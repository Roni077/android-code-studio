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
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.tom.rv2ide.backup.models.BackupCategory
import org.slf4j.LoggerFactory
import java.io.File

object PreferencesBackupHelper {

    private val log = LoggerFactory.getLogger(PreferencesBackupHelper::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "string_set"

    const val DEFAULT_PREFS_NAME = "__default_preferences__"
    private const val ATC_WIZARD_PREFS = "atc_wizard_prefs"
    private const val EXCLUDED_PREFS_GIT_CREDENTIALS = "git_credentials"

    data class TypedPrefEntry(
        @SerializedName("type") val type: String,
        @SerializedName("value") val value: String? = null,
        @SerializedName("values") val values: List<String>? = null
    )

    data class PreferenceFileDump(
        @SerializedName("name") val name: String,
        @SerializedName("is_default") val isDefault: Boolean,
        @SerializedName("entries") val entries: Map<String, TypedPrefEntry>
    )

    data class StandalonePreferencesDump(
        @SerializedName("format") val format: String = "acs_preferences_dump",
        @SerializedName("version") val version: Int = 1,
        @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis(),
        @SerializedName("dumps") val dumps: List<PreferenceFileDump>
    )

    /**
     * Discovers and exports all candidate SharedPreferences files into structured objects.
     */
    fun exportAllPreferences(
        context: Context,
        includeRecentProjects: Boolean
    ): List<PreferenceFileDump> {
        val dumps = mutableListOf<PreferenceFileDump>()

        // 1. Export default preferences
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        dumps.add(dumpPreferences(DEFAULT_PREFS_NAME, defaultPrefs, isDefault = true))

        // 2. Discover named SharedPreferences
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val knownNames = mutableSetOf(DEFAULT_PREFS_NAME)

        // Known named prefs in ACS
        val standardNamedPrefs = listOf(
            "git_config",
            ATC_WIZARD_PREFS,
            "ai_permissions",
            "ai_preferences",
            "memory_opt_config"
        )

        val namesToExport = mutableSetOf<String>()
        namesToExport.addAll(standardNamedPrefs)

        if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
            sharedPrefsDir.listFiles { file -> file.extension == "xml" }?.forEach { file ->
                val name = file.nameWithoutExtension
                if (!name.endsWith("_preferences") && name != EXCLUDED_PREFS_GIT_CREDENTIALS) {
                    namesToExport.add(name)
                }
            }
        }

        for (name in namesToExport) {
            if (name == EXCLUDED_PREFS_GIT_CREDENTIALS || knownNames.contains(name)) {
                continue
            }
            knownNames.add(name)
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            var dump = dumpPreferences(name, prefs, isDefault = false)

            // Filter out recent projects if not selected
            if (name == ATC_WIZARD_PREFS && !includeRecentProjects) {
                val filtered = dump.entries.filterKeys { it != "recent_projects" }
                dump = dump.copy(entries = filtered)
            }

            dumps.add(dump)
        }

        return dumps
    }

    /**
     * Serializes all preferences into a single formatted standalone JSON container.
     */
    fun exportAllPreferencesAsJson(
        context: Context,
        includeRecentProjects: Boolean = true
    ): String {
        val dumps = exportAllPreferences(context, includeRecentProjects)
        val standalone = StandalonePreferencesDump(
            format = "acs_preferences_dump",
            version = 1,
            createdAt = System.currentTimeMillis(),
            dumps = dumps
        )
        return gson.toJson(standalone)
    }

    /**
     * Restores preferences from a standalone JSON string.
     * Supports both native ACS StandalonePreferencesDump and flat key-value maps (e.g. from Xed-Editor).
     */
    fun importAllPreferencesFromJson(context: Context, json: String): Boolean {
        return try {
            if (json.contains("\"format\"") && json.contains("\"acs_preferences_dump\"")) {
                val standalone = gson.fromJson(json, StandalonePreferencesDump::class.java)
                var success = true
                for (dump in standalone.dumps) {
                    if (!restorePreferences(context, dump, BackupCategory.entries.toSet())) {
                        success = false
                    }
                }
                success
            } else {
                // Fallback: flat key-value map (e.g. from Xed-Editor / custom export)
                val type = object : TypeToken<Map<String, Any?>>() {}.type
                val map: Map<String, Any?> = gson.fromJson(json, type)
                val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
                val editor = defaultPrefs.edit()

                for ((key, value) in map) {
                    if (value == null) continue
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Number -> {
                            // If integer representation equals double, store as int/long
                            val d = value.toDouble()
                            if (d == d.toLong().toDouble()) {
                                if (d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) {
                                    editor.putInt(key, value.toInt())
                                } else {
                                    editor.putLong(key, value.toLong())
                                }
                            } else {
                                editor.putFloat(key, value.toFloat())
                            }
                        }
                        is String -> editor.putString(key, value)
                        is List<*> -> {
                            val strSet = value.filterIsInstance<String>().toSet()
                            editor.putStringSet(key, strSet)
                        }
                        else -> editor.putString(key, value.toString())
                    }
                }
                editor.apply()
                true
            }
        } catch (e: Exception) {
            log.error("Failed to import preferences from JSON", e)
            false
        }
    }

    /**
     * Serializes a SharedPreferences instance into a PreferenceFileDump.
     */
    fun dumpPreferences(name: String, prefs: SharedPreferences, isDefault: Boolean): PreferenceFileDump {
        val allEntries = prefs.all
        val typedEntries = mutableMapOf<String, TypedPrefEntry>()

        for ((key, value) in allEntries) {
            if (key == null || value == null) continue

            when (value) {
                is Boolean -> typedEntries[key] = TypedPrefEntry(TYPE_BOOLEAN, value = value.toString())
                is Int -> typedEntries[key] = TypedPrefEntry(TYPE_INT, value = value.toString())
                is Long -> typedEntries[key] = TypedPrefEntry(TYPE_LONG, value = value.toString())
                is Float -> typedEntries[key] = TypedPrefEntry(TYPE_FLOAT, value = value.toString())
                is String -> typedEntries[key] = TypedPrefEntry(TYPE_STRING, value = value)
                is Set<*> -> {
                    val stringList = value.filterIsInstance<String>()
                    typedEntries[key] = TypedPrefEntry(TYPE_STRING_SET, values = stringList)
                }
                else -> {
                    log.warn("Unknown preference type for key '{}': {}", key, value.javaClass.name)
                    typedEntries[key] = TypedPrefEntry(TYPE_STRING, value = value.toString())
                }
            }
        }

        return PreferenceFileDump(name, isDefault, typedEntries)
    }

    fun toJson(dump: PreferenceFileDump): String = gson.toJson(dump)

    fun fromJson(json: String): PreferenceFileDump? {
        return try {
            gson.fromJson(json, PreferenceFileDump::class.java)
        } catch (e: Exception) {
            log.error("Failed to parse PreferenceFileDump from JSON", e)
            null
        }
    }

    /**
     * Restores preference entries into SharedPreferences using Editor.
     */
    fun restorePreferences(
        context: Context,
        dump: PreferenceFileDump,
        selectedCategories: Set<BackupCategory>
    ): Boolean {
        return try {
            val prefs = if (dump.isDefault) {
                PreferenceManager.getDefaultSharedPreferences(context)
            } else {
                context.getSharedPreferences(dump.name, Context.MODE_PRIVATE)
            }

            val editor = prefs.edit()
            val shouldRestoreRecentProjects = selectedCategories.contains(BackupCategory.RECENT_PROJECTS)

            for ((key, entry) in dump.entries) {
                if (dump.name == ATC_WIZARD_PREFS && key == "recent_projects" && !shouldRestoreRecentProjects) {
                    continue
                }

                when (entry.type) {
                    TYPE_BOOLEAN -> editor.putBoolean(key, entry.value?.toBoolean() ?: false)
                    TYPE_INT -> editor.putInt(key, entry.value?.toIntOrNull() ?: 0)
                    TYPE_LONG -> editor.putLong(key, entry.value?.toLongOrNull() ?: 0L)
                    TYPE_FLOAT -> editor.putFloat(key, entry.value?.toFloatOrNull() ?: 0f)
                    TYPE_STRING -> editor.putString(key, entry.value)
                    TYPE_STRING_SET -> editor.putStringSet(key, entry.values?.toSet() ?: emptySet())
                    else -> entry.value?.let { editor.putString(key, it) }
                }
            }

            editor.apply()
            true
        } catch (e: Exception) {
            log.error("Failed to restore preferences for dump: {}", dump.name, e)
            false
        }
    }

    /**
     * Resets default and known preferences to empty/default values.
     */
    fun resetAllPreferences(context: Context): Boolean {
        return try {
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()

            val standardNamedPrefs = listOf(
                "git_config",
                "ai_permissions",
                "ai_preferences",
                "memory_opt_config"
            )

            for (name in standardNamedPrefs) {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
            }
            true
        } catch (e: Exception) {
            log.error("Failed to reset preferences", e)
            false
        }
    }
}
