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

package com.tom.rv2ide.actions.build

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.BaseBuildAction
import com.tom.rv2ide.flutter.project.FlutterProjectHelper
import com.tom.rv2ide.flutter.service.FlutterBuildManager
import com.tom.rv2ide.fragments.RunTasksDialogFragment
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.resources.R

/** @author Akash Yadav */
class RunTasksAction(context: Context, override val order: Int) : BaseBuildAction() {
  override val id: String = "ide.editor.build.runTasks"
  private var dialog: DialogFragment? = null

  init {
    label = context.getString(R.string.title_run_tasks)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run_tasks)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val projectDir = IProjectManager.getInstance().projectDir
    if (FlutterProjectHelper.isFlutterProject(projectDir)) {
      label = data.getActivity()?.getString(R.string.title_flutter_tasks) ?: "Flutter Tasks"
      enabled = !FlutterBuildManager.isBuildInProgress
      visible = true
    }
  }

  override suspend fun execAction(data: ActionData): Any {
    dialog?.dismiss()
    dialog = null

    val toShow = RunTasksDialogFragment()
    dialog = toShow
    return toShow
  }

  override fun postExec(data: ActionData, result: Any) {
    if (result !is DialogFragment) {
      return
    }

    val activity = data.getActivity() ?: return
    result.show(activity.supportFragmentManager, this.id)
  }

  override fun destroy() {
    super.destroy()
    try {
      dialog?.dismiss()
    } catch (e: Exception) {
      // ignored
    }
    dialog = null
  }
}
