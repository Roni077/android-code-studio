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

import com.google.gson.Gson
import com.tom.rv2ide.backup.models.BackupCategory
import com.tom.rv2ide.backup.models.BackupManifest
import com.tom.rv2ide.backup.models.BackupPreset
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

class BackupSerializationTest {

    private val gson = Gson()

    @Test
    fun testBackupCategoryMapping() {
        assertEquals(BackupCategory.PREFERENCES, BackupCategory.fromId("preferences"))
        assertEquals(BackupCategory.COLOR_SCHEMES, BackupCategory.fromId("color_schemes"))
        assertEquals(BackupCategory.FONTS, BackupCategory.fromId("fonts"))
        assertEquals(BackupCategory.GRADLE_INIT, BackupCategory.fromId("gradle_init"))
        assertEquals(BackupCategory.TERMINAL_CONFIG, BackupCategory.fromId("terminal_config"))
        assertEquals(BackupCategory.TERMINAL_ENVIRONMENT, BackupCategory.fromId("terminal_environment"))
        assertEquals(BackupCategory.RECENT_PROJECTS, BackupCategory.fromId("recent_projects"))
        assertEquals(BackupCategory.ANDROID_SDK, BackupCategory.fromId("android_sdk"))
        assertEquals(BackupCategory.FLUTTER_SDK, BackupCategory.fromId("flutter_sdk"))
        assertEquals(BackupCategory.GRADLE_CACHE, BackupCategory.fromId("gradle_cache"))
        assertEquals(BackupCategory.LSP_SERVERS, BackupCategory.fromId("lsp_servers"))

        // Case insensitivity
        assertEquals(BackupCategory.ANDROID_SDK, BackupCategory.fromId("ANDROID_SDK"))
        assertNull(BackupCategory.fromId("non_existent_category"))
    }

    @Test
    fun testBackupCategoryFromRelativePath() {
        assertEquals(BackupCategory.ANDROID_SDK, BackupCategory.fromRelativePath("home/android-sdk/platforms/android-34"))
        assertEquals(BackupCategory.ANDROID_SDK, BackupCategory.fromRelativePath("./home/android-sdk/build-tools/34.0.0/aapt2"))
        assertEquals(BackupCategory.FLUTTER_SDK, BackupCategory.fromRelativePath("usr/opt/flutter/bin/flutter"))
        assertEquals(BackupCategory.FLUTTER_SDK, BackupCategory.fromRelativePath("home/.pub-cache/hosted/pub.dev/foo"))
        assertEquals(BackupCategory.PREFERENCES, BackupCategory.fromRelativePath("home/.androidide/preferences/ide_preferences.json"))
        assertEquals(BackupCategory.FONTS, BackupCategory.fromRelativePath("home/.androidide/ui/JetBrainsMono.ttf"))
        assertEquals(BackupCategory.COLOR_SCHEMES, BackupCategory.fromRelativePath("home/.androidide/editor/schemes/dracula.json"))
        assertEquals(BackupCategory.GRADLE_CACHE, BackupCategory.fromRelativePath("home/.gradle/caches/modules-2/files-2.1"))
        assertEquals(BackupCategory.TERMINAL_CONFIG, BackupCategory.fromRelativePath("home/.bashrc"))
        assertEquals(BackupCategory.TERMINAL_ENVIRONMENT, BackupCategory.fromRelativePath("usr/bin/tar"))

        // Legacy ACS ZIP paths
        assertEquals(BackupCategory.ANDROID_SDK, BackupCategory.fromRelativePath("toolchains/sdk/build-tools/34.0.0/aapt2"))
        assertEquals(BackupCategory.FLUTTER_SDK, BackupCategory.fromRelativePath("toolchains/flutter/bin/flutter"))
        assertEquals(BackupCategory.PREFERENCES, BackupCategory.fromRelativePath("preferences/ide_preferences.json"))
    }

    @Test
    fun testBackupPresets() {
        val quick = BackupPreset.QUICK_SETTINGS.getCategories()
        assertTrue(quick.contains(BackupCategory.PREFERENCES))
        assertTrue(quick.contains(BackupCategory.FONTS))
        assertFalse(quick.contains(BackupCategory.ANDROID_SDK))
        assertFalse(quick.contains(BackupCategory.FLUTTER_SDK))

        val complete = BackupPreset.COMPLETE_TOOLCHAIN.getCategories()
        assertTrue(complete.contains(BackupCategory.ANDROID_SDK))
        assertTrue(complete.contains(BackupCategory.FLUTTER_SDK))
        assertTrue(complete.contains(BackupCategory.GRADLE_CACHE))

        val termuxFull = BackupPreset.TERMUX_FULL_ENVIRONMENT.getCategories()
        assertEquals(BackupCategory.entries.size, termuxFull.size)
        assertTrue(termuxFull.contains(BackupCategory.TERMINAL_ENVIRONMENT))
    }

    @Test
    fun testBackupManifestSerialization() {
        val manifest = BackupManifest(
            version = BackupManifest.CURRENT_VERSION,
            createdAt = 1700000000000L,
            appVersion = "1.0.0",
            packageName = "com.tom.rv2ide",
            deviceInfo = "Google Pixel 8 (Android 14)",
            includedCategories = listOf("preferences", "fonts", "android_sdk", "flutter_sdk"),
            fileCount = 120
        )

        val json = gson.toJson(manifest)
        assertNotNull(json)
        assertTrue(json.contains("\"version\":2"))
        assertTrue(json.contains("\"app_version\":\"1.0.0\""))
        assertTrue(json.contains("\"package_name\":\"com.tom.rv2ide\""))

        val deserialized = gson.fromJson(json, BackupManifest::class.java)
        assertEquals(manifest.version, deserialized.version)
        assertEquals(manifest.createdAt, deserialized.createdAt)
        assertEquals(manifest.appVersion, deserialized.appVersion)
        assertEquals(manifest.packageName, deserialized.packageName)
        assertEquals(manifest.includedCategories, deserialized.includedCategories)
        assertEquals(manifest.fileCount, deserialized.fileCount)
    }

    @Test
    fun testPreferenceDumpSerializationRoundTrip() {
        val entries = mapOf(
            "editor_font_size" to PreferencesBackupHelper.TypedPrefEntry("int", value = "16"),
            "editor_word_wrap" to PreferencesBackupHelper.TypedPrefEntry("boolean", value = "true"),
            "selected_theme" to PreferencesBackupHelper.TypedPrefEntry("string", value = "darcula"),
            "custom_jvm_memory" to PreferencesBackupHelper.TypedPrefEntry("long", value = "4096"),
            "line_spacing_multiplier" to PreferencesBackupHelper.TypedPrefEntry("float", value = "1.2"),
            "recent_tags" to PreferencesBackupHelper.TypedPrefEntry("string_set", values = listOf("kotlin", "compose", "gradle"))
        )

        val originalDump = PreferencesBackupHelper.PreferenceFileDump(
            name = PreferencesBackupHelper.DEFAULT_PREFS_NAME,
            isDefault = true,
            entries = entries
        )

        val json = PreferencesBackupHelper.toJson(originalDump)
        assertNotNull(json)

        val parsedDump = PreferencesBackupHelper.fromJson(json)
        assertNotNull(parsedDump)
        assertEquals(originalDump.name, parsedDump!!.name)
        assertTrue(parsedDump.isDefault)
        assertEquals(6, parsedDump.entries.size)
        assertEquals("16", parsedDump.entries["editor_font_size"]?.value)
        assertEquals("int", parsedDump.entries["editor_font_size"]?.type)
        assertEquals(listOf("kotlin", "compose", "gradle"), parsedDump.entries["recent_tags"]?.values)
    }

    @Test
    fun testStandalonePreferencesDump() {
        val dump1 = PreferencesBackupHelper.PreferenceFileDump(
            name = PreferencesBackupHelper.DEFAULT_PREFS_NAME,
            isDefault = true,
            entries = mapOf("ui_mode" to PreferencesBackupHelper.TypedPrefEntry("int", value = "2"))
        )
        val dump2 = PreferencesBackupHelper.PreferenceFileDump(
            name = "ai_preferences",
            isDefault = false,
            entries = mapOf("model" to PreferencesBackupHelper.TypedPrefEntry("string", value = "gemini-flash"))
        )

        val standalone = PreferencesBackupHelper.StandalonePreferencesDump(
            format = "acs_preferences_dump",
            version = 1,
            dumps = listOf(dump1, dump2)
        )

        val json = gson.toJson(standalone)
        assertTrue(json.contains("\"format\":\"acs_preferences_dump\""))
        assertTrue(json.contains("\"ui_mode\""))
        assertTrue(json.contains("\"gemini-flash\""))

        val parsed = gson.fromJson(json, PreferencesBackupHelper.StandalonePreferencesDump::class.java)
        assertEquals(2, parsed.dumps.size)
        assertEquals("ai_preferences", parsed.dumps[1].name)
    }

    @Test
    fun testMagicByteDetections() {
        val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        assertTrue(BackupManager.isZipStream(zipBytes))

        val gzipBytes = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)
        assertTrue(BackupManager.isGzipStream(gzipBytes))

        val jsonBytes = "{\"format\":\"acs_preferences_dump\"}".toByteArray(StandardCharsets.UTF_8)
        assertFalse(BackupManager.isZipStream(jsonBytes))
        assertFalse(BackupManager.isGzipStream(jsonBytes))
    }

    @Test
    fun testTarGzCreationAndPOSIXStructure() {
        val baos = ByteArrayOutputStream()
        val gzipParams = GzipParameters().apply {
            compressionLevel = Deflater.BEST_SPEED
        }
        val gzipOut = GzipCompressorOutputStream(baos, gzipParams)
        val tarOut = TarArchiveOutputStream(gzipOut).apply {
            setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        }

        // Add regular file in home/
        val homeContent = "echo 'Hello Termux ACS'".toByteArray(StandardCharsets.UTF_8)
        val homeEntry = TarArchiveEntry("home/.bashrc").apply {
            size = homeContent.size.toLong()
            mode = 420 // 0644
        }
        tarOut.putArchiveEntry(homeEntry)
        tarOut.write(homeContent)
        tarOut.closeArchiveEntry()

        // Add binary in usr/bin/
        val binContent = "#!/bin/sh\necho acs".toByteArray(StandardCharsets.UTF_8)
        val binEntry = TarArchiveEntry("usr/bin/acs").apply {
            size = binContent.size.toLong()
            mode = 493 // 0755
        }
        tarOut.putArchiveEntry(binEntry)
        tarOut.write(binContent)
        tarOut.closeArchiveEntry()

        // Add symbolic link in home/
        val symlinkEntry = TarArchiveEntry("home/flutter-sdk", TarArchiveEntry.LF_SYMLINK).apply {
            linkName = "../usr/opt/flutter"
            mode = 511 // 0777
        }
        tarOut.putArchiveEntry(symlinkEntry)
        tarOut.closeArchiveEntry()

        tarOut.finish()
        gzipOut.finish()

        val archiveBytes = baos.toByteArray()
        assertTrue(archiveBytes.size > 0)
        assertTrue(BackupManager.isGzipStream(archiveBytes))

        // Read back and verify entries
        val bais = ByteArrayInputStream(archiveBytes)
        val gzipIn = GzipCompressorInputStream(bais)
        val tarIn = TarArchiveInputStream(gzipIn)

        val entryNames = mutableListOf<String>()
        var entry: TarArchiveEntry? = tarIn.nextTarEntry
        while (entry != null) {
            entryNames.add(entry.name)
            if (entry.name == "usr/bin/acs") {
                assertEquals(493, entry.mode)
            }
            if (entry.name == "home/flutter-sdk") {
                assertTrue(entry.isSymbolicLink)
                assertEquals("../usr/opt/flutter", entry.linkName)
            }
            entry = tarIn.nextTarEntry
        }

        assertTrue(entryNames.contains("home/.bashrc"))
        assertTrue(entryNames.contains("usr/bin/acs"))
        assertTrue(entryNames.contains("home/flutter-sdk"))
    }
}
