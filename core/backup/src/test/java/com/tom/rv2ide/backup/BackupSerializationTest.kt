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

import com.google.gson.Gson
import com.tom.rv2ide.backup.models.BackupCategory
import com.tom.rv2ide.backup.models.BackupManifest
import com.tom.rv2ide.backup.models.BackupPreset
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun testBackupPresets() {
        val quick = BackupPreset.QUICK_SETTINGS.getCategories()
        assertTrue(quick.contains(BackupCategory.PREFERENCES))
        assertTrue(quick.contains(BackupCategory.FONTS))
        assertFalse(quick.contains(BackupCategory.ANDROID_SDK))
        assertFalse(quick.contains(BackupCategory.FLUTTER_SDK))

        val complete = BackupPreset.COMPLETE_TOOLCHAIN.getCategories()
        assertEquals(BackupCategory.entries.size, complete.size)
        assertTrue(complete.contains(BackupCategory.ANDROID_SDK))
        assertTrue(complete.contains(BackupCategory.FLUTTER_SDK))
        assertTrue(complete.contains(BackupCategory.GRADLE_CACHE))
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
    fun testMagicByteZipDetection() {
        val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        assertTrue(BackupManager.isZipStream(zipBytes))

        val jsonBytes = "{\"format\":\"acs_preferences_dump\"}".toByteArray(StandardCharsets.UTF_8)
        assertFalse(BackupManager.isZipStream(jsonBytes))

        val shortBytes = byteArrayOf(0x50, 0x4B)
        assertFalse(BackupManager.isZipStream(shortBytes))
    }
}
