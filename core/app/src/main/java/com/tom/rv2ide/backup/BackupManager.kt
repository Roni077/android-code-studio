/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.backup

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tom.rv2ide.backup.models.BackupCategory
import com.tom.rv2ide.backup.models.BackupInspection
import com.tom.rv2ide.backup.models.BackupManifest
import com.tom.rv2ide.buildinfo.BuildInfo
import com.tom.rv2ide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {

    private val log = LoggerFactory.getLogger(BackupManager::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private const val PREFIX_PREFERENCES = "preferences/"
    private const val PREFIX_FONTS = "ui/fonts/"
    private const val PREFIX_SCHEMES = "ui/schemes/"
    private const val PREFIX_INIT = "init/"
    private const val PREFIX_TERMINAL = "terminal/"

    /**
     * Creates a backup archive (.zip) containing data for the requested categories.
     */
    suspend fun createBackup(
        context: Context,
        outputStream: OutputStream,
        categories: Set<BackupCategory>
    ): Result<BackupManifest> = withContext(Dispatchers.IO) {
        try {
            var fileCount = 0
            val zipOut = ZipOutputStream(BufferedOutputStream(outputStream))

            val includedCategoryIds = categories.map { it.id }

            // 1. Preferences & Recent Projects
            if (categories.contains(BackupCategory.PREFERENCES) || categories.contains(BackupCategory.RECENT_PROJECTS)) {
                val includeRecent = categories.contains(BackupCategory.RECENT_PROJECTS)
                val prefDumps = PreferencesBackupHelper.exportAllPreferences(context, includeRecent)

                for (dump in prefDumps) {
                    val json = PreferencesBackupHelper.toJson(dump)
                    val entryName = "$PREFIX_PREFERENCES${dump.name}.json"
                    zipOut.putNextEntry(ZipEntry(entryName))
                    zipOut.write(json.toByteArray(StandardCharsets.UTF_8))
                    zipOut.closeEntry()
                    fileCount++
                }
            }

            // 2. Custom Fonts
            if (categories.contains(BackupCategory.FONTS)) {
                val fontDir = Environment.ANDROIDIDE_UI
                if (fontDir.exists() && fontDir.isDirectory) {
                    val fontFiles = fontDir.listFiles { file ->
                        file.isFile && (file.extension.equals("ttf", ignoreCase = true) ||
                                file.extension.equals("otf", ignoreCase = true))
                    }
                    fontFiles?.forEach { fontFile ->
                        zipOut.putNextEntry(ZipEntry("$PREFIX_FONTS${fontFile.name}"))
                        fontFile.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        fileCount++
                    }
                }
            }

            // 3. Custom Color Schemes
            if (categories.contains(BackupCategory.COLOR_SCHEMES)) {
                val schemesDir = File(Environment.ANDROIDIDE_UI, "editor/schemes")
                if (schemesDir.exists() && schemesDir.isDirectory) {
                    schemesDir.listFiles { file -> file.isFile }?.forEach { schemeFile ->
                        zipOut.putNextEntry(ZipEntry("$PREFIX_SCHEMES${schemeFile.name}"))
                        schemeFile.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        fileCount++
                    }
                }
            }

            // 4. Gradle Init Script
            if (categories.contains(BackupCategory.GRADLE_INIT)) {
                val initScript = Environment.INIT_SCRIPT
                if (initScript.exists() && initScript.isFile) {
                    zipOut.putNextEntry(ZipEntry("$PREFIX_INIT${initScript.name}"))
                    initScript.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                    fileCount++
                }
            }

            // 5. Terminal Configurations (.bashrc, .profile, .gitconfig)
            if (categories.contains(BackupCategory.TERMINAL_CONFIG)) {
                val homeDir = Environment.HOME
                val shellConfigs = listOf(".bashrc", ".profile", ".gitconfig", ".inputrc")
                for (configName in shellConfigs) {
                    val configFile = File(homeDir, configName)
                    if (configFile.exists() && configFile.isFile) {
                        zipOut.putNextEntry(ZipEntry("$PREFIX_TERMINAL$configName"))
                        configFile.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        fileCount++
                    }
                }
            }

            // 6. Write Manifest
            val manifest = BackupManifest(
                version = BackupManifest.CURRENT_VERSION,
                createdAt = System.currentTimeMillis(),
                appVersion = BuildInfo.VERSION_NAME,
                packageName = context.packageName,
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                includedCategories = includedCategoryIds,
                fileCount = fileCount
            )

            val manifestJson = gson.toJson(manifest)
            zipOut.putNextEntry(ZipEntry(BackupManifest.MANIFEST_FILE_NAME))
            zipOut.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            zipOut.closeEntry()

            zipOut.finish()
            zipOut.flush()

            log.info("Backup successfully created with {} files", fileCount)
            Result.success(manifest)
        } catch (e: Exception) {
            log.error("Failed to create backup", e)
            Result.failure(e)
        }
    }

    suspend fun inspectBackup(
        context: Context,
        inputStream: InputStream
    ): BackupInspection = inspectBackup(inputStream)

    /**
     * Inspects a backup archive stream and reads its manifest and available categories.
     */
    suspend fun inspectBackup(
        inputStream: InputStream
    ): BackupInspection = withContext(Dispatchers.IO) {
        try {
            val zipIn = ZipInputStream(BufferedInputStream(inputStream))
            var manifest: BackupManifest? = null
            val detectedCategories = mutableSetOf<BackupCategory>()

            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == BackupManifest.MANIFEST_FILE_NAME -> {
                        val content = readEntryText(zipIn)
                        manifest = gson.fromJson(content, BackupManifest::class.java)
                    }
                    name.startsWith(PREFIX_PREFERENCES) -> detectedCategories.add(BackupCategory.PREFERENCES)
                    name.startsWith(PREFIX_FONTS) -> detectedCategories.add(BackupCategory.FONTS)
                    name.startsWith(PREFIX_SCHEMES) -> detectedCategories.add(BackupCategory.COLOR_SCHEMES)
                    name.startsWith(PREFIX_INIT) -> detectedCategories.add(BackupCategory.GRADLE_INIT)
                    name.startsWith(PREFIX_TERMINAL) -> detectedCategories.add(BackupCategory.TERMINAL_CONFIG)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            if (manifest != null) {
                manifest.includedCategories.forEach { id ->
                    BackupCategory.fromId(id)?.let { detectedCategories.add(it) }
                }
                BackupInspection(
                    isValid = true,
                    manifest = manifest,
                    availableCategories = detectedCategories
                )
            } else {
                BackupInspection(
                    isValid = false,
                    errorMessage = "Manifest not found in backup archive"
                )
            }
        } catch (e: Exception) {
            log.error("Failed to inspect backup archive", e)
            BackupInspection(
                isValid = false,
                errorMessage = e.message ?: "Failed to read backup archive"
            )
        }
    }

    /**
     * Restores selected categories from a backup archive stream.
     * Includes automatic pre-restore snapshot and rollback protection.
     */
    suspend fun restoreBackup(
        context: Context,
        inputStream: InputStream,
        selectedCategories: Set<BackupCategory>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val snapshotDir = File(context.cacheDir, "pre_restore_snapshot_${System.currentTimeMillis()}")
        try {
            // Take pre-restore snapshot for rollback safety
            createPreRestoreSnapshot(context, snapshotDir)

            val zipIn = ZipInputStream(BufferedInputStream(inputStream))
            var restoredCount = 0

            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val name = entry.name

                when {
                    name.startsWith(PREFIX_PREFERENCES) &&
                            (selectedCategories.contains(BackupCategory.PREFERENCES) ||
                                    selectedCategories.contains(BackupCategory.RECENT_PROJECTS)) -> {
                        val json = readEntryText(zipIn)
                        val dump = PreferencesBackupHelper.fromJson(json)
                        if (dump != null) {
                            if (PreferencesBackupHelper.restorePreferences(context, dump, selectedCategories)) {
                                restoredCount++
                            }
                        }
                    }

                    name.startsWith(PREFIX_FONTS) && selectedCategories.contains(BackupCategory.FONTS) -> {
                        val fileName = name.removePrefix(PREFIX_FONTS)
                        if (fileName.isNotBlank()) {
                            val fontDir = Environment.ANDROIDIDE_UI
                            val destFile = File(fontDir, fileName)
                            copyEntryToFile(zipIn, destFile)
                            restoredCount++
                        }
                    }

                    name.startsWith(PREFIX_SCHEMES) && selectedCategories.contains(BackupCategory.COLOR_SCHEMES) -> {
                        val fileName = name.removePrefix(PREFIX_SCHEMES)
                        if (fileName.isNotBlank()) {
                            val schemesDir = File(Environment.ANDROIDIDE_UI, "editor/schemes")
                            val destFile = File(schemesDir, fileName)
                            copyEntryToFile(zipIn, destFile)
                            restoredCount++
                        }
                    }

                    name.startsWith(PREFIX_INIT) && selectedCategories.contains(BackupCategory.GRADLE_INIT) -> {
                        val initScript = Environment.INIT_SCRIPT
                        copyEntryToFile(zipIn, initScript)
                        restoredCount++
                    }

                    name.startsWith(PREFIX_TERMINAL) && selectedCategories.contains(BackupCategory.TERMINAL_CONFIG) -> {
                        val fileName = name.removePrefix(PREFIX_TERMINAL)
                        if (fileName.isNotBlank()) {
                            val destFile = File(Environment.HOME, fileName)
                            copyEntryToFile(zipIn, destFile)
                            restoredCount++
                        }
                    }
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            // Clean up snapshot on success
            snapshotDir.deleteRecursively()
            log.info("Backup successfully restored ({} items restored)", restoredCount)
            Result.success(restoredCount)
        } catch (e: Exception) {
            log.error("Failed to restore backup, initiating rollback", e)
            rollbackFromSnapshot(context, snapshotDir)
            Result.failure(e)
        }
    }

    /**
     * Resets all IDE preferences to default state.
     */
    suspend fun resetPreferences(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (PreferencesBackupHelper.resetAllPreferences(context)) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Failed to reset preferences"))
            }
        } catch (e: Exception) {
            log.error("Failed to reset preferences", e)
            Result.failure(e)
        }
    }

    private fun readEntryBytes(zipIn: ZipInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var count: Int
        while (zipIn.read(buffer).also { count = it } != -1) {
            baos.write(buffer, 0, count)
        }
        return baos.toByteArray()
    }

    private fun readEntryText(zipIn: ZipInputStream): String {
        return String(readEntryBytes(zipIn), StandardCharsets.UTF_8)
    }

    private fun copyEntryToFile(zipIn: ZipInputStream, destFile: File) {
        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val buffer = ByteArray(4096)
        var count: Int
        destFile.outputStream().use { out ->
            while (zipIn.read(buffer).also { count = it } != -1) {
                out.write(buffer, 0, count)
            }
        }
    }

    private fun createPreRestoreSnapshot(context: Context, snapshotDir: File) {
        try {
            if (!snapshotDir.exists()) snapshotDir.mkdirs()
            val prefDumps = PreferencesBackupHelper.exportAllPreferences(context, true)
            val prefsDir = File(snapshotDir, "preferences").apply { mkdirs() }
            for (dump in prefDumps) {
                File(prefsDir, "${dump.name}.json").writeText(PreferencesBackupHelper.toJson(dump))
            }
        } catch (e: Exception) {
            log.warn("Failed to create pre-restore snapshot: {}", e.message)
        }
    }

    private fun rollbackFromSnapshot(context: Context, snapshotDir: File) {
        try {
            val prefsDir = File(snapshotDir, "preferences")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                    val dump = PreferencesBackupHelper.fromJson(file.readText())
                    if (dump != null) {
                        PreferencesBackupHelper.restorePreferences(context, dump, BackupCategory.entries.toSet())
                    }
                }
            }
            snapshotDir.deleteRecursively()
            log.info("Rollback completed successfully")
        } catch (e: Exception) {
            log.error("Rollback failed", e)
        }
    }
}
