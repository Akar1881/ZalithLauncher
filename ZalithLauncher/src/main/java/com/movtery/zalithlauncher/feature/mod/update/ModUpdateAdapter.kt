package com.movtery.zalithlauncher.feature.mod.update

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.ItemModUpdateBinding
import com.movtery.zalithlauncher.feature.download.enums.Platform

class ModUpdateAdapter(
    private val updates: List<ModUpdateInfo>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ModUpdateAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemModUpdateBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModUpdateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val update = updates[position]
        val context = holder.binding.root.context

        holder.binding.apply {
            modName.text = update.getDisplayName()
            platformBadge.text = when (update.platform) {
                Platform.MODRINTH -> "Modrinth"
                Platform.CURSEFORGE -> "CurseForge"
            }
            currentVersion.text = context.getString(
                R.string.mod_update_current_version,
                update.modInfo.version ?: "Unknown"
            )
            newVersion.text = context.getString(
                R.string.mod_update_new_version,
                update.latestVersionNumber
            )
            
            checkbox.isChecked = update.isSelected
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                update.isSelected = isChecked
                onSelectionChanged()
            }
            
            root.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    override fun getItemCount(): Int = updates.size

    fun getSelectedUpdates(): List<ModUpdateInfo> = updates.filter { it.isSelected }
    
    fun selectAll(selected: Boolean) {
        updates.forEach { it.isSelected = selected }
        notifyDataSetChanged()
        onSelectionChanged()
    }
}
