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
package com.tom.rv2ide.fragments.output

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ScaleGestureDetector
import android.view.View
import com.blankj.utilcode.util.ThreadUtils
import com.tom.rv2ide.R
import com.tom.rv2ide.eventbus.events.preferences.PreferenceChangeEvent
import com.tom.rv2ide.preferences.internal.BuildPreferences
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.math.roundToInt

class BuildOutputFragment : NonEditableEditorFragment() {
  private val unsavedLines: MutableList<String?> = ArrayList()
  private val pendingOutputBuffer = StringBuilder()
  private val flushHandler = Handler(Looper.getMainLooper())
  private var flushPending = false
  private var currentLineCount = 0
  private var scaleGestureDetector: ScaleGestureDetector? = null
  private var currentFontSize = BuildPreferences.DEFAULT_BUILD_OUTPUT_FONT_SIZE

  companion object {
    private const val MAX_LINES = 10000
    private const val TRIM_TO_LINES = 8000
    private const val FLUSH_DELAY_MS = 150L
    private const val BATCH_SIZE = 100
  }

  private val flushRunnable = Runnable {
    flushPending = false
    flushPendingOutput()
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    emptyStateViewModel.emptyMessage.value = getString(R.string.msg_emptyview_buildoutput)
    editor?.setEditorLanguage(BuildOutputLanguage())
    currentFontSize = BuildPreferences.buildOutputFontSize
    editor?.setTextSize(currentFontSize)
    setupPinchToZoom()
    if (unsavedLines.isNotEmpty()) {
      for (line in unsavedLines) {
        editor?.append("${line!!.trim()}\n")
      }
      unsavedLines.clear()
      editor?.goToEnd()
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun setupPinchToZoom() {
    val ed = editor ?: return
    scaleGestureDetector =
        ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
              private var baseSize = BuildPreferences.DEFAULT_BUILD_OUTPUT_FONT_SIZE
              private var cumulativeScale = 1.0f

              override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                baseSize = BuildPreferences.buildOutputFontSize
                cumulativeScale = 1.0f
                return true
              }

              override fun onScale(detector: ScaleGestureDetector): Boolean {
                cumulativeScale *= detector.scaleFactor
                val targetSize =
                    (baseSize * cumulativeScale).roundToInt().toFloat().coerceIn(6f, 32f)
                if (targetSize != currentFontSize) {
                  currentFontSize = targetSize
                  ed.setTextSize(targetSize)
                }
                return true
              }

              override fun onScaleEnd(detector: ScaleGestureDetector) {
                val finalSize =
                    (baseSize * cumulativeScale).roundToInt().toFloat().coerceIn(6f, 32f)
                currentFontSize = finalSize
                BuildPreferences.buildOutputFontSize = finalSize
              }
            })

    ed.setOnTouchListener { _, event ->
      scaleGestureDetector?.onTouchEvent(event)
      scaleGestureDetector?.isInProgress == true
    }
  }

  override fun onStart() {
    super.onStart()
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }
  }

  override fun onStop() {
    if (EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().unregister(this)
    }
    super.onStop()
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun onPreferenceChanged(event: PreferenceChangeEvent) {
    if (event.key == BuildPreferences.BUILD_OUTPUT_FONT_SIZE) {
      val newSize = BuildPreferences.buildOutputFontSize
      currentFontSize = newSize
      editor?.setTextSize(newSize)
    }
  }

  override fun onDestroyView() {
    flushHandler.removeCallbacks(flushRunnable)
    pendingOutputBuffer.clear()
    editor?.release()
    super.onDestroyView()
  }

  fun appendOutput(output: String?) {
    // Check if build output is disabled
    if (!BuildPreferences.isBuildOutputEnabled) {
      return
    }

    // Filter out unwanted messages
    if (shouldFilterOutput(output)) {
      return
    }

    if (editor == null) {
      unsavedLines.add(output)
      return
    }

    val message =
        if (output == null || output.endsWith("\n")) {
          output ?: ""
        } else {
          "${output}\n"
        }

    synchronized(pendingOutputBuffer) { pendingOutputBuffer.append(message) }

    // Schedule flush if not already pending
    if (!flushPending) {
      flushPending = true
      flushHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS)
    }
  }

  private fun shouldFilterOutput(output: String?): Boolean {
    if (output == null) return false

    val trimmedOutput = output.trim()

    // Filter: "Starting a Gradle Daemon, ...., use --status for details"
    if (trimmedOutput.startsWith("Starting a Gradle Daemon") &&
        trimmedOutput.contains("use --status for details")) {
      return true
    }

    // Filter: WARNING about android.aapt2FromMavenOverride
    if (trimmedOutput.startsWith("WARNING:") &&
        trimmedOutput.contains("android.aapt2FromMavenOverride") &&
        trimmedOutput.contains("is experimental")) {
      return true
    }

    // Filter: Flutter not in a git repository warning
    if (trimmedOutput.startsWith("Warning: Flutter is not in a git repository") ||
        trimmedOutput.contains("unable to determine Flutter version")) {
      return true
    }

    return false
  }

  private fun flushPendingOutput() {
    val textToAppend: String
    synchronized(pendingOutputBuffer) {
      if (pendingOutputBuffer.isEmpty()) {
        return
      }
      textToAppend = pendingOutputBuffer.toString()
      pendingOutputBuffer.clear()
    }

    ThreadUtils.runOnUiThread {
      editor?.let { ed ->
        val newLines = textToAppend.count { it == '\n' }
        currentLineCount += newLines

        // Append in batches to reduce UI operations
        val lines = textToAppend.lines()
        lines.chunked(BATCH_SIZE).forEach { chunk ->
          ed.append(chunk.joinToString("\n") + if (chunk.last().isNotEmpty()) "\n" else "")
        }

        // Trim if exceeds max lines
        if (currentLineCount > MAX_LINES) {
          trimExcessLines()
        }

        // Auto-scroll to end so user sees live build output
        ed.goToEnd()

        emptyStateViewModel.isEmpty.value = false
      }
    }
  }

  private fun trimExcessLines() {
    editor?.let { ed ->
      try {
        val content = ed.text ?: return
        if (content.lineCount > MAX_LINES) {
          val linesToRemove = content.lineCount - TRIM_TO_LINES
          content.delete(0, 0, linesToRemove, 0)
          content.insert(0, 0, "... [${linesToRemove} lines trimmed for performance] ...\n\n")
          currentLineCount = TRIM_TO_LINES
          ed.goToEnd()
        }
      } catch (e: Exception) {
        // Fail silently if trimming doesn't work
      }
    }
  }
}
