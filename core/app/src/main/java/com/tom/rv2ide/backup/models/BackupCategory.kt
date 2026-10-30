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
    @StringRes val summaryRes: Int
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
    );

    companion object {
        fun fromId(id: String): BackupCategory? = entries.find { it.id.equals(id, ignoreCase = true) }
    }
}
