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

package com.tom.rv2ide.flutter.actions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.hasRequiredData
import com.tom.rv2ide.activities.TerminalActivity
import com.tom.rv2ide.flutter.project.FlutterProjectHelper
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.resources.R
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal fun promptInstallFlutter(activity: Activity) {
  MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.title_flutter_setup)
      .setMessage(R.string.msg_prompt_install_flutter)
      .setPositiveButton(R.string.title_install_tools) { _, _ ->
        val intent = Intent(activity, TerminalActivity::class.java).apply {
          putExtra(TerminalActivity.EXTRA_RUN_FLUTTER_SETUP, true)
          putExtra(TerminalActivity.EXTRA_RUN_FLUTTER_SETUP_ARGS, arrayOf("-y"))
        }
        activity.startActivity(intent)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
}

abstract class BaseFlutterAction : ActionItem {

  override var enabled: Boolean = true
  override var visible: Boolean = true
  override var icon: Drawable? = null
  override var label: String = ""
  override var location: ActionItem.Location = ActionItem.Location.EDITOR_TOOLBAR
  override var requiresUIThread: Boolean = true

  protected val actionScope =
      CoroutineScope(Dispatchers.Default + CoroutineName("${javaClass.simpleName}Scope"))

  override fun prepare(data: ActionData) {
    super.prepare(data)
    if (!data.hasRequiredData(Context::class.java)) {
      visible = false
      enabled = false
    }
  }

  fun ActionData.getActivity(): Activity? {
    return this[Context::class.java] as? Activity
  }
}

/**
 * Action to run `flutter run` in the interactive terminal.
 */
class FlutterRunAction(context: Context, override val order: Int) : BaseFlutterAction() {

  override val id: String = "ide.editor.flutter.run"

  init {
    label = context.getString(R.string.title_flutter_run)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val projectDir = IProjectManager.getInstance().projectDir
    val isFlutter = FlutterProjectHelper.isFlutterProject(projectDir)
    visible = isFlutter
    enabled = isFlutter
  }

  override suspend fun execAction(data: ActionData) {
    val activity = data.getActivity() ?: return
    val projectDir = IProjectManager.getInstance().projectDir ?: return

    if (!FlutterProjectHelper.isFlutterInstalled()) {
      promptInstallFlutter(activity)
      return
    }

    FlutterProjectHelper.configureProjectForAndroidCS(projectDir)

    val intent = Intent(activity, TerminalActivity::class.java).apply {
      putExtra(TerminalActivity.EXTRA_WORKING_DIR, projectDir.absolutePath)
      putExtra(TerminalActivity.EXTRA_INITIAL_COMMAND, "flutter run")
      putExtra(TerminalActivity.EXTRA_SESSION_NAME, "Flutter Run")
    }
    activity.startActivity(intent)
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    return MenuItem.SHOW_AS_ACTION_ALWAYS
  }
}

/**
 * Action to build an APK using `flutter build apk --debug`.
 */
class FlutterBuildApkAction(context: Context, override val order: Int) : BaseFlutterAction() {

  override val id: String = "ide.editor.flutter.buildApk"

  init {
    label = context.getString(R.string.title_flutter_build_apk)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_apk_install)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val projectDir = IProjectManager.getInstance().projectDir
    val isFlutter = FlutterProjectHelper.isFlutterProject(projectDir)
    visible = isFlutter
    enabled = isFlutter
  }

  override suspend fun execAction(data: ActionData) {
    val activity = data.getActivity() ?: return
    val projectDir = IProjectManager.getInstance().projectDir ?: return

    if (!FlutterProjectHelper.isFlutterInstalled()) {
      promptInstallFlutter(activity)
      return
    }

    FlutterProjectHelper.configureProjectForAndroidCS(projectDir)

    val intent = Intent(activity, TerminalActivity::class.java).apply {
      putExtra(TerminalActivity.EXTRA_WORKING_DIR, projectDir.absolutePath)
      putExtra(TerminalActivity.EXTRA_INITIAL_COMMAND, "flutter build apk --debug --no-tree-shake-icons")
      putExtra(TerminalActivity.EXTRA_SESSION_NAME, "Build APK")
    }
    activity.startActivity(intent)
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    return MenuItem.SHOW_AS_ACTION_IF_ROOM
  }
}

/**
 * Action to run `flutter pub get`.
 */
class FlutterPubGetAction(context: Context, override val order: Int) : BaseFlutterAction() {

  override val id: String = "ide.editor.flutter.pubGet"

  init {
    label = context.getString(R.string.title_flutter_pub_get)
    icon = ContextCompat.getDrawable(context, R.drawable.download)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val projectDir = IProjectManager.getInstance().projectDir
    val isFlutter = FlutterProjectHelper.isFlutterProject(projectDir)
    visible = isFlutter
    enabled = isFlutter
  }

  override suspend fun execAction(data: ActionData) {
    val activity = data.getActivity() ?: return
    val projectDir = IProjectManager.getInstance().projectDir ?: return

    if (!FlutterProjectHelper.isFlutterInstalled()) {
      promptInstallFlutter(activity)
      return
    }

    val intent = Intent(activity, TerminalActivity::class.java).apply {
      putExtra(TerminalActivity.EXTRA_WORKING_DIR, projectDir.absolutePath)
      putExtra(TerminalActivity.EXTRA_INITIAL_COMMAND, "flutter pub get")
      putExtra(TerminalActivity.EXTRA_SESSION_NAME, "Pub Get")
    }
    activity.startActivity(intent)
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    return MenuItem.SHOW_AS_ACTION_NEVER
  }
}

/**
 * Action to run `flutter analyze`.
 */
class FlutterAnalyzeAction(context: Context, override val order: Int) : BaseFlutterAction() {

  override val id: String = "ide.editor.flutter.analyze"

  init {
    label = context.getString(R.string.title_flutter_analyze)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run_tasks)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val projectDir = IProjectManager.getInstance().projectDir
    val isFlutter = FlutterProjectHelper.isFlutterProject(projectDir)
    visible = isFlutter
    enabled = isFlutter
  }

  override suspend fun execAction(data: ActionData) {
    val activity = data.getActivity() ?: return
    val projectDir = IProjectManager.getInstance().projectDir ?: return

    if (!FlutterProjectHelper.isFlutterInstalled()) {
      promptInstallFlutter(activity)
      return
    }

    val intent = Intent(activity, TerminalActivity::class.java).apply {
      putExtra(TerminalActivity.EXTRA_WORKING_DIR, projectDir.absolutePath)
      putExtra(TerminalActivity.EXTRA_INITIAL_COMMAND, "flutter analyze")
      putExtra(TerminalActivity.EXTRA_SESSION_NAME, "Analyze")
    }
    activity.startActivity(intent)
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    return MenuItem.SHOW_AS_ACTION_NEVER
  }
}

/**
 * Action to run `flutter doctor -v`.
 */
class FlutterDoctorAction(context: Context, override val order: Int) : BaseFlutterAction() {

  override val id: String = "ide.editor.flutter.doctor"

  init {
    label = context.getString(R.string.title_flutter_doctor)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_alert)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val projectDir = IProjectManager.getInstance().projectDir
    val isFlutter = FlutterProjectHelper.isFlutterProject(projectDir)
    visible = isFlutter
    enabled = isFlutter
  }

  override suspend fun execAction(data: ActionData) {
    val activity = data.getActivity() ?: return

    val intent = Intent(activity, TerminalActivity::class.java).apply {
      putExtra(TerminalActivity.EXTRA_INITIAL_COMMAND, "flutter doctor -v")
      putExtra(TerminalActivity.EXTRA_SESSION_NAME, "Flutter Doctor")
    }
    activity.startActivity(intent)
  }

  override fun getShowAsActionFlags(data: ActionData): Int {
    return MenuItem.SHOW_AS_ACTION_NEVER
  }
}
