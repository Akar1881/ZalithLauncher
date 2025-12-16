package com.movtery.zalithlauncher.feature.mod.update

import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.mod.parser.ModInfo
import java.io.File
import java.util.Date

data class ModUpdateInfo(
    val modInfo: ModInfo,
    val currentFile: File,
    val currentHash: String,
    val platform: Platform,
    val projectId: String,
    val projectSlug: String?,
    val latestVersionTitle: String,
    val latestVersionNumber: String,
    val latestFileName: String,
    val latestFileUrl: String,
    val latestFileHash: String?,
    val uploadDate: Date?,
    val changelog: String?
) {
    var isSelected: Boolean = true
    
    fun hasUpdate(): Boolean = currentHash != latestFileHash
    
    fun getDisplayName(): String = modInfo.name ?: modInfo.id ?: currentFile.nameWithoutExtension
}
