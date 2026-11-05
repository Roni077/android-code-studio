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

package com.tom.rv2ide.fragments

import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.WindowInsetsCompat.Type.navigationBars
import androidx.core.view.WindowInsetsCompat.Type.statusBars
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.blankj.utilcode.util.ThreadUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.databinding.LayoutRunTaskBinding
import com.tom.rv2ide.databinding.LayoutRunTaskDialogBinding
import com.tom.rv2ide.databinding.LayoutRunTaskItemBinding
import com.tom.rv2ide.flutter.actions.promptInstallFlutter
import com.tom.rv2ide.flutter.project.FlutterProjectHelper
import com.tom.rv2ide.flutter.service.FlutterBuildManager
import com.tom.rv2ide.flutter.service.FlutterBuildService
import com.tom.rv2ide.flutter.tasks.FlutterTask
import com.tom.rv2ide.flutter.tasks.FlutterTaskRegistry
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.SingleTextWatcher
import com.tom.rv2ide.utils.doOnApplyWindowInsets
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Dedicated BottomSheetDialogFragment for Flutter task selection and execution.
 */
class FlutterTasksDialogFragment : BottomSheetDialogFragment() {

  private lateinit var binding: LayoutRunTaskDialogBinding
  private lateinit var run: LayoutRunTaskBinding

  private val allTasks: List<FlutterTask> = FlutterTaskRegistry.getAvailableTasks()
  private var filteredTasks: MutableList<FlutterTask> = allTasks.toMutableList()
  private var selectedTask: FlutterTask? = null
  private var displayedChild: Int = CHILD_TASKS

  companion object {
    private val log = LoggerFactory.getLogger(FlutterTasksDialogFragment::class.java)

    private const val CHILD_LOADING = 0
    private const val CHILD_TASKS = 1
    private const val CHILD_CONFIRMATION = 2
    private const val CHILD_PROJECT_NOT_INITIALIZED = 3
    private const val SEARCH_DELAY = 300L
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog =
        object : BottomSheetDialog(requireContext(), theme) {
          override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            findViewById<View>(com.google.android.material.R.id.container)?.apply {
              doOnApplyWindowInsets { view, insets, _, margins ->
                insets.getInsets(statusBars() or navigationBars()).apply {
                  view.updateLayoutParams<MarginLayoutParams> {
                    updateMargins(top = margins.top + top)
                  }
                  run.tasks.apply {
                    updatePadding(bottom = bottom)
                    clipToPadding = false
                    clipChildren = false
                  }
                }
              }
            }
          }
        }
    dialog.behavior.peekHeight = (getWindowHeight() * 0.7).toInt()
    return dialog
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    binding = LayoutRunTaskDialogBinding.inflate(inflater, container, false)
    run = binding.run
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    setDisplayedChild(CHILD_TASKS)
    setupRecyclerView()

    run.searchInput.editText?.addTextChangedListener(
        object : SingleTextWatcher() {
          val searchRunner = Runnable {
            filterTasks(run.searchInput.editText?.text?.toString() ?: "")
          }

          override fun afterTextChanged(s: Editable?) {
            ThreadUtils.getMainHandler().removeCallbacks(searchRunner)
            ThreadUtils.runOnUiThreadDelayed(searchRunner, SEARCH_DELAY)
          }
        }
    )

    binding.exec.setOnClickListener {
      val task = selectedTask
      if (task == null) {
        requireActivity().flashInfo("Please select a task to execute")
        return@setOnClickListener
      }

      if (displayedChild == CHILD_TASKS) {
        binding.confirm.msg.text = "Are you sure you want to run '${task.name}'?"
        setDisplayedChild(CHILD_CONFIRMATION)
        return@setOnClickListener
      }

      if (displayedChild == CHILD_CONFIRMATION) {
        executeSelectedTask(task)
        dismiss()
      }
    }

    binding.confirm.cancel.setOnClickListener {
      setDisplayedChild(CHILD_TASKS)
    }
  }

  private fun setupRecyclerView() {
    run.tasks.adapter = FlutterTasksAdapter(filteredTasks, selectedTask) { task ->
      selectedTask = if (selectedTask?.id == task.id) null else task
      (run.tasks.adapter as? FlutterTasksAdapter)?.updateSelected(selectedTask)
    }
  }

  private fun filterTasks(query: String) {
    filteredTasks.clear()
    if (query.isBlank()) {
      filteredTasks.addAll(allTasks)
    } else {
      val lower = query.lowercase().trim()
      filteredTasks.addAll(
          allTasks.filter {
            it.name.lowercase().contains(lower) ||
                it.description.lowercase().contains(lower) ||
                it.category.title.lowercase().contains(lower) ||
                it.commandArgs.joinToString(" ").lowercase().contains(lower)
          }
      )
    }
    (run.tasks.adapter as? FlutterTasksAdapter)?.notifyDataSetChanged()
  }

  private fun setDisplayedChild(child: Int) {
    displayedChild = child
    val transition = MaterialSharedAxis(MaterialSharedAxis.X, child > binding.flipper.displayedChild)
    TransitionManager.beginDelayedTransition(binding.root, transition)
    binding.flipper.displayedChild = child
  }

  private fun executeSelectedTask(task: FlutterTask) {
    val activity = activity as? EditorHandlerActivity ?: return
    val projectDir = IProjectManager.getInstance().projectDir ?: return

    if (!FlutterProjectHelper.isFlutterInstalled()) {
      promptInstallFlutter(activity)
      return
    }

    if (FlutterBuildManager.isBuildInProgress) {
      activity.flashError("A Flutter build/task is already in progress.")
      return
    }

    activity.lifecycleScope.launch(Dispatchers.Main) {
      activity.saveAllResult()
      activity.editorViewModel.isBuildInProgress = true
      activity.content.bottomSheet.clearBuildOutput()
      activity.appendBuildOutput(">>> Starting Flutter Task: ${task.name}")
      activity.appendBuildOutput(">>> flutter ${task.commandArgs.joinToString(" ")}")
      activity.setStatus("Running ${task.name}...")

      // Start via Foreground Service to ensure wakefulness & process resilience
      try {
        val serviceIntent = Intent(activity, FlutterBuildService::class.java).apply {
          putExtra(FlutterBuildService.EXTRA_PROJECT_DIR, projectDir.absolutePath)
          putExtra(FlutterBuildService.EXTRA_TASK_COMMAND, task.commandArgs.toTypedArray())
        }
        activity.startForegroundService(serviceIntent)
      } catch (e: Exception) {
        log.warn("Failed to start FlutterBuildService via intent, running via FlutterBuildManager: {}", e.message)
        FlutterBuildManager.startBuild(projectDir, task.commandArgs)
      }
    }
  }

  private fun getWindowHeight(): Int {
    val height =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          activity?.windowManager?.currentWindowMetrics?.bounds?.height() ?: 1200
        } else {
          val displayMetrics = DisplayMetrics()
          activity?.windowManager?.defaultDisplay?.getMetrics(displayMetrics)
          displayMetrics.heightPixels
        }
    return height
  }

  private class FlutterTasksAdapter(
      private val tasks: List<FlutterTask>,
      private var selected: FlutterTask?,
      private val onTaskClicked: (FlutterTask) -> Unit
  ) : RecyclerView.Adapter<FlutterTasksAdapter.VH>() {

    class VH(val binding: LayoutRunTaskItemBinding) : RecyclerView.ViewHolder(binding.root)

    fun updateSelected(task: FlutterTask?) {
      selected = task
      notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
      val inflater = LayoutInflater.from(parent.context)
      return VH(LayoutRunTaskItemBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
      val task = tasks[position]
      val isSelected = selected?.id == task.id
      holder.binding.check.isChecked = isSelected
      holder.binding.taskPath.text = "${task.name} [${task.category.title}]"
      holder.binding.taskDesc.text = task.description

      holder.binding.root.setOnClickListener {
        onTaskClicked(task)
      }
    }

    override fun getItemCount(): Int = tasks.size
  }
}
