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
import java.util.zip.Deflater
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
    private const val PREFIX_TERMINAL_ENV = "terminal/env/"
    private const val PREFIX_SDK = "toolchains/sdk/"
    private const val PREFIX_FLUTTER = "toolchains/flutter/"
    private const val PREFIX_PUB_CACHE = "toolchains/pub-cache/"
    private const val PREFIX_GRADLE_CACHE = "toolchains/gradle/"
    private const val PREFIX_LSP = "toolchains/lsp/"

    /**
     * Inspects magic bytes to verify whether the stream represents a ZIP archive.
     */
    fun isZipStream(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() && // 'P'
                bytes[1] == 0x4B.toByte() && // 'K'
                (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
    }

    /**
     * Creates a backup archive (.zip) containing data for the requested categories.
     * Uses streaming compression with Deflater.BEST_SPEED to handle multi-gigabyte toolchains efficiently.
     */
    suspend fun createBackup(
        context: Context,
        outputStream: OutputStream,
        categories: Set<BackupCategory>
    ): Result<BackupManifest> = withContext(Dispatchers.IO) {
        try {
            var fileCount = 0
            val zipOut = ZipOutputStream(BufferedOutputStream(outputStream, 65536)).apply {
                setLevel(Deflater.BEST_SPEED)
            }

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

            // 5. Terminal Configurations (.bashrc, .profile, .gitconfig, and .termux/ styling)
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

                // Add .termux styling configs
                val termuxDir = File(homeDir, ".termux")
                if (termuxDir.exists() && termuxDir.isDirectory) {
                    termuxDir.listFiles { f -> f.isFile }?.forEach { file ->
                        zipOut.putNextEntry(ZipEntry("$PREFIX_TERMINAL.termux/${file.name}"))
                        file.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        fileCount++
                    }
                }
            }

            // 6. Terminal Environment (User packages & scripts in $HOME)
            if (categories.contains(BackupCategory.TERMINAL_ENVIRONMENT)) {
                val homeDir = Environment.HOME
                val userDirs = listOf("bin", ".config", ".local")
                for (subName in userDirs) {
                    val dir = File(homeDir, subName)
                    if (dir.exists() && dir.isDirectory) {
                        fileCount += zipDirectoryRecursively(dir, "$PREFIX_TERMINAL_ENV$subName/", zipOut)
                    }
                }
            }

            // 7. Android SDK & Tooling (~/android-sdk)
            if (categories.contains(BackupCategory.ANDROID_SDK)) {
                val sdkDir = Environment.ANDROID_HOME
                if (sdkDir.exists() && sdkDir.isDirectory) {
                    log.info("Archiving Android SDK from: {}", sdkDir.absolutePath)
                    fileCount += zipDirectoryRecursively(sdkDir, PREFIX_SDK, zipOut)
                }
            }

            // 8. Flutter & Dart SDK
            if (categories.contains(BackupCategory.FLUTTER_SDK)) {
                val flutterHome = Environment.FLUTTER_HOME
                if (flutterHome.exists() && flutterHome.isDirectory) {
                    log.info("Archiving Flutter SDK from: {}", flutterHome.absolutePath)
                    fileCount += zipDirectoryRecursively(flutterHome, PREFIX_FLUTTER, zipOut)
                }

                val pubCache = File(Environment.HOME, ".pub-cache")
                if (pubCache.exists() && pubCache.isDirectory) {
                    fileCount += zipDirectoryRecursively(pubCache, PREFIX_PUB_CACHE, zipOut)
                }
            }

            // 9. Gradle Caches & Distributions (~/.gradle)
            if (categories.contains(BackupCategory.GRADLE_CACHE)) {
                val gradleHome = Environment.GRADLE_USER_HOME
                val cachesDir = File(gradleHome, "caches")
                if (cachesDir.exists() && cachesDir.isDirectory) {
                    fileCount += zipDirectoryRecursively(cachesDir, "$PREFIX_GRADLE_CACHE/caches/", zipOut)
                }
                val wrapperDir = File(gradleHome, "wrapper")
                if (wrapperDir.exists() && wrapperDir.isDirectory) {
                    fileCount += zipDirectoryRecursively(wrapperDir, "$PREFIX_GRADLE_CACHE/wrapper/", zipOut)
                }
            }

            // 10. Language Servers (Kotlin & Clangd)
            if (categories.contains(BackupCategory.LSP_SERVERS)) {
                val serversDir = Environment.SERVERS_DIR
                if (serversDir.exists() && serversDir.isDirectory) {
                    fileCount += zipDirectoryRecursively(serversDir, PREFIX_LSP, zipOut)
                }
            }

            // Write Manifest
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

    /**
     * Inspects a backup stream and identifies whether it is a ZIP archive or standalone JSON.
     */
    suspend fun inspectBackup(
        inputStream: InputStream
    ): BackupInspection = withContext(Dispatchers.IO) {
        val bufferedIn = BufferedInputStream(inputStream, 65536)
        bufferedIn.mark(1024)
        val header = ByteArray(4)
        val readBytes = bufferedIn.read(header)
        bufferedIn.reset()

        if (readBytes >= 4 && isZipStream(header)) {
            inspectZipArchive(bufferedIn)
        } else {
            inspectJsonFile(bufferedIn)
        }
    }

    private fun inspectZipArchive(inputStream: InputStream): BackupInspection {
        try {
            val zipIn = ZipInputStream(inputStream)
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
                    name.startsWith(PREFIX_TERMINAL_ENV) -> detectedCategories.add(BackupCategory.TERMINAL_ENVIRONMENT)
                    name.startsWith(PREFIX_SDK) -> detectedCategories.add(BackupCategory.ANDROID_SDK)
                    name.startsWith(PREFIX_FLUTTER) || name.startsWith(PREFIX_PUB_CACHE) -> detectedCategories.add(BackupCategory.FLUTTER_SDK)
                    name.startsWith(PREFIX_GRADLE_CACHE) -> detectedCategories.add(BackupCategory.GRADLE_CACHE)
                    name.startsWith(PREFIX_LSP) -> detectedCategories.add(BackupCategory.LSP_SERVERS)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            if (manifest != null) {
                manifest.includedCategories.forEach { id ->
                    BackupCategory.fromId(id)?.let { detectedCategories.add(it) }
                }
                return BackupInspection(
                    isValid = true,
                    manifest = manifest,
                    availableCategories = detectedCategories
                )
            } else {
                return BackupInspection(
                    isValid = detectedCategories.isNotEmpty(),
                    availableCategories = detectedCategories,
                    errorMessage = if (detectedCategories.isEmpty()) "Manifest not found in backup archive" else null
                )
            }
        } catch (e: Exception) {
            log.error("Failed to inspect zip backup archive", e)
            return BackupInspection(isValid = false, errorMessage = e.message ?: "Failed to read zip archive")
        }
    }

    private fun inspectJsonFile(inputStream: InputStream): BackupInspection {
        return try {
            val content = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (content.isNotBlank() && (content.trim().startsWith("{") || content.trim().startsWith("["))) {
                BackupInspection(
                    isValid = true,
                    isStandaloneJson = true,
                    availableCategories = setOf(BackupCategory.PREFERENCES)
                )
            } else {
                BackupInspection(isValid = false, errorMessage = "Selected file is neither a valid backup ZIP nor JSON")
            }
        } catch (e: Exception) {
            BackupInspection(isValid = false, errorMessage = "Failed to parse JSON file: ${e.message}")
        }
    }

    /**
     * Restores selected categories from a backup stream.
     * Auto-detects whether input is ZIP or standalone JSON, taking pre-restore snapshots.
     */
    suspend fun restoreBackup(
        context: Context,
        inputStream: InputStream,
        selectedCategories: Set<BackupCategory>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val snapshotDir = File(context.cacheDir, "pre_restore_snapshot_${System.currentTimeMillis()}")
        try {
            createPreRestoreSnapshot(context, snapshotDir)

            val bufferedIn = BufferedInputStream(inputStream, 65536)
            bufferedIn.mark(1024)
            val header = ByteArray(4)
            val readBytes = bufferedIn.read(header)
            bufferedIn.reset()

            var restoredCount = 0

            if (readBytes >= 4 && isZipStream(header)) {
                restoredCount = restoreZipArchive(context, bufferedIn, selectedCategories)
            } else {
                val json = bufferedIn.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                if (PreferencesBackupHelper.importAllPreferencesFromJson(context, json)) {
                    restoredCount = 1
                } else {
                    throw IOException("Failed to import preferences from JSON")
                }
            }

            snapshotDir.deleteRecursively()
            log.info("Backup successfully restored ({} items restored)", restoredCount)
            Result.success(restoredCount)
        } catch (e: Exception) {
            log.error("Failed to restore backup, initiating rollback", e)
            rollbackFromSnapshot(context, snapshotDir)
            Result.failure(e)
        }
    }

    private fun restoreZipArchive(
        context: Context,
        inputStream: InputStream,
        selectedCategories: Set<BackupCategory>
    ): Int {
        val zipIn = ZipInputStream(inputStream)
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
                        copyEntryToFile(zipIn, File(Environment.ANDROIDIDE_UI, fileName))
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_SCHEMES) && selectedCategories.contains(BackupCategory.COLOR_SCHEMES) -> {
                    val fileName = name.removePrefix(PREFIX_SCHEMES)
                    if (fileName.isNotBlank()) {
                        val schemesDir = File(Environment.ANDROIDIDE_UI, "editor/schemes")
                        copyEntryToFile(zipIn, File(schemesDir, fileName))
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_INIT) && selectedCategories.contains(BackupCategory.GRADLE_INIT) -> {
                    copyEntryToFile(zipIn, Environment.INIT_SCRIPT)
                    restoredCount++
                }

                name.startsWith(PREFIX_TERMINAL) && selectedCategories.contains(BackupCategory.TERMINAL_CONFIG) -> {
                    val relPath = name.removePrefix(PREFIX_TERMINAL)
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.HOME, relPath)
                        copyEntryToFile(zipIn, destFile)
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_TERMINAL_ENV) && selectedCategories.contains(BackupCategory.TERMINAL_ENVIRONMENT) -> {
                    val relPath = name.removePrefix(PREFIX_TERMINAL_ENV)
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.HOME, relPath)
                        copyEntryToFile(zipIn, destFile)
                        if (destFile.parentFile?.name == "bin" || destFile.extension == "sh") {
                            destFile.setExecutable(true, false)
                        }
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_SDK) && selectedCategories.contains(BackupCategory.ANDROID_SDK) -> {
                    val relPath = name.removePrefix(PREFIX_SDK)
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.ANDROID_HOME, relPath)
                        copyEntryToFile(zipIn, destFile)
                        setExecutableIfBinary(destFile)
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_FLUTTER) && selectedCategories.contains(BackupCategory.FLUTTER_SDK) -> {
                    val relPath = name.removePrefix(PREFIX_FLUTTER)
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.FLUTTER_HOME, relPath)
                        copyEntryToFile(zipIn, destFile)
                        setExecutableIfBinary(destFile)
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_PUB_CACHE) && selectedCategories.contains(BackupCategory.FLUTTER_SDK) -> {
                    val relPath = name.removePrefix(PREFIX_PUB_CACHE)
                    if (relPath.isNotBlank()) {
                        val destFile = File(File(Environment.HOME, ".pub-cache"), relPath)
                        copyEntryToFile(zipIn, destFile)
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_GRADLE_CACHE) && selectedCategories.contains(BackupCategory.GRADLE_CACHE) -> {
                    val relPath = name.removePrefix(PREFIX_GRADLE_CACHE)
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.GRADLE_USER_HOME, relPath)
                        copyEntryToFile(zipIn, destFile)
                        restoredCount++
                    }
                }

                name.startsWith(PREFIX_LSP) && selectedCategories.contains(BackupCategory.LSP_SERVERS) -> {
                    val relPath = name.removePrefix(PREFIX_LSP)
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.SERVERS_DIR, relPath)
                        copyEntryToFile(zipIn, destFile)
                        setExecutableIfBinary(destFile)
                        restoredCount++
                    }
                }
            }

            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        // Post-restore symlinks for Flutter SDK
        if (selectedCategories.contains(BackupCategory.FLUTTER_SDK) && Environment.FLUTTER_HOME.exists()) {
            val flutterSdkLink = File(Environment.HOME, "flutter-sdk")
            if (!flutterSdkLink.exists()) {
                try {
                    android.system.Os.symlink(Environment.FLUTTER_HOME.absolutePath, flutterSdkLink.absolutePath)
                } catch (t: Throwable) {
                    log.warn("Failed to create flutter-sdk symlink: {}", t.message)
                }
            }
        }

        return restoredCount
    }

    private fun setExecutableIfBinary(file: File) {
        val name = file.name
        val parent = file.parentFile?.name ?: ""
        if (parent == "bin" || name == "aapt2" || name == "zipalign" || name == "clang" ||
            name == "clang++" || name == "lld" || name == "cmake" || name == "ninja" ||
            name == "flutter" || name == "dart" || file.extension in listOf("sh", "so")
        ) {
            file.setExecutable(true, false)
        }
    }

    private fun zipDirectoryRecursively(dir: File, basePrefix: String, zipOut: ZipOutputStream): Int {
        var count = 0
        val buffer = ByteArray(65536)

        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relPath = file.relativeTo(dir).path.replace('\\', '/')
                val entryName = "$basePrefix$relPath"
                zipOut.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input ->
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        zipOut.write(buffer, 0, n)
                    }
                }
                zipOut.closeEntry()
                count++
            }
        }
        return count
    }

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
        val buffer = ByteArray(8192)
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
        val buffer = ByteArray(65536)
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
