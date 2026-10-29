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

package com.tom.rv2ide.flutter.editor.dart

import android.os.Bundle
import com.tom.rv2ide.editor.language.IDELanguage
import com.tom.rv2ide.editor.language.newline.BracketsNewlineHandler
import com.tom.rv2ide.editor.language.utils.CommonSymbolPairs
import io.github.rosemoe.sora.lang.Language.INTERRUPTION_LEVEL_STRONG
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch

class DartLanguage : IDELanguage() {

  private val analyzer = DartAnalyzer()
  private val completer = DartAutoComplete()
  private val newlineHandlers = arrayOf<NewlineHandler>(
      BracketsNewlineHandler({ line -> getIndentAdvance(line ?: "") }, this::useTab)
  )
  private val symbolPairs = CommonSymbolPairs()

  override fun getAnalyzeManager(): AnalyzeManager {
    return analyzer
  }

  override fun getInterruptionLevel(): Int {
    return INTERRUPTION_LEVEL_STRONG
  }

  @Throws(CompletionCancelledException::class)
  override fun requireAutoComplete(
      content: ContentReference,
      position: CharPosition,
      publisher: CompletionPublisher,
      extraArguments: Bundle
  ) {
    completer.complete(content, position, publisher, extraArguments)
  }

  override fun getNewlineHandlers(): Array<NewlineHandler> {
    return newlineHandlers
  }

  override fun getSymbolPairs(): SymbolPairMatch {
    return symbolPairs
  }

  override fun checkIsCompletionChar(c: Char): Boolean {
    return c == '.' || c == '(' || c == ':' || c.isLetterOrDigit()
  }

  override fun getIndentAdvance(line: String): Int {
    val trimmed = line.trimEnd()
    return if (trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[")) {
      getTabSize()
    } else {
      0
    }
  }

  override fun destroy() {
  }
}
