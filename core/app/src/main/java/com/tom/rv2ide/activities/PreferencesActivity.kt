/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.Insets
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tom.rv2ide.R
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.backup.BackupManager
import com.tom.rv2ide.backup.PreferencesBackupHelper
import com.tom.rv2ide.backup.models.BackupCategory
import com.tom.rv2ide.backup.ui.BackupDialogs
import com.tom.rv2ide.databinding.ActivityPreferencesBinding
import com.tom.rv2ide.fragments.IDEPreferencesFragment
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.preferences.IDEPreferences as prefs
import com.tom.rv2ide.preferences.addRootPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.tom.rv2ide.utils.Environment
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class PreferencesActivity :
    EdgeToEdgeIDEActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

  private var _binding: ActivityPreferencesBinding? = null
  private val binding: ActivityPreferencesBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  private val rootFragment by lazy { IDEPreferencesFragment() }

  private var pendingBackupCategories: Set<BackupCategory> = emptySet()

  private val createBackupLauncher =
      registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) {
          executeBackup(uri, pendingBackupCategories)
        }
      }

  private val exportSettingsJsonLauncher =
      registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
          executeExportSettingsJson(uri)
        }
      }

  private val restoreBackupLauncher =
      registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          inspectAndPromptRestore(uri)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setSupportActionBar(binding.toolbar)
    supportActionBar!!.setTitle(R.string.ide_preferences)
    supportActionBar!!.setDisplayHomeAsUpEnabled(true)

    binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

    supportFragmentManager.addOnBackStackChangedListener {
      if (supportFragmentManager.backStackEntryCount == 0) {
        supportActionBar?.setTitle(R.string.ide_preferences)
      }
    }

    if (savedInstanceState != null) {
      return
    }

    (prefs.children as MutableList?)?.clear()

    prefs.addRootPreferences()

    val args = Bundle()
    args.putParcelableArrayList(IDEPreferencesFragment.EXTRA_CHILDREN, ArrayList(prefs.children))

    rootFragment.arguments = args
    loadFragment(rootFragment)
  }

  override fun onPreferenceStartFragment(
      caller: PreferenceFragmentCompat,
      pref: Preference
  ): Boolean {
    val fragmentClass = pref.fragment ?: return false
    val fragment = supportFragmentManager.fragmentFactory.instantiate(
        classLoader,
        fragmentClass
    ).apply {
      arguments = pref.extras
    }
    supportFragmentManager.beginTransaction()
        .replace(binding.fragmentContainer.id, fragment)
        .addToBackStack(null)
        .commit()
    supportActionBar?.title = pref.title
    return true
  }

  fun startCreateBackupFlow() {
    BackupDialogs.showBackupCategorySelector(this) { selectedCategories ->
      pendingBackupCategories = selectedCategories
      val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
      val defaultFileName = "acs-backup-${sdf.format(Date())}.tar.gz"
      createBackupLauncher.launch(defaultFileName)
    }
  }

  fun startExportSettingsJsonFlow() {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val defaultFileName = "acs-settings-${sdf.format(Date())}.json"
    exportSettingsJsonLauncher.launch(defaultFileName)
  }

  fun startRestoreBackupFlow() {
    restoreBackupLauncher.launch(
        arrayOf(
            "application/gzip",
            "application/x-gzip",
            "application/x-tar",
            "application/zip",
            "application/json",
            "application/octet-stream",
            "*/*"
        )
    )
  }

  fun startResetSettingsFlow() {
    BackupDialogs.showResetConfirmDialog(this) {
      executeReset()
    }
  }

  private fun executeExportSettingsJson(uri: Uri) {
    lifecycleScope.launch {
      val progressDialog = MaterialAlertDialogBuilder(this@PreferencesActivity)
          .setMessage(R.string.backup_creating_progress)
          .setCancelable(false)
          .show()

      try {
        val outputStream = contentResolver.openOutputStream(uri)
        if (outputStream == null) {
          progressDialog.dismiss()
          Toast.makeText(this@PreferencesActivity, "Failed to open destination file", Toast.LENGTH_LONG).show()
          return@launch
        }

        val json = PreferencesBackupHelper.exportAllPreferencesAsJson(this@PreferencesActivity)
        outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        progressDialog.dismiss()

        val size = getFileSize(uri)
        val formattedSize = Formatter.formatFileSize(this@PreferencesActivity, size)
        val fileName = getFileName(uri) ?: "settings.json"
        val msg = getString(R.string.backup_create_success, fileName, formattedSize)
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
      } catch (e: Exception) {
        progressDialog.dismiss()
        val msg = getString(R.string.backup_create_failed, e.message ?: "Unknown error")
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
      }
    }
  }

  private fun executeBackup(uri: Uri, categories: Set<BackupCategory>) {
    lifecycleScope.launch {
      val progressDialog = MaterialAlertDialogBuilder(this@PreferencesActivity)
          .setMessage(R.string.backup_creating_progress)
          .setCancelable(false)
          .show()

      try {
        val outputStream = contentResolver.openOutputStream(uri)
        if (outputStream == null) {
          progressDialog.dismiss()
          Toast.makeText(this@PreferencesActivity, "Failed to open destination file", Toast.LENGTH_LONG).show()
          return@launch
        }

        val result = BackupManager.createBackup(this@PreferencesActivity, outputStream, categories)
        progressDialog.dismiss()

        result.onSuccess {
          val size = getFileSize(uri)
          val formattedSize = Formatter.formatFileSize(this@PreferencesActivity, size)
          val fileName = getFileName(uri) ?: "backup.zip"
          val msg = getString(R.string.backup_create_success, fileName, formattedSize)
          Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }.onFailure { error ->
          val msg = getString(R.string.backup_create_failed, error.message ?: "Unknown error")
          Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
      } catch (e: Exception) {
        progressDialog.dismiss()
        val msg = getString(R.string.backup_create_failed, e.message ?: "Unknown error")
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
      }
    }
  }

  private fun inspectAndPromptRestore(uri: Uri) {
    lifecycleScope.launch {
      val progressDialog = MaterialAlertDialogBuilder(this@PreferencesActivity)
          .setMessage("Inspecting backup archive...")
          .setCancelable(false)
          .show()

      try {
        val inputStream = contentResolver.openInputStream(uri)
        if (inputStream == null) {
          progressDialog.dismiss()
          Toast.makeText(this@PreferencesActivity, "Failed to read backup file", Toast.LENGTH_LONG).show()
          return@launch
        }

        val inspection = BackupManager.inspectBackup(inputStream)
        progressDialog.dismiss()

        if (!inspection.isValid) {
          MaterialAlertDialogBuilder(this@PreferencesActivity)
              .setTitle(R.string.backup_inspect_failed)
              .setMessage(inspection.errorMessage ?: "Unknown error")
              .setPositiveButton(android.R.string.ok, null)
              .show()
          return@launch
        }

        if (inspection.isStandaloneJson) {
          BackupDialogs.showJsonRestoreConfirmDialog(this@PreferencesActivity) {
            executeRestore(uri, setOf(BackupCategory.PREFERENCES))
          }
        } else {
          BackupDialogs.showRestorePreviewDialog(this@PreferencesActivity, inspection) { selectedCategories ->
            executeRestore(uri, selectedCategories)
          }
        }
      } catch (e: Exception) {
        progressDialog.dismiss()
        Toast.makeText(this@PreferencesActivity, "Error inspecting backup: ${e.message}", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun executeRestore(uri: Uri, categories: Set<BackupCategory>) {
    lifecycleScope.launch {
      val progressDialog = MaterialAlertDialogBuilder(this@PreferencesActivity)
          .setMessage(R.string.backup_restoring_progress)
          .setCancelable(false)
          .show()

      try {
        val inputStream = contentResolver.openInputStream(uri)
        if (inputStream == null) {
          progressDialog.dismiss()
          Toast.makeText(this@PreferencesActivity, "Failed to open backup file", Toast.LENGTH_LONG).show()
          return@launch
        }

        val result = BackupManager.restoreBackup(this@PreferencesActivity, inputStream, categories)
        progressDialog.dismiss()

        result.onSuccess {
          // Hot reload UI theme immediately
          val uiMode = GeneralPreferences.uiMode
          AppCompatDelegate.setDefaultNightMode(uiMode)

          BackupDialogs.showRestartPrompt(
              this@PreferencesActivity,
              getString(R.string.backup_restore_success)
          )
        }.onFailure { error ->
          MaterialAlertDialogBuilder(this@PreferencesActivity)
              .setTitle("Restore Failed")
              .setMessage(getString(R.string.backup_restore_failed, error.message))
              .setPositiveButton(android.R.string.ok, null)
              .show()
        }
      } catch (e: Exception) {
        progressDialog.dismiss()
        Toast.makeText(this@PreferencesActivity, "Error restoring backup: ${e.message}", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun executeReset() {
    lifecycleScope.launch {
      val result = BackupManager.resetPreferences(this@PreferencesActivity)
      result.onSuccess {
        BackupDialogs.showRestartPrompt(
            this@PreferencesActivity,
            getString(R.string.backup_reset_success)
        )
      }.onFailure { error ->
        Toast.makeText(this@PreferencesActivity, "Failed to reset settings: ${error.message}", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun getFileSize(uri: Uri): Long {
    if (uri.scheme == "content") {
      contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val columnIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (columnIndex != -1) {
            return cursor.getLong(columnIndex)
          }
        }
      }
    }
    return 0L
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)

    if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
      data?.data?.let { uri ->
        try {
          val fontDir = File("${Environment.HOME}/.androidide/ui")
          if (!fontDir.exists()) {
            fontDir.mkdirs()
          }

          val fileName = getFileName(uri) ?: "custom_font.ttf"
          val destFile = File(fontDir, fileName)

          contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
              input.copyTo(output)
            }
          }

          Toast.makeText(this, "Font copied successfully: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
          Toast.makeText(this, "Error copying font: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  private fun getFileName(uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
      contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (columnIndex != -1) {
            result = cursor.getString(columnIndex)
          }
        }
      }
    }
    if (result == null) {
      result = uri.path?.let { path ->
        val cut = path.lastIndexOf('/')
        if (cut != -1) path.substring(cut + 1) else path
      }
    }
    return result
  }

  /** Force restart the entire application Call this method when theme changes need to be applied */
  fun forceRestartApp() {
    finishAffinity() // Close all activities

    // Restart the application
    val intent = packageManager.getLaunchIntentForPackage(packageName)
    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)

    // Force exit to ensure clean restart
    exitProcess(0)
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    if (_binding == null) return // Skip if binding not initialized yet

    val toolbar: View = binding.toolbar
    toolbar.setPadding(
        toolbar.paddingLeft + insets.left,
        toolbar.paddingTop,
        toolbar.paddingRight + insets.right,
        toolbar.paddingBottom,
    )

    val fragmentContainer: View = binding.fragmentContainerParent
    fragmentContainer.setPadding(
        fragmentContainer.paddingLeft + insets.left,
        fragmentContainer.paddingTop,
        fragmentContainer.paddingRight + insets.right,
        fragmentContainer.paddingBottom,
    )
  }

  override fun bindLayout(): View {
    _binding = ActivityPreferencesBinding.inflate(layoutInflater)
    return binding.root
  }

  private fun loadFragment(fragment: Fragment) {
    super.loadFragment(fragment, binding.fragmentContainer.id)
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }
}
