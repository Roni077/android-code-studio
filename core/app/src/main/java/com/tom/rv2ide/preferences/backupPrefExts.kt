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

package com.tom.rv2ide.preferences

import android.content.Context
import android.content.ContextWrapper
import androidx.preference.Preference
import com.tom.rv2ide.activities.PreferencesActivity
import com.tom.rv2ide.resources.R
import kotlinx.parcelize.Parcelize

private fun Context.findPreferencesActivity(): PreferencesActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is PreferencesActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Parcelize
class BackupAndRestorePreferencesScreen(
    override val key: String = "idepref_backup_restore",
    override val title: Int = R.string.backup_restore_title,
    override val summary: Int? = R.string.backup_restore_summary,
    override val icon: Int? = R.drawable.ic_backup,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

    init {
        addPreference(BackupGroup())
        addPreference(RestoreGroup())
        addPreference(ResetGroup())
        addPreference(AboutBackupGroup())
    }
}

@Parcelize
class BackupPreferencesGroup(
    override val key: String = "idepref_group_backup_restore",
    override val title: Int = R.string.backup_restore_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

    init {
        addPreference(BackupAndRestorePreferencesScreen())
    }
}

@Parcelize
private class BackupGroup(
    override val key: String = "idepref_backup_group",
    override val title: Int = R.string.backup_group_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

    init {
        addPreference(
            SimpleClickablePreference(
                key = "idepref_create_backup",
                title = R.string.backup_create_title,
                summary = R.string.backup_create_summary,
                icon = R.drawable.ic_backup,
                onClick = { pref ->
                    pref.context.findPreferencesActivity()?.startCreateBackupFlow()
                    true
                }
            )
        )
        addPreference(
            SimpleClickablePreference(
                key = "idepref_export_settings_json",
                title = R.string.backup_export_json_title,
                summary = R.string.backup_export_json_summary,
                icon = R.drawable.ic_backup,
                onClick = { pref ->
                    pref.context.findPreferencesActivity()?.startExportSettingsJsonFlow()
                    true
                }
            )
        )
    }
}

@Parcelize
private class RestoreGroup(
    override val key: String = "idepref_restore_group",
    override val title: Int = R.string.restore_group_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

    init {
        addPreference(
            SimpleClickablePreference(
                key = "idepref_restore_backup",
                title = R.string.backup_restore_action_title,
                summary = R.string.backup_restore_action_summary,
                icon = R.drawable.ic_restore,
                onClick = { pref ->
                    pref.context.findPreferencesActivity()?.startRestoreBackupFlow()
                    true
                }
            )
        )
    }
}

@Parcelize
private class ResetGroup(
    override val key: String = "idepref_reset_group",
    override val title: Int = R.string.reset_group_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

    init {
        addPreference(
            SimpleClickablePreference(
                key = "idepref_reset_settings",
                title = R.string.backup_reset_title,
                summary = R.string.backup_reset_summary,
                icon = R.drawable.ic_reset,
                onClick = { pref ->
                    pref.context.findPreferencesActivity()?.startResetSettingsFlow()
                    true
                }
            )
        )
    }
}

@Parcelize
private class AboutBackupGroup(
    override val key: String = "idepref_about_backup_group",
    override val title: Int = R.string.backup_about_group_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

    init {
        addPreference(AboutBackupInfoPreference())
    }
}

@Parcelize
private class AboutBackupInfoPreference(
    override val key: String = "idepref_about_backup_info",
    override val title: Int = R.string.backup_about_info_title,
    override val summary: Int? = R.string.backup_about_info_summary,
    override val icon: Int? = R.drawable.ic_backup,
) : SimplePreference()
