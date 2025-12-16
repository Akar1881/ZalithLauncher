package com.movtery.zalithlauncher.feature.mod.update

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.DialogModUpdateBinding
import com.movtery.zalithlauncher.feature.download.install.InstallHelper
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.parser.ModInfo
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.DraggableDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModUpdateDialog(
    private val context: Context,
    private val version: Version,
    private val modInfoList: List<ModInfo>,
    private val modsDir: File,
    private val onUpdateComplete: () -> Unit
) {
    companion object {
        private const val TAG = "ModUpdateDialog"
    }
    
    private var dialog: AlertDialog? = null
    private lateinit var binding: DialogModUpdateBinding
    private var adapter: ModUpdateAdapter? = null
    private var updates: List<ModUpdateInfo> = emptyList()
    
    fun show() {
        binding = DialogModUpdateBinding.inflate(android.view.LayoutInflater.from(context))
        
        dialog = DraggableDialog.DialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .build()
        
        dialog?.show()
        
        checkForUpdates()
    }
    
    private fun checkForUpdates() {
        binding.statusText.text = context.getString(R.string.mod_update_checking)
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.updatesList.visibility = View.GONE
        binding.buttonsLayout.visibility = View.GONE
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val checker = ModUpdateChecker.create(version)
                val foundUpdates = checker.checkForUpdates(modInfoList)
                
                withContext(Dispatchers.Main) {
                    updates = foundUpdates
                    showResults()
                }
            } catch (e: Exception) {
                Logging.e(TAG, "Error checking for updates", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.text = context.getString(R.string.mod_update_error)
                }
            }
        }
    }
    
    private fun showResults() {
        binding.progressBar.visibility = View.GONE
        
        if (updates.isEmpty()) {
            binding.statusText.text = context.getString(R.string.mod_update_no_updates)
            binding.buttonsLayout.visibility = View.VISIBLE
            binding.btnUpdate.visibility = View.GONE
            binding.btnCancel.text = context.getString(R.string.generic_ok)
            binding.btnCancel.setOnClickListener { dialog?.dismiss() }
        } else {
            binding.statusText.text = context.getString(R.string.mod_update_found, updates.size)
            binding.updatesList.visibility = View.VISIBLE
            binding.buttonsLayout.visibility = View.VISIBLE
            binding.btnUpdate.visibility = View.VISIBLE
            
            adapter = ModUpdateAdapter(updates) {
                updateButtonState()
            }
            binding.updatesList.layoutManager = LinearLayoutManager(context)
            binding.updatesList.adapter = adapter
            
            binding.btnCancel.setOnClickListener { dialog?.dismiss() }
            binding.btnUpdate.setOnClickListener { downloadUpdates() }
            
            updateButtonState()
        }
    }
    
    private fun updateButtonState() {
        val selectedCount = adapter?.getSelectedUpdates()?.size ?: 0
        binding.btnUpdate.isEnabled = selectedCount > 0
        binding.btnUpdate.text = if (selectedCount == updates.size) {
            context.getString(R.string.mod_update_update_all)
        } else {
            context.getString(R.string.mod_update_update_selected)
        }
    }
    
    private fun downloadUpdates() {
        val selectedUpdates = adapter?.getSelectedUpdates() ?: return
        if (selectedUpdates.isEmpty()) return
        
        binding.btnUpdate.isEnabled = false
        binding.btnCancel.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = selectedUpdates.size
        binding.progressBar.progress = 0
        
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var failCount = 0
            
            for ((index, update) in selectedUpdates.withIndex()) {
                withContext(Dispatchers.Main) {
                    binding.statusText.text = context.getString(
                        R.string.mod_update_downloading_progress,
                        update.getDisplayName(),
                        index + 1,
                        selectedUpdates.size
                    )
                }
                
                try {
                    val oldFile = update.currentFile
                    val newFileName = update.latestFileName
                    val newFile = File(modsDir, newFileName)
                    
                    val tempFile = File(modsDir, "${newFileName}.tmp")
                    
                    downloadFile(update.latestFileUrl, tempFile)
                    
                    val disabledFile = File(oldFile.absolutePath + ".disabled")
                    if (disabledFile.exists()) {
                        disabledFile.delete()
                    }
                    
                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                    
                    if (newFile.exists() && newFile != tempFile) {
                        newFile.delete()
                    }
                    
                    tempFile.renameTo(newFile)
                    
                    successCount++
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to download update for ${update.getDisplayName()}", e)
                    failCount++
                }
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.progress = index + 1
                }
            }
            
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.btnCancel.isEnabled = true
                
                if (failCount == 0) {
                    binding.statusText.text = context.getString(R.string.mod_update_success, successCount)
                } else {
                    binding.statusText.text = context.getString(R.string.mod_update_failed)
                }
                
                binding.btnUpdate.visibility = View.GONE
                binding.btnCancel.text = context.getString(R.string.generic_close)
                binding.btnCancel.setOnClickListener {
                    dialog?.dismiss()
                    if (successCount > 0) {
                        onUpdateComplete()
                    }
                }
            }
        }
    }
    
    private fun downloadFile(url: String, targetFile: File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "ZalithLauncher")
        
        connection.inputStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        
        connection.disconnect()
    }
}
