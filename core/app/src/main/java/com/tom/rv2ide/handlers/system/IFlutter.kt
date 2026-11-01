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

package com.tom.rv2ide.handlers.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.customizablecardview.CustomizableCardView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.IDEConfigurations
import com.tom.rv2ide.activities.TerminalActivity
import com.tom.rv2ide.handlers.IConfigHandler
import com.tom.rv2ide.managers.PreferenceManager
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashSuccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configuration handler for the on-device Flutter SDK toolchain in [IDEConfigurations].
 */
class IFlutter(
    private val context: Context,
    private val prefManager: PreferenceManager,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val isDarkTheme: Boolean,
) : IConfigHandler {

  override val handlerName: String = "Flutter"
  override val cardHolderId: Int = R.id.flutterCardHolder
  override val removeButtonId: Int = R.id.flutter_remove_btn
  override val reinstallButtonId: Int = R.id.flutter_install_btn

  override fun initialize() {
    val activity = context as? Activity
    val doctorBtn = activity?.findViewById<MaterialButton>(R.id.flutter_doctor_btn)
    doctorBtn?.setOnClickListener {
      if (!isFlutterInstalled()) {
        Toast.makeText(context, "Flutter is not installed yet.", Toast.LENGTH_SHORT).show()
        return@setOnClickListener
      }
      val intent = Intent(context, TerminalActivity::class.java).apply {
        putExtra(TerminalActivity.EXTRA_INITIAL_COMMAND, "flutter doctor -v")
        putExtra(TerminalActivity.EXTRA_SESSION_NAME, "Flutter Doctor")
      }
      context.startActivity(intent)
    }

    updateStatus()
  }

  override fun updateStatus() {
    val activity = context as? IDEConfigurations
    val cardHolder = activity?.findViewById<CustomizableCardView>(cardHolderId)
    val removeBtn = activity?.findViewById<View>(removeButtonId)
    val doctorBtn = activity?.findViewById<View>(R.id.flutter_doctor_btn)
    val installBtn = activity?.findViewById<MaterialButton>(reinstallButtonId)

    val installedVersion = getInstalledVersion()
    val statusText: String
    val color: Int

    if (installedVersion != null) {
      statusText = installedVersion
      color = if (isDarkTheme) Color.GREEN else Color.rgb(0, 120, 0)
      removeBtn?.visibility = View.VISIBLE
      doctorBtn?.visibility = View.VISIBLE
      installBtn?.text = "Reinstall"
    } else {
      statusText = "Not installed"
      color = Color.RED
      removeBtn?.visibility = View.GONE
      doctorBtn?.visibility = View.GONE
      installBtn?.text = "Install"
    }

    val fullText = context.getString(R.string.cfg_installed_flutter_version, statusText)
    val spannableString = SpannableString(fullText)

    val startIndex = fullText.indexOf(statusText)
    if (startIndex != -1) {
      val endIndex = startIndex + statusText.length
      spannableString.setSpan(
          ForegroundColorSpan(color),
          startIndex,
          endIndex,
          SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
    }

    cardHolder?.setCardSummary(spannableString)
  }

  override fun onReinstallClick() {
    val intent = Intent(context, TerminalActivity::class.java).apply {
      putExtra(TerminalActivity.EXTRA_RUN_FLUTTER_SETUP, true)
      putExtra(TerminalActivity.EXTRA_RUN_FLUTTER_SETUP_ARGS, arrayOf("-y"))
    }
    context.startActivity(intent)
  }

  override fun onRemoveClick() {
    if (!isFlutterInstalled()) {
      Toast.makeText(context, "Flutter is not installed", Toast.LENGTH_SHORT).show()
      return
    }

    MaterialAlertDialogBuilder(context)
        .setTitle("Remove Flutter SDK")
        .setMessage("Are you sure you want to remove the on-device Flutter SDK? This will delete ${Environment.FLUTTER_HOME.absolutePath}.")
        .setPositiveButton("Remove") { _, _ ->
          lifecycleScope.launch(Dispatchers.IO) {
            try {
              if (Environment.FLUTTER_HOME.exists()) {
                Environment.FLUTTER_HOME.deleteRecursively()
              }
              val homeSdk = File(Environment.HOME, "flutter-sdk")
              if (homeSdk.exists()) {
                homeSdk.delete()
              }
              withContext(Dispatchers.Main) {
                (context as? Activity)?.flashSuccess("Flutter SDK removed successfully.")
                updateStatus()
              }
            } catch (e: Exception) {
              withContext(Dispatchers.Main) {
                (context as? Activity)?.flashError("Failed to remove Flutter SDK: ${e.message}")
              }
            }
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  override fun cleanup() {
  }

  private fun isFlutterInstalled(): Boolean {
    return Environment.FLUTTER_BIN != null && Environment.FLUTTER_BIN.exists()
  }

  private fun getInstalledVersion(): String? {
    if (!isFlutterInstalled()) return null

    val verFile = File(Environment.FLUTTER_HOME, "version")
    if (verFile.exists()) {
      val v = verFile.readText().trim()
      if (v.isNotEmpty()) return v
    }

    val jsonFile = File(Environment.FLUTTER_HOME, "bin/cache/flutter.version.json")
    if (jsonFile.exists()) {
      try {
        val content = jsonFile.readText()
        val match = Regex(""""frameworkVersion"\s*:\s*"([^"]+)"""").find(content)
        if (match != null) return match.groupValues[1]
      } catch (_: Exception) {}
    }

    return "Installed"
  }
}
