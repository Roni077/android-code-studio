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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.backup

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tom.rv2ide.backup.models.ArchiveFormat
import com.tom.rv2ide.backup.models.BackupCategory
import com.tom.rv2ide.backup.models.BackupInspection
import com.tom.rv2ide.backup.models.BackupManifest
import com.tom.rv2ide.buildinfo.BuildInfo
import com.tom.rv2ide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * High-performance Backup and Restore manager conforming to the official Termux standard
 * (https://wiki.termux.com/wiki/Backing_up_Termux) using POSIX tar.gz format with `./home`
 * and `./usr` filesystem hierarchy, while maintaining full backward compatibility with
 * ACS ZIP archives and standalone JSON exports.
 */
object BackupManager {

    private val log = LoggerFactory.getLogger(BackupManager::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private const val BUFFER_SIZE = 65536

    // POSIX mode bit constants
    private const val MODE_FILE = 420 // 0644
    private const val MODE_DIR = 493 // 0755
    private const val MODE_EXEC = 493 // 0755
    private const val MODE_SYMLINK = 511 // 0777
    private const val MASK_EXEC = 73 // 0111
    private const val MASK_WRITE = 146 // 0222

    /**
     * Inspects magic bytes to detect whether the stream represents a GZIP archive.
     */
    fun isGzipStream(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
                bytes[0] == 0x1F.toByte() &&
                bytes[1] == 0x8B.toByte()
    }

    /**
     * Inspects magic bytes to detect whether the stream represents a ZIP archive.
     */
    fun isZipStream(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() && // 'P'
                bytes[1] == 0x4B.toByte() && // 'K'
                (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
    }

    /**
     * Creates a standard Termux-compatible (.tar.gz) backup archive containing data for the requested categories.
     * Uses POSIX Tar + GZIP with Deflater.BEST_SPEED to achieve high throughput on mobile processors.
     */
    suspend fun createBackup(
        context: Context,
        outputStream: OutputStream,
        categories: Set<BackupCategory>
    ): Result<BackupManifest> = withContext(Dispatchers.IO) {
        try {
            ensureTerminalScriptsInstalled()

            var fileCount = 0
            val gzipParams = GzipParameters().apply {
                compressionLevel = Deflater.BEST_SPEED
            }

            val gzipOut = GzipCompressorOutputStream(BufferedOutputStream(outputStream, BUFFER_SIZE), gzipParams)
            val tarOut = TarArchiveOutputStream(gzipOut).apply {
                setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            }

            val includedCategoryIds = categories.map { it.id }

            // 1. Preferences & Recent Projects (stored in home/.androidide/preferences/)
            if (categories.contains(BackupCategory.PREFERENCES) || categories.contains(BackupCategory.RECENT_PROJECTS)) {
                val includeRecent = categories.contains(BackupCategory.RECENT_PROJECTS)
                val prefDumps = PreferencesBackupHelper.exportAllPreferences(context, includeRecent)

                for (dump in prefDumps) {
                    val json = PreferencesBackupHelper.toJson(dump)
                    val entryName = "home/.androidide/preferences/${dump.name}.json"
                    val bytes = json.toByteArray(StandardCharsets.UTF_8)
                    val entry = TarArchiveEntry(entryName).apply {
                        size = bytes.size.toLong()
                        mode = MODE_FILE
                        modTime = java.util.Date()
                    }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(bytes)
                    tarOut.closeArchiveEntry()
                    fileCount++
                }
            }

            // 2. Custom Fonts (home/.androidide/ui/)
            if (categories.contains(BackupCategory.FONTS)) {
                val fontDir = Environment.ANDROIDIDE_UI
                if (fontDir.exists() && fontDir.isDirectory) {
                    val fontFiles = fontDir.listFiles { file ->
                        file.isFile && (file.extension.equals("ttf", ignoreCase = true) ||
                                file.extension.equals("otf", ignoreCase = true))
                    }
                    fontFiles?.forEach { fontFile ->
                        fileCount += tarFileOrDirectory(fontFile, "home/.androidide/ui/${fontFile.name}", tarOut)
                    }
                }
            }

            // 3. Custom Color Schemes (home/.androidide/editor/schemes/)
            if (categories.contains(BackupCategory.COLOR_SCHEMES)) {
                val schemesDir = File(Environment.ANDROIDIDE_UI, "editor/schemes")
                if (schemesDir.exists() && schemesDir.isDirectory) {
                    schemesDir.listFiles { file -> file.isFile }?.forEach { schemeFile ->
                        fileCount += tarFileOrDirectory(schemeFile, "home/.androidide/editor/schemes/${schemeFile.name}", tarOut)
                    }
                }
            }

            // 4. Gradle Init Script & init.d (home/.gradle/init.gradle)
            if (categories.contains(BackupCategory.GRADLE_INIT)) {
                val initScript = Environment.INIT_SCRIPT
                if (initScript.exists() && initScript.isFile) {
                    fileCount += tarFileOrDirectory(initScript, "home/.gradle/${initScript.name}", tarOut)
                }
                val gradleHome = Environment.GRADLE_USER_HOME
                val initD = File(gradleHome, "init.d")
                if (initD.exists() && initD.isDirectory) {
                    fileCount += tarDirectoryRecursively(initD, "home/.gradle/init.d/", tarOut)
                }
            }

            // 5. Terminal Configurations (home/.bashrc, home/.profile, home/.gitconfig, home/.termux/)
            if (categories.contains(BackupCategory.TERMINAL_CONFIG)) {
                val homeDir = Environment.HOME
                val shellConfigs = listOf(".bashrc", ".profile", ".gitconfig", ".inputrc")
                for (configName in shellConfigs) {
                    val configFile = File(homeDir, configName)
                    if (configFile.exists() && configFile.isFile) {
                        fileCount += tarFileOrDirectory(configFile, "home/$configName", tarOut)
                    }
                }

                val termuxDir = File(homeDir, ".termux")
                if (termuxDir.exists() && termuxDir.isDirectory) {
                    fileCount += tarDirectoryRecursively(termuxDir, "home/.termux/", tarOut)
                }
            }

            // 6. Terminal Environment (usr/ and home/bin, home/.config, home/.local)
            if (categories.contains(BackupCategory.TERMINAL_ENVIRONMENT)) {
                val prefixDir = Environment.PREFIX
                if (prefixDir.exists() && prefixDir.isDirectory) {
                    log.info("Archiving full PRoot prefix (usr/) from: {}", prefixDir.absolutePath)
                    fileCount += tarDirectoryRecursively(prefixDir, "usr/", tarOut)
                }

                val homeDir = Environment.HOME
                val userDirs = listOf("bin", ".config", ".local")
                for (subName in userDirs) {
                    val dir = File(homeDir, subName)
                    if (dir.exists() && dir.isDirectory) {
                        fileCount += tarDirectoryRecursively(dir, "home/$subName/", tarOut)
                    }
                }
            }

            // 7. Android SDK & Tooling (home/android-sdk/)
            if (categories.contains(BackupCategory.ANDROID_SDK)) {
                val sdkDir = Environment.ANDROID_HOME
                if (sdkDir.exists() && sdkDir.isDirectory) {
                    log.info("Archiving Android SDK from: {}", sdkDir.absolutePath)
                    fileCount += tarDirectoryRecursively(sdkDir, "home/android-sdk/", tarOut)
                }
            }

            // 8. Flutter & Dart SDK (usr/opt/flutter/ & home/.pub-cache/)
            if (categories.contains(BackupCategory.FLUTTER_SDK)) {
                val flutterHome = Environment.FLUTTER_HOME
                if (flutterHome.exists() && flutterHome.isDirectory) {
                    log.info("Archiving Flutter SDK from: {}", flutterHome.absolutePath)
                    fileCount += tarDirectoryRecursively(flutterHome, "usr/opt/flutter/", tarOut)
                }

                val pubCache = File(Environment.HOME, ".pub-cache")
                if (pubCache.exists() && pubCache.isDirectory) {
                    fileCount += tarDirectoryRecursively(pubCache, "home/.pub-cache/", tarOut)
                }
            }

            // 9. Gradle Caches & Distributions (home/.gradle/caches/ & home/.gradle/wrapper/)
            if (categories.contains(BackupCategory.GRADLE_CACHE)) {
                val gradleHome = Environment.GRADLE_USER_HOME
                val cachesDir = File(gradleHome, "caches")
                if (cachesDir.exists() && cachesDir.isDirectory) {
                    fileCount += tarDirectoryRecursively(cachesDir, "home/.gradle/caches/", tarOut)
                }
                val wrapperDir = File(gradleHome, "wrapper")
                if (wrapperDir.exists() && wrapperDir.isDirectory) {
                    fileCount += tarDirectoryRecursively(wrapperDir, "home/.gradle/wrapper/", tarOut)
                }
            }

            // 10. Language Servers (home/.androidide/lsp/)
            if (categories.contains(BackupCategory.LSP_SERVERS)) {
                val serversDir = Environment.SERVERS_DIR
                if (serversDir.exists() && serversDir.isDirectory) {
                    fileCount += tarDirectoryRecursively(serversDir, "home/.androidide/lsp/", tarOut)
                }
            }

            // Write Manifest to home/.androidide/backup_manifest.json
            val manifest = BackupManifest(
                version = BackupManifest.CURRENT_VERSION,
                createdAt = System.currentTimeMillis(),
                appVersion = BuildInfo.VERSION_NAME,
                packageName = context.packageName,
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                includedCategories = includedCategoryIds,
                fileCount = fileCount
            )

            val manifestBytes = gson.toJson(manifest).toByteArray(StandardCharsets.UTF_8)
            val manifestEntry = TarArchiveEntry("home/.androidide/${BackupManifest.MANIFEST_FILE_NAME}").apply {
                size = manifestBytes.size.toLong()
                mode = MODE_FILE
                modTime = java.util.Date()
            }
            tarOut.putArchiveEntry(manifestEntry)
            tarOut.write(manifestBytes)
            tarOut.closeArchiveEntry()

            tarOut.finish()
            gzipOut.finish()

            log.info("Termux-standard backup successfully created with {} files", fileCount)
            Result.success(manifest)
        } catch (e: Exception) {
            log.error("Failed to create backup", e)
            Result.failure(e)
        }
    }

    /**
     * Inspects a backup stream and identifies whether it is a Termux tar.gz, legacy ZIP, or JSON.
     */
    suspend fun inspectBackup(
        inputStream: InputStream
    ): BackupInspection = withContext(Dispatchers.IO) {
        val bufferedIn = BufferedInputStream(inputStream, BUFFER_SIZE)
        bufferedIn.mark(1024)
        val header = ByteArray(4)
        val readBytes = bufferedIn.read(header)
        bufferedIn.reset()

        when {
            readBytes >= 2 && isGzipStream(header) -> inspectTarGzArchive(bufferedIn)
            readBytes >= 4 && isZipStream(header) -> inspectZipArchive(bufferedIn)
            else -> inspectJsonFile(bufferedIn)
        }
    }

    private fun inspectTarGzArchive(inputStream: InputStream): BackupInspection {
        try {
            val gzipIn = GzipCompressorInputStream(inputStream)
            val tarIn = TarArchiveInputStream(gzipIn)
            var manifest: BackupManifest? = null
            val detectedCategories = mutableSetOf<BackupCategory>()
            var isTermuxTarball = false
            var totalBytes = 0L

            var entry: TarArchiveEntry? = tarIn.nextTarEntry
            while (entry != null) {
                val name = entry.name
                totalBytes += entry.size

                if (name.startsWith("home/") || name.startsWith("usr/") ||
                    name.startsWith("./home/") || name.startsWith("./usr/")) {
                    isTermuxTarball = true
                }

                if (name.endsWith(BackupManifest.MANIFEST_FILE_NAME)) {
                    val content = readTarEntryText(tarIn, entry.size.toInt().coerceAtLeast(0))
                    manifest = gson.fromJson(content, BackupManifest::class.java)
                } else {
                    BackupCategory.fromRelativePath(name)?.let {
                        detectedCategories.add(it)
                    }
                }

                entry = tarIn.nextTarEntry
            }

            if (manifest != null) {
                manifest.includedCategories.forEach { id ->
                    BackupCategory.fromId(id)?.let { detectedCategories.add(it) }
                }
                return BackupInspection(
                    isValid = true,
                    manifest = manifest,
                    format = ArchiveFormat.TAR_GZ,
                    isTermuxTarball = isTermuxTarball,
                    totalUncompressedBytes = totalBytes,
                    availableCategories = detectedCategories
                )
            } else {
                return BackupInspection(
                    isValid = detectedCategories.isNotEmpty() || isTermuxTarball,
                    format = ArchiveFormat.TAR_GZ,
                    isTermuxTarball = isTermuxTarball,
                    totalUncompressedBytes = totalBytes,
                    availableCategories = if (detectedCategories.isNotEmpty()) detectedCategories else BackupCategory.entries.toSet(),
                    errorMessage = if (detectedCategories.isEmpty() && !isTermuxTarball) "Empty or unrecognized tar.gz archive" else null
                )
            }
        } catch (e: Exception) {
            log.error("Failed to inspect tar.gz backup archive", e)
            return BackupInspection(isValid = false, format = ArchiveFormat.TAR_GZ, errorMessage = e.message ?: "Failed to read tar.gz archive")
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
                if (name == BackupManifest.MANIFEST_FILE_NAME) {
                    val content = readZipEntryText(zipIn)
                    manifest = gson.fromJson(content, BackupManifest::class.java)
                } else {
                    BackupCategory.fromRelativePath(name)?.let {
                        detectedCategories.add(it)
                    }
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
                    format = ArchiveFormat.ZIP,
                    availableCategories = detectedCategories
                )
            } else {
                return BackupInspection(
                    isValid = detectedCategories.isNotEmpty(),
                    format = ArchiveFormat.ZIP,
                    availableCategories = detectedCategories,
                    errorMessage = if (detectedCategories.isEmpty()) "Manifest not found in zip archive" else null
                )
            }
        } catch (e: Exception) {
            log.error("Failed to inspect zip backup archive", e)
            return BackupInspection(isValid = false, format = ArchiveFormat.ZIP, errorMessage = e.message ?: "Failed to read zip archive")
        }
    }

    private fun inspectJsonFile(inputStream: InputStream): BackupInspection {
        return try {
            val content = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (content.isNotBlank() && (content.trim().startsWith("{") || content.trim().startsWith("["))) {
                BackupInspection(
                    isValid = true,
                    format = ArchiveFormat.JSON,
                    isStandaloneJson = true,
                    availableCategories = setOf(BackupCategory.PREFERENCES)
                )
            } else {
                BackupInspection(isValid = false, errorMessage = "Selected file is neither a valid backup tar.gz, zip, nor json")
            }
        } catch (e: Exception) {
            BackupInspection(isValid = false, errorMessage = "Failed to parse JSON file: ${e.message}")
        }
    }

    /**
     * Restores selected categories from a backup stream.
     * Auto-detects whether input is a Termux tar.gz, legacy ZIP, or standalone JSON.
     */
    suspend fun restoreBackup(
        context: Context,
        inputStream: InputStream,
        selectedCategories: Set<BackupCategory>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val snapshotDir = File(context.cacheDir, "pre_restore_snapshot_${System.currentTimeMillis()}")
        try {
            ensureTerminalScriptsInstalled()
            createPreRestoreSnapshot(context, snapshotDir)

            val bufferedIn = BufferedInputStream(inputStream, BUFFER_SIZE)
            bufferedIn.mark(1024)
            val header = ByteArray(4)
            val readBytes = bufferedIn.read(header)
            bufferedIn.reset()

            val restoredCount: Int = when {
                readBytes >= 2 && isGzipStream(header) -> {
                    restoreTarGzArchive(context, bufferedIn, selectedCategories)
                }
                readBytes >= 4 && isZipStream(header) -> {
                    restoreZipArchive(context, bufferedIn, selectedCategories)
                }
                else -> {
                    val json = bufferedIn.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    if (PreferencesBackupHelper.importAllPreferencesFromJson(context, json)) {
                        1
                    } else {
                        throw IOException("Failed to import preferences from JSON")
                    }
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

    private fun restoreTarGzArchive(
        context: Context,
        inputStream: InputStream,
        selectedCategories: Set<BackupCategory>
    ): Int {
        val gzipIn = GzipCompressorInputStream(inputStream)
        val tarIn = TarArchiveInputStream(gzipIn)
        var restoredCount = 0

        var entry: TarArchiveEntry? = tarIn.nextTarEntry
        while (entry != null) {
            val name = entry.name.removePrefix("./").removePrefix("/")

            val category = BackupCategory.fromRelativePath(name)
            // If category is null but path is under home/ or usr/, check if complete environment is selected
            val shouldExtract = (category != null && selectedCategories.contains(category)) ||
                    (category == null && (selectedCategories.contains(BackupCategory.TERMINAL_ENVIRONMENT) ||
                            selectedCategories.contains(BackupCategory.ANDROID_SDK)))

            if (shouldExtract) {
                // Determine destination file
                val destFile = resolveDestinationFile(name)
                if (destFile != null) {
                    if (entry.isSymbolicLink) {
                        createSymbolicLink(entry.linkName, destFile)
                        restoredCount++
                    } else if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        // Special handling for preferences json dumps
                        if (name.contains(".androidide/preferences/") && name.endsWith(".json") &&
                            selectedCategories.contains(BackupCategory.PREFERENCES)) {
                            val json = readTarEntryText(tarIn, entry.size.toInt().coerceAtLeast(0))
                            val dump = PreferencesBackupHelper.fromJson(json)
                            if (dump != null) {
                                PreferencesBackupHelper.restorePreferences(context, dump, selectedCategories)
                            }
                        }

                        // Extract file to filesystem
                        copyTarEntryToFile(tarIn, destFile)

                        // Preserve POSIX permissions
                        val mode = entry.mode
                        val isExecutable = (mode and MASK_EXEC) != 0 || isBinaryFile(destFile)
                        destFile.setExecutable(isExecutable, false)
                        destFile.setReadable(true, false)
                        destFile.setWritable((mode and MASK_WRITE) != 0 || true, false)

                        restoredCount++
                    }
                }
            }

            entry = tarIn.nextTarEntry
        }

        // Recreate Flutter symlink if needed
        ensureFlutterSymlink(selectedCategories)

        return restoredCount
    }

    private fun resolveDestinationFile(cleanName: String): File? {
        return when {
            cleanName.startsWith("home/") -> {
                val relPath = cleanName.removePrefix("home/")
                File(Environment.HOME, relPath)
            }
            cleanName.startsWith("usr/") -> {
                val relPath = cleanName.removePrefix("usr/")
                File(Environment.PREFIX, relPath)
            }
            else -> null
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
                name.startsWith("preferences/") &&
                        (selectedCategories.contains(BackupCategory.PREFERENCES) ||
                                selectedCategories.contains(BackupCategory.RECENT_PROJECTS)) -> {
                    val json = readZipEntryText(zipIn)
                    val dump = PreferencesBackupHelper.fromJson(json)
                    if (dump != null && PreferencesBackupHelper.restorePreferences(context, dump, selectedCategories)) {
                        restoredCount++
                    }
                }

                name.startsWith("ui/fonts/") && selectedCategories.contains(BackupCategory.FONTS) -> {
                    val fileName = name.removePrefix("ui/fonts/")
                    if (fileName.isNotBlank()) {
                        copyZipEntryToFile(zipIn, File(Environment.ANDROIDIDE_UI, fileName))
                        restoredCount++
                    }
                }

                name.startsWith("ui/schemes/") && selectedCategories.contains(BackupCategory.COLOR_SCHEMES) -> {
                    val fileName = name.removePrefix("ui/schemes/")
                    if (fileName.isNotBlank()) {
                        val schemesDir = File(Environment.ANDROIDIDE_UI, "editor/schemes")
                        copyZipEntryToFile(zipIn, File(schemesDir, fileName))
                        restoredCount++
                    }
                }

                name.startsWith("init/") && selectedCategories.contains(BackupCategory.GRADLE_INIT) -> {
                    copyZipEntryToFile(zipIn, Environment.INIT_SCRIPT)
                    restoredCount++
                }

                name.startsWith("terminal/") && selectedCategories.contains(BackupCategory.TERMINAL_CONFIG) -> {
                    val relPath = name.removePrefix("terminal/")
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.HOME, relPath)
                        copyZipEntryToFile(zipIn, destFile)
                        restoredCount++
                    }
                }

                name.startsWith("terminal/env/") && selectedCategories.contains(BackupCategory.TERMINAL_ENVIRONMENT) -> {
                    val relPath = name.removePrefix("terminal/env/")
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.HOME, relPath)
                        copyZipEntryToFile(zipIn, destFile)
                        if (destFile.parentFile?.name == "bin" || destFile.extension == "sh") {
                            destFile.setExecutable(true, false)
                        }
                        restoredCount++
                    }
                }

                name.startsWith("toolchains/sdk/") && selectedCategories.contains(BackupCategory.ANDROID_SDK) -> {
                    val relPath = name.removePrefix("toolchains/sdk/")
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.ANDROID_HOME, relPath)
                        copyZipEntryToFile(zipIn, destFile)
                        setExecutableIfBinary(destFile)
                        restoredCount++
                    }
                }

                name.startsWith("toolchains/flutter/") && selectedCategories.contains(BackupCategory.FLUTTER_SDK) -> {
                    val relPath = name.removePrefix("toolchains/flutter/")
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.FLUTTER_HOME, relPath)
                        copyZipEntryToFile(zipIn, destFile)
                        setExecutableIfBinary(destFile)
                        restoredCount++
                    }
                }

                name.startsWith("toolchains/pub-cache/") && selectedCategories.contains(BackupCategory.FLUTTER_SDK) -> {
                    val relPath = name.removePrefix("toolchains/pub-cache/")
                    if (relPath.isNotBlank()) {
                        val destFile = File(File(Environment.HOME, ".pub-cache"), relPath)
                        copyZipEntryToFile(zipIn, destFile)
                        restoredCount++
                    }
                }

                name.startsWith("toolchains/gradle/") && selectedCategories.contains(BackupCategory.GRADLE_CACHE) -> {
                    val relPath = name.removePrefix("toolchains/gradle/")
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.GRADLE_USER_HOME, relPath)
                        copyZipEntryToFile(zipIn, destFile)
                        restoredCount++
                    }
                }

                name.startsWith("toolchains/lsp/") && selectedCategories.contains(BackupCategory.LSP_SERVERS) -> {
                    val relPath = name.removePrefix("toolchains/lsp/")
                    if (relPath.isNotBlank()) {
                        val destFile = File(Environment.SERVERS_DIR, relPath)
                        copyZipEntryToFile(zipIn, destFile)
                        setExecutableIfBinary(destFile)
                        restoredCount++
                    }
                }
            }

            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }

        ensureFlutterSymlink(selectedCategories)
        return restoredCount
    }

    private fun ensureFlutterSymlink(selectedCategories: Set<BackupCategory>) {
        if (selectedCategories.contains(BackupCategory.FLUTTER_SDK) && Environment.FLUTTER_HOME.exists()) {
            val flutterSdkLink = File(Environment.HOME, "flutter-sdk")
            if (!flutterSdkLink.exists()) {
                createSymbolicLink(Environment.FLUTTER_HOME.absolutePath, flutterSdkLink)
            }
        }
    }

    private fun tarFileOrDirectory(file: File, entryPath: String, tarOut: TarArchiveOutputStream): Int {
        if (!file.exists()) return 0
        return if (file.isDirectory) {
            tarDirectoryRecursively(file, if (entryPath.endsWith("/")) entryPath else "$entryPath/", tarOut)
        } else {
            tarSingleFile(file, entryPath, tarOut)
            1
        }
    }

    private fun tarSingleFile(file: File, entryPath: String, tarOut: TarArchiveOutputStream) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            val target = Files.readSymbolicLink(path).toString()
            val entry = TarArchiveEntry(entryPath, TarArchiveEntry.LF_SYMLINK).apply {
                linkName = target
                mode = MODE_SYMLINK
                modTime = java.util.Date(file.lastModified())
            }
            tarOut.putArchiveEntry(entry)
            tarOut.closeArchiveEntry()
            return
        }

        // Skip sockets, fifos, and non-regular files
        if (!file.isFile) return

        val entry = TarArchiveEntry(file, entryPath).apply {
            size = file.length()
            mode = getPosixMode(file)
            modTime = java.util.Date(file.lastModified())
        }
        tarOut.putArchiveEntry(entry)
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                tarOut.write(buffer, 0, n)
            }
        }
        tarOut.closeArchiveEntry()
    }

    private fun tarDirectoryRecursively(dir: File, basePrefix: String, tarOut: TarArchiveOutputStream): Int {
        var count = 0
        val buffer = ByteArray(BUFFER_SIZE)

        dir.walkTopDown()
            .onFail { file, ioException ->
                // Ignore transient sockets or unreadable files (matching Termux wiki)
                log.warn("Skipping inaccessible file in backup: {} ({})", file.path, ioException.message)
            }
            .forEach { file ->
                val path = file.toPath()
                val isLink = Files.isSymbolicLink(path)

                // Skip socket/fifo/block/char devices
                if (!isLink && !file.isFile && !file.isDirectory) return@forEach

                val relPath = file.relativeTo(dir).path.replace('\\', '/')
                if (relPath.isBlank()) return@forEach // root directory itself

                val entryName = "$basePrefix$relPath"

                try {
                    if (isLink) {
                        val target = Files.readSymbolicLink(path).toString()
                        val entry = TarArchiveEntry(entryName, TarArchiveEntry.LF_SYMLINK).apply {
                            linkName = target
                            mode = MODE_SYMLINK
                            modTime = java.util.Date(file.lastModified())
                        }
                        tarOut.putArchiveEntry(entry)
                        tarOut.closeArchiveEntry()
                        count++
                    } else if (file.isDirectory) {
                        val dirEntry = TarArchiveEntry(file, "$entryName/").apply {
                            mode = MODE_DIR
                        }
                        tarOut.putArchiveEntry(dirEntry)
                        tarOut.closeArchiveEntry()
                    } else {
                        val entry = TarArchiveEntry(file, entryName).apply {
                            size = file.length()
                            mode = getPosixMode(file)
                            modTime = java.util.Date(file.lastModified())
                        }
                        tarOut.putArchiveEntry(entry)
                        file.inputStream().use { input ->
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                tarOut.write(buffer, 0, n)
                            }
                        }
                        tarOut.closeArchiveEntry()
                        count++
                    }
                } catch (t: Throwable) {
                    // Ignore socket or transient file errors
                    log.warn("Skipping file {}: {}", entryName, t.message)
                }
            }
        return count
    }

    private fun getPosixMode(file: File): Int {
        return try {
            val modeAttr = Files.getAttribute(file.toPath(), "unix:mode") as? Int
            modeAttr ?: (if (file.canExecute() || isBinaryFile(file)) MODE_EXEC else MODE_FILE)
        } catch (_: Throwable) {
            if (file.canExecute() || isBinaryFile(file)) MODE_EXEC else MODE_FILE
        }
    }

    private fun isBinaryFile(file: File): Boolean {
        val name = file.name
        val parent = file.parentFile?.name ?: ""
        return parent == "bin" || name == "aapt2" || name == "zipalign" || name == "clang" ||
                name == "clang++" || name == "lld" || name == "cmake" || name == "ninja" ||
                name == "flutter" || name == "dart" || file.extension in listOf("sh", "so")
    }

    private fun setExecutableIfBinary(file: File) {
        if (isBinaryFile(file)) {
            file.setExecutable(true, false)
        }
    }

    private fun createSymbolicLink(target: String, linkFile: File) {
        // Semantics of --recursive-unlink: remove conflicting destination first
        if (linkFile.exists() || Files.isSymbolicLink(linkFile.toPath())) {
            linkFile.delete()
        }
        linkFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.system.Os.symlink(target, linkFile.absolutePath)
            } else {
                Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(target))
            }
        } catch (_: Throwable) {
            try {
                Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(target))
            } catch (t: Throwable) {
                log.warn("Failed to create symlink from {} to {}: {}", linkFile.path, target, t.message)
            }
        }
    }

    private fun readTarEntryText(tarIn: TarArchiveInputStream, expectedSize: Int): String {
        val baos = ByteArrayOutputStream(expectedSize.coerceAtLeast(1024))
        val buffer = ByteArray(8192)
        var count: Int
        while (tarIn.read(buffer).also { count = it } != -1) {
            baos.write(buffer, 0, count)
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    private fun readZipEntryText(zipIn: ZipInputStream): String {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count: Int
        while (zipIn.read(buffer).also { count = it } != -1) {
            baos.write(buffer, 0, count)
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    private fun copyTarEntryToFile(tarIn: TarArchiveInputStream, destFile: File) {
        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val buffer = ByteArray(BUFFER_SIZE)
        var count: Int
        destFile.outputStream().use { out ->
            while (tarIn.read(buffer).also { count = it } != -1) {
                out.write(buffer, 0, count)
            }
        }
    }

    private fun copyZipEntryToFile(zipIn: ZipInputStream, destFile: File) {
        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val buffer = ByteArray(BUFFER_SIZE)
        var count: Int
        destFile.outputStream().use { out ->
            while (zipIn.read(buffer).also { count = it } != -1) {
                out.write(buffer, 0, count)
            }
        }
    }

    /**
     * Deploys `acs-backup` and `acs-restore` bash scripts to $PREFIX/bin so users
     * can run terminal-based backups just like Termux.
     */
    fun ensureTerminalScriptsInstalled() {
        try {
            val binDir = Environment.BIN_DIR
            if (!binDir.exists() || !binDir.isDirectory) return

            val backupScript = File(binDir, "acs-backup")
            val restoreScript = File(binDir, "acs-restore")

            if (!backupScript.exists()) {
                val scriptContent = """
                    #!/data/data/com.tom.rv2ide/files/usr/bin/bash
                    # AndroidCodeStudio Terminal Backup Utility (Termux Wiki format)
                    set -e

                    DEST="${'$'}{1:-/sdcard/acs-backup-${'$'}(date +%Y-%m-%d).tar.gz}"
                    echo "[ACS] Starting backup to: ${'$'}DEST"

                    if [ ! -d "/sdcard" ]; then
                        echo "[ACS] Error: /sdcard not accessible. Please ensure storage permission is granted." >&2
                        exit 1
                    fi

                    ROOT_DIR="/data/data/com.tom.rv2ide/files"
                    if [ ! -d "${'$'}ROOT_DIR" ]; then
                        ROOT_DIR="${'$'}(dirname "${'$'}PREFIX")"
                    fi

                    cd "${'$'}ROOT_DIR"
                    tar -zcf "${'$'}DEST" ./home ./usr
                    echo "[ACS] Backup successfully created: ${'$'}DEST"
                """.trimIndent()
                backupScript.writeText(scriptContent, StandardCharsets.UTF_8)
                backupScript.setExecutable(true, false)
            }

            if (!restoreScript.exists()) {
                val scriptContent = """
                    #!/data/data/com.tom.rv2ide/files/usr/bin/bash
                    # AndroidCodeStudio Terminal Restore Utility (Termux Wiki format)
                    set -e

                    if [ -z "${'$'}1" ]; then
                        echo "Usage: acs-restore /path/to/backup.tar.gz" >&2
                        exit 1
                    fi

                    ARCHIVE="${'$'}1"
                    if [ ! -f "${'$'}ARCHIVE" ]; then
                        echo "[ACS] Error: Backup file not found: ${'$'}ARCHIVE" >&2
                        exit 1
                    fi

                    ROOT_DIR="/data/data/com.tom.rv2ide/files"
                    if [ ! -d "${'$'}ROOT_DIR" ]; then
                        ROOT_DIR="${'$'}(dirname "${'$'}PREFIX")"
                    fi

                    echo "[ACS] Restoring from: ${'$'}ARCHIVE"
                    tar -zxf "${'$'}ARCHIVE" -C "${'$'}ROOT_DIR" --recursive-unlink --preserve-permissions
                    echo "[ACS] Restore completed! Please restart AndroidCodeStudio."
                """.trimIndent()
                restoreScript.writeText(scriptContent, StandardCharsets.UTF_8)
                restoreScript.setExecutable(true, false)
            }
        } catch (t: Throwable) {
            log.warn("Failed to install terminal backup scripts: {}", t.message)
        }
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
