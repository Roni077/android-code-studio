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
        assertEquals(BackupCategory.RECENT_PROJECTS, BackupCategory.fromId("recent_projects"))

        // Case insensitivity
        assertEquals(BackupCategory.FONTS, BackupCategory.fromId("FONTS"))
        assertNull(BackupCategory.fromId("non_existent_category"))
    }

    @Test
    fun testBackupManifestSerialization() {
        val manifest = BackupManifest(
            version = 1,
            createdAt = 1700000000000L,
            appVersion = "1.0.0",
            packageName = "com.tom.rv2ide",
            deviceInfo = "Google Pixel 8 (Android 14)",
            includedCategories = listOf("preferences", "fonts", "color_schemes"),
            fileCount = 12
        )

        val json = gson.toJson(manifest)
        assertNotNull(json)
        assertTrue(json.contains("\"version\":1"))
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

        assertEquals("int", parsedDump.entries["editor_font_size"]?.type)
        assertEquals("16", parsedDump.entries["editor_font_size"]?.value)

        assertEquals("boolean", parsedDump.entries["editor_word_wrap"]?.type)
        assertEquals("true", parsedDump.entries["editor_word_wrap"]?.value)

        assertEquals("string_set", parsedDump.entries["recent_tags"]?.type)
        assertEquals(listOf("kotlin", "compose", "gradle"), parsedDump.entries["recent_tags"]?.values)
    }

    @Test
    fun testCorruptedArchiveInspection() {
        val randomBytes = "this is not a zip archive".toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayInputStream(randomBytes)

        // Run inspectBackup on invalid stream - should handle gracefully
        val baos = ByteArrayOutputStream()
        val zipOut = ZipOutputStream(baos)
        zipOut.putNextEntry(ZipEntry("some_other_file.txt"))
        zipOut.write("hello".toByteArray(StandardCharsets.UTF_8))
        zipOut.closeEntry()
        zipOut.close()

        val zipWithoutManifest = ByteArrayInputStream(baos.toByteArray())
        val inspection = runBlockingInspection(zipWithoutManifest)

        assertFalse(inspection.isValid)
        assertNotNull(inspection.errorMessage)
    }

    @Test
    fun testValidArchiveInspectionWithManifest() {
        val manifest = BackupManifest(
            version = 1,
            createdAt = 1700000000000L,
            appVersion = "1.0.0",
            packageName = "com.tom.rv2ide",
            deviceInfo = "TestDevice",
            includedCategories = listOf("preferences", "fonts"),
            fileCount = 3
        )

        val baos = ByteArrayOutputStream()
        val zipOut = ZipOutputStream(baos)

        // Write manifest
        zipOut.putNextEntry(ZipEntry(BackupManifest.MANIFEST_FILE_NAME))
        zipOut.write(gson.toJson(manifest).toByteArray(StandardCharsets.UTF_8))
        zipOut.closeEntry()

        // Write sample font
        zipOut.putNextEntry(ZipEntry("ui/fonts/FiraCode-Regular.ttf"))
        zipOut.write("mock font content".toByteArray(StandardCharsets.UTF_8))
        zipOut.closeEntry()

        // Write sample preference
        zipOut.putNextEntry(ZipEntry("preferences/default.json"))
        zipOut.write("{}".toByteArray(StandardCharsets.UTF_8))
        zipOut.closeEntry()

        zipOut.close()

        val zipInput = ByteArrayInputStream(baos.toByteArray())
        val inspection = runBlockingInspection(zipInput)

        assertTrue(inspection.isValid)
        assertNotNull(inspection.manifest)
        assertEquals("1.0.0", inspection.manifest?.appVersion)
        assertTrue(inspection.availableCategories.contains(BackupCategory.FONTS))
        assertTrue(inspection.availableCategories.contains(BackupCategory.PREFERENCES))
    }

    private fun runBlockingInspection(inputStream: java.io.InputStream): com.tom.rv2ide.backup.models.BackupInspection {
        return kotlinx.coroutines.runBlocking {
            BackupManager.inspectBackup(inputStream = inputStream)
        }
    }
}
