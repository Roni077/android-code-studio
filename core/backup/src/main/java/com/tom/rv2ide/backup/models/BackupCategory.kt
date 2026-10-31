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

package com.tom.rv2ide.backup.models

import androidx.annotation.StringRes
import com.tom.rv2ide.resources.R

enum class BackupCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val isHeavyweight: Boolean = false
) {
    PREFERENCES(
        "preferences",
        R.string.backup_category_preferences,
        R.string.backup_category_preferences_summary
    ),
    COLOR_SCHEMES(
        "color_schemes",
        R.string.backup_category_schemes,
        R.string.backup_category_schemes_summary
    ),
    FONTS(
        "fonts",
        R.string.backup_category_fonts,
        R.string.backup_category_fonts_summary
    ),
    GRADLE_INIT(
        "gradle_init",
        R.string.backup_category_gradle,
        R.string.backup_category_gradle_summary
    ),
    TERMINAL_CONFIG(
        "terminal_config",
        R.string.backup_category_terminal,
        R.string.backup_category_terminal_summary
    ),
    RECENT_PROJECTS(
        "recent_projects",
        R.string.backup_category_recent_projects,
        R.string.backup_category_recent_projects_summary
    ),
    TERMINAL_ENVIRONMENT(
        "terminal_environment",
        R.string.backup_category_terminal_environment,
        R.string.backup_category_terminal_environment_summary,
        isHeavyweight = true
    ),
    ANDROID_SDK(
        "android_sdk",
        R.string.backup_category_android_sdk,
        R.string.backup_category_android_sdk_summary,
        isHeavyweight = true
    ),
    FLUTTER_SDK(
        "flutter_sdk",
        R.string.backup_category_flutter_sdk,
        R.string.backup_category_flutter_sdk_summary,
        isHeavyweight = true
    ),
    GRADLE_CACHE(
        "gradle_cache",
        R.string.backup_category_gradle_cache,
        R.string.backup_category_gradle_cache_summary,
        isHeavyweight = true
    ),
    LSP_SERVERS(
        "lsp_servers",
        R.string.backup_category_lsp_servers,
        R.string.backup_category_lsp_servers_summary,
        isHeavyweight = true
    );

    companion object {
        fun fromId(id: String): BackupCategory? = entries.find { it.id.equals(id, ignoreCase = true) }

        fun fromRelativePath(path: String): BackupCategory? {
            val cleanPath = path.removePrefix("./").removePrefix("/")
            return when {
                // Termux format: ./home/ and ./usr/
                cleanPath.startsWith("home/.androidide/preferences/") -> PREFERENCES
                cleanPath.startsWith("home/.androidide/ui/") -> FONTS
                cleanPath.startsWith("home/.androidide/editor/schemes/") -> COLOR_SCHEMES
                cleanPath.startsWith("home/.androidide/recent_projects.json") -> RECENT_PROJECTS
                cleanPath.startsWith("home/.androidide/lsp/") -> LSP_SERVERS
                cleanPath.startsWith("home/android-sdk/") -> ANDROID_SDK
                cleanPath.startsWith("home/flutter-sdk/") || cleanPath.startsWith("home/.pub-cache/") -> FLUTTER_SDK
                cleanPath.startsWith("usr/opt/flutter/") -> FLUTTER_SDK
                cleanPath.startsWith("home/.gradle/caches/") || cleanPath.startsWith("home/.gradle/wrapper/") -> GRADLE_CACHE
                cleanPath.startsWith("home/.gradle/init.gradle") || cleanPath.startsWith("home/.gradle/init.d/") -> GRADLE_INIT
                cleanPath.startsWith("home/.bashrc") || cleanPath.startsWith("home/.profile") ||
                        cleanPath.startsWith("home/.gitconfig") || cleanPath.startsWith("home/.termux/") -> TERMINAL_CONFIG
                cleanPath.startsWith("usr/") || cleanPath.startsWith("home/bin/") ||
                        cleanPath.startsWith("home/.config/") || cleanPath.startsWith("home/.local/") -> TERMINAL_ENVIRONMENT

                // Legacy ACS zip format
                cleanPath.startsWith("preferences/") -> PREFERENCES
                cleanPath.startsWith("ui/fonts/") -> FONTS
                cleanPath.startsWith("ui/schemes/") -> COLOR_SCHEMES
                cleanPath.startsWith("init/") -> GRADLE_INIT
                cleanPath.startsWith("terminal/env/") -> TERMINAL_ENVIRONMENT
                cleanPath.startsWith("terminal/") -> TERMINAL_CONFIG
                cleanPath.startsWith("toolchains/sdk/") -> ANDROID_SDK
                cleanPath.startsWith("toolchains/flutter/") || cleanPath.startsWith("toolchains/pub-cache/") -> FLUTTER_SDK
                cleanPath.startsWith("toolchains/gradle/") -> GRADLE_CACHE
                cleanPath.startsWith("toolchains/lsp/") -> LSP_SERVERS
                else -> null
            }
        }
    }
}
