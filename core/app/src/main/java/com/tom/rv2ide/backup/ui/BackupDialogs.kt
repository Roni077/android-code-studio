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

package com.tom.rv2ide.backup.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.backup.models.BackupCategory
import com.tom.rv2ide.backup.models.BackupInspection
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.AppRestartDialog
import java.text.SimpleDateFormat
import java.util.*

object BackupDialogs {

    /**
     * Displays a dialog allowing the user to select which categories to include in the backup.
     */
    fun showBackupCategorySelector(
        context: Context,
        onConfirm: (Set<BackupCategory>) -> Unit
    ) {
        val categories = BackupCategory.entries.toTypedArray()
        val itemLabels = categories.map { context.getString(it.titleRes) }.toTypedArray()
        val checkedItems = BooleanArray(categories.size) { true }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_dialog_select_categories_title)
            .setMultiChoiceItems(itemLabels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.backup_dialog_create_button) { dialog, _ ->
                val selected = categories.filterIndexed { index, _ -> checkedItems[index] }.toSet()
                if (selected.isNotEmpty()) {
                    onConfirm(selected)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Displays inspection details of a selected backup file and allows selective restoration.
     */
    fun showRestorePreviewDialog(
        context: Context,
        inspection: BackupInspection,
        onConfirm: (Set<BackupCategory>) -> Unit
    ) {
        val manifest = inspection.manifest
        val availableList = inspection.availableCategories.toList()

        val dateStr = if (manifest != null && manifest.createdAt > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(manifest.createdAt))
        } else {
            "Unknown"
        }

        val appVersionStr = manifest?.appVersion ?: "Unknown"
        val deviceStr = manifest?.deviceInfo ?: "Unknown"

        val headerMessage = StringBuilder()
            .append("Backup Date: ").append(dateStr).append("\n")
            .append("App Version: ").append(appVersionStr).append("\n")
            .append("Device: ").append(deviceStr).append("\n\n")
            .append(context.getString(R.string.backup_dialog_restore_warning))
            .toString()

        val itemLabels = availableList.map { context.getString(it.titleRes) }.toTypedArray()
        val checkedItems = BooleanArray(availableList.size) { true }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_dialog_restore_title)
            .setMessage(headerMessage)
            .setMultiChoiceItems(itemLabels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.backup_dialog_restore_button) { dialog, _ ->
                val selected = availableList.filterIndexed { index, _ -> checkedItems[index] }.toSet()
                if (selected.isNotEmpty()) {
                    onConfirm(selected)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Prompts the user before resetting IDE settings to factory defaults.
     */
    fun showResetConfirmDialog(
        context: Context,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.backup_dialog_reset_title)
            .setMessage(R.string.backup_dialog_reset_message)
            .setPositiveButton(R.string.backup_dialog_reset_button) { dialog, _ ->
                onConfirm()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Prompts the user to restart the application to finalize restoring or resetting settings.
     */
    fun showRestartPrompt(context: Context, message: String) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.restart_app_title)
            .setMessage(message)
            .setPositiveButton(R.string.backup_btn_restart_now) { dialog, _ ->
                dialog.dismiss()
                AppRestartDialog.restartApp(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .show()
    }
}
