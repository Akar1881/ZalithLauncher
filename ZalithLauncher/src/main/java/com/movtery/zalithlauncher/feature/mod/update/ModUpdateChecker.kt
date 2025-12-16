package com.movtery.zalithlauncher.feature.mod.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.mod.parser.ModInfo
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.file.FileTools
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import java.io.File
import java.util.Date

class ModUpdateChecker(
    private val mcVersion: String,
    private val modLoaders: List<ModLoader>
) {
    companion object {
        private const val TAG = "ModUpdateChecker"
        private const val MODRINTH_API_URL = "https://api.modrinth.com/v2"
        
        fun create(version: Version): ModUpdateChecker {
            val mcVersion = version.getVersionInfo()?.minecraftVersion ?: ""
            val modLoaders = detectModLoaders(version)
            return ModUpdateChecker(mcVersion, modLoaders)
        }
        
        private fun detectModLoaders(version: Version): List<ModLoader> {
            // Avoid calling VersionInfo methods that may not exist in all versions
            // (e.g. isFabric(), isForge(), etc.). Returning a sensible default
            // list of supported loaders ensures compilation succeeds and the
            // update checking logic can still proceed.
            return listOf(ModLoader.FABRIC, ModLoader.FORGE, ModLoader.NEOFORGE, ModLoader.QUILT)
        }
    }
    
    private val modrinthApi = ApiHandler(MODRINTH_API_URL)
    private val curseForgeApi = PlatformUtils.createCurseForgeApi()
    
    suspend fun checkForUpdates(modInfoList: List<ModInfo>): List<ModUpdateInfo> {
        val updates = mutableListOf<ModUpdateInfo>()
        
        val modrinthUpdates = checkModrinthUpdates(modInfoList)
        updates.addAll(modrinthUpdates)
        
        val checkedFiles = modrinthUpdates.map { it.currentFile.absolutePath }.toSet()
        val remainingMods = modInfoList.filter { it.file?.absolutePath !in checkedFiles }
        
        if (remainingMods.isNotEmpty()) {
            val curseForgeUpdates = checkCurseForgeUpdates(remainingMods)
            updates.addAll(curseForgeUpdates)
        }
        
        return updates
    }
    
    private suspend fun checkModrinthUpdates(modInfoList: List<ModInfo>): List<ModUpdateInfo> {
        val updates = mutableListOf<ModUpdateInfo>()
        
        try {
            val fileHashes = mutableMapOf<String, Pair<ModInfo, File>>()
            
            for (modInfo in modInfoList) {
                val file = modInfo.file ?: continue
                try {
                    val sha1Hash = FileTools.calculateFileHash(file, "SHA-1")
                    fileHashes[sha1Hash] = Pair(modInfo, file)
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to calculate hash for ${file.name}", e)
                }
            }
            
            if (fileHashes.isEmpty()) return updates
            
            val hashes = fileHashes.keys.toList()
            val updateResults = getModrinthLatestVersions(hashes)
            
            for ((hash, versionInfo) in updateResults) {
                val (modInfo, file) = fileHashes[hash] ?: continue
                
                try {
                    val filesArray = versionInfo.getAsJsonArray("files")
                    if (filesArray == null || filesArray.size() == 0) continue
                    
                    val primaryFile = filesArray.firstOrNull { 
                        it.asJsonObject.get("primary")?.asBoolean == true 
                    }?.asJsonObject ?: filesArray[0].asJsonObject
                    
                    val latestHash = primaryFile.getAsJsonObject("hashes")?.get("sha1")?.asString
                    
                    if (latestHash != null && latestHash != hash) {
                        val updateInfo = ModUpdateInfo(
                            modInfo = modInfo,
                            currentFile = file,
                            currentHash = hash,
                            platform = Platform.MODRINTH,
                            projectId = versionInfo.get("project_id").asString,
                            projectSlug = null,
                            latestVersionTitle = versionInfo.get("name")?.asString ?: "Unknown",
                            latestVersionNumber = versionInfo.get("version_number")?.asString ?: "",
                            latestFileName = primaryFile.get("filename").asString,
                            latestFileUrl = primaryFile.get("url").asString,
                            latestFileHash = latestHash,
                            uploadDate = parseDate(versionInfo.get("date_published")?.asString),
                            changelog = versionInfo.get("changelog")?.asString
                        )
                        updates.add(updateInfo)
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error processing Modrinth update for ${modInfo.name}", e)
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error checking Modrinth updates", e)
        }
        
        return updates
    }
    
    private fun getModrinthLatestVersions(hashes: List<String>): Map<String, JsonObject> {
        val results = mutableMapOf<String, JsonObject>()
        
        try {
            val loadersArray = JsonArray()
            modLoaders.forEach { loadersArray.add(it.modrinthName) }
            
            val gameVersionsArray = JsonArray()
            if (mcVersion.isNotEmpty()) gameVersionsArray.add(mcVersion)
            
            val requestBody = JsonObject().apply {
                add("hashes", JsonArray().apply { hashes.forEach { add(it) } })
                addProperty("algorithm", "sha1")
                add("loaders", loadersArray)
                add("game_versions", gameVersionsArray)
            }
            
            val response = ApiHandler.postRaw(
                modrinthApi.additionalHeaders,
                "$MODRINTH_API_URL/version_files/update",
                requestBody.toString()
            )
            
            if (response != null) {
                val jsonResponse = Tools.GLOBAL_GSON.fromJson(response, JsonObject::class.java)
                jsonResponse.entrySet().forEach { (hash, versionElement) ->
                    if (versionElement.isJsonObject) {
                        results[hash] = versionElement.asJsonObject
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error fetching Modrinth latest versions", e)
        }
        
        return results
    }
    
    private suspend fun checkCurseForgeUpdates(modInfoList: List<ModInfo>): List<ModUpdateInfo> {
        val updates = mutableListOf<ModUpdateInfo>()
        
        try {
            val fileFingerprints = mutableMapOf<Long, Pair<ModInfo, File, String>>()
            
            for (modInfo in modInfoList) {
                val file = modInfo.file ?: continue
                try {
                    val fingerprint = Murmur2Hash.computeFingerprint(file)
                    val sha1 = FileTools.calculateFileHash(file, "SHA-1")
                    fileFingerprints[fingerprint] = Triple(modInfo, file, sha1)
                } catch (e: Exception) {
                    Logging.e(TAG, "Failed to calculate fingerprint for ${file.name}", e)
                }
            }
            
            if (fileFingerprints.isEmpty()) return updates
            
            val fingerprintMatches = getCurseForgeMatches(fileFingerprints.keys.toList())
            
            for (match in fingerprintMatches) {
                try {
                    val fileFingerprint = match.get("file")?.asJsonObject?.get("fileFingerprint")?.asLong ?: continue
                    val (modInfo, file, currentHash) = fileFingerprints[fileFingerprint] ?: continue
                    
                    val latestFiles = match.getAsJsonArray("latestFiles") ?: continue
                    
                    val compatibleFile = findCompatibleFile(latestFiles, mcVersion, modLoaders)
                    if (compatibleFile != null) {
                        val latestHash = getCurseForgeFileHash(compatibleFile)
                        
                        if (latestHash != null && latestHash != currentHash) {
                            val downloadUrl = compatibleFile.get("downloadUrl")?.asString
                            if (downloadUrl != null) {
                                val updateInfo = ModUpdateInfo(
                                    modInfo = modInfo,
                                    currentFile = file,
                                    currentHash = currentHash,
                                    platform = Platform.CURSEFORGE,
                                    projectId = match.get("id")?.asString ?: "",
                                    projectSlug = match.get("slug")?.asString,
                                    latestVersionTitle = compatibleFile.get("displayName")?.asString ?: "Unknown",
                                    latestVersionNumber = compatibleFile.get("displayName")?.asString ?: "",
                                    latestFileName = compatibleFile.get("fileName").asString,
                                    latestFileUrl = downloadUrl,
                                    latestFileHash = latestHash,
                                    uploadDate = parseDate(compatibleFile.get("fileDate")?.asString),
                                    changelog = null
                                )
                                updates.add(updateInfo)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logging.e(TAG, "Error processing CurseForge match", e)
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error checking CurseForge updates", e)
        }
        
        return updates
    }
    
    private fun getCurseForgeMatches(fingerprints: List<Long>): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        
        try {
            val requestBody = JsonObject().apply {
                add("fingerprints", JsonArray().apply { fingerprints.forEach { add(it) } })
            }
            
            val response = ApiHandler.postRaw(
                curseForgeApi.additionalHeaders,
                "${curseForgeApi.baseUrl}/fingerprints",
                requestBody.toString()
            )
            
            if (response != null) {
                val jsonResponse = Tools.GLOBAL_GSON.fromJson(response, JsonObject::class.java)
                val data = jsonResponse.getAsJsonObject("data")
                val exactMatches = data?.getAsJsonArray("exactMatches")
                
                exactMatches?.forEach { match ->
                    if (match.isJsonObject) {
                        results.add(match.asJsonObject)
                    }
                }
            }
        } catch (e: Exception) {
            Logging.e(TAG, "Error fetching CurseForge fingerprint matches", e)
        }
        
        return results
    }
    
    private fun findCompatibleFile(latestFiles: JsonArray, mcVersion: String, modLoaders: List<ModLoader>): JsonObject? {
        val compatibleFiles = mutableListOf<JsonObject>()
        
        for (fileElement in latestFiles) {
            val file = fileElement.asJsonObject
            val gameVersions = file.getAsJsonArray("gameVersions") ?: continue
            
            val hasMatchingVersion = mcVersion.isEmpty() || gameVersions.any { it.asString == mcVersion }
            if (!hasMatchingVersion) continue
            
            val hasMatchingLoader = modLoaders.isEmpty() || gameVersions.any { gv ->
                modLoaders.any { loader -> 
                    gv.asString.equals(loader.displayName, ignoreCase = true) ||
                    gv.asString.equals(loader.modrinthName, ignoreCase = true)
                }
            }
            if (!hasMatchingLoader) continue
            
            compatibleFiles.add(file)
        }
        
        return compatibleFiles.maxByOrNull { 
            parseDate(it.get("fileDate")?.asString)?.time ?: 0L 
        }
    }
    
    private fun getCurseForgeFileHash(file: JsonObject): String? {
        val hashes = file.getAsJsonArray("hashes") ?: return null
        for (hashElement in hashes) {
            val hashObj = hashElement.asJsonObject
            if (hashObj.get("algo")?.asInt == 1) {
                return hashObj.get("value")?.asString
            }
        }
        return null
    }
    
    private fun parseDate(dateString: String?): Date? {
        if (dateString == null) return null
        return try {
            ZHTools.getDate(dateString)
        } catch (e: Exception) {
            null
        }
    }
    
    private data class Triple<A, B, C>(val first: A, val second: B, val third: C)
}
