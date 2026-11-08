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

package com.tom.rv2ide.fragments.output

import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager.LineTokenizeResult
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content

/**
 * High-performance incremental analyzer that tokenizes and styles build output lines
 * for Gradle and Flutter builds.
 */
class BuildOutputAnalyzer : AsyncIncrementalAnalyzeManager<Int, Span>() {

  override fun getInitialState(): Int = 0

  override fun stateEquals(state: Int, another: Int): Boolean = state == another

  override fun tokenizeLine(
      lineText: CharSequence,
      state: Int,
      line: Int,
  ): LineTokenizeResult<Int, Span> {
    val text = lineText.toString()
    val trimmed = text.trim()

    val style = when {
      trimmed.isEmpty() -> TextStyle.makeStyle(SchemeAndroidIDE.TEXT_NORMAL)

      // Success messages (Green + Bold)
      trimmed.contains("BUILD SUCCESSFUL") ||
      trimmed.startsWith("✓ Built") ||
      trimmed.contains("Flutter build finished successfully") ||
      trimmed.contains("App installed successfully") -> {
        TextStyle.makeStyle(SchemeAndroidIDE.LOG_TEXT_INFO, 0, true, false, false)
      }

      // Errors and Failures (Red + Bold)
      trimmed.contains("BUILD FAILED") ||
      trimmed.startsWith("FAILURE:") ||
      trimmed.startsWith("Error:") ||
      trimmed.contains(": Error: ") ||
      trimmed.startsWith("e:") ||
      trimmed.startsWith("Exception") ||
      trimmed.contains("Exception in thread") ||
      trimmed.startsWith("Fatal") ||
      trimmed.startsWith("Execution failed for task") -> {
        TextStyle.makeStyle(SchemeAndroidIDE.LOG_TEXT_ERROR, 0, true, false, false)
      }

      // Warnings (Yellow)
      trimmed.startsWith("w:") ||
      trimmed.startsWith("WARNING:") ||
      trimmed.startsWith("Warning:") ||
      trimmed.contains("Warning: ") -> {
        TextStyle.makeStyle(SchemeAndroidIDE.LOG_TEXT_WARNING)
      }

      // Tasks and Major Steps (Cyan + Bold)
      trimmed.startsWith("> Task :") ||
      trimmed.startsWith("> Configure project") ||
      trimmed.startsWith("Run tasks :") ||
      trimmed.startsWith("Running Gradle task") ||
      trimmed.startsWith(">>> Starting") ||
      trimmed.startsWith(">>> Validating") ||
      trimmed.startsWith("Resolving dependencies") ||
      trimmed.startsWith("Running \"flutter") -> {
        TextStyle.makeStyle(SchemeAndroidIDE.LOG_TEXT_DEBUG, 0, true, false, false)
      }

      // Verification and Actionable Task Summaries (Bold Normal or Info)
      trimmed.startsWith("APK:") ||
      trimmed.startsWith("Size:") ||
      trimmed.startsWith("Variant:") ||
      trimmed.startsWith("ABI:") ||
      trimmed.contains("actionable tasks:") -> {
        TextStyle.makeStyle(SchemeAndroidIDE.LOG_TEXT_INFO)
      }

      // Performance trim notices (Gray Comment)
      trimmed.startsWith("... [") -> {
        TextStyle.makeStyle(SchemeAndroidIDE.COMMENT)
      }

      else -> TextStyle.makeStyle(SchemeAndroidIDE.TEXT_NORMAL)
    }

    val spanList = mutableListOf(Span.obtain(0, style))
    return LineTokenizeResult(0, spanList, spanList)
  }

  override fun generateSpansForLine(tokens: LineTokenizeResult<Int, Span>): List<Span> {
    return tokens.spans ?: tokens.tokens ?: listOf(Span.obtain(0, TextStyle.makeStyle(SchemeAndroidIDE.TEXT_NORMAL)))
  }

  override fun computeBlocks(
      text: Content,
      delegate: AsyncIncrementalAnalyzeManager<Int, Span>.CodeBlockAnalyzeDelegate,
  ): List<CodeBlock> {
    return emptyList()
  }
}
