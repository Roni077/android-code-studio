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

package com.tom.rv2ide.flutter.editor.yaml

import com.tom.rv2ide.editor.language.IDELanguage
import com.tom.rv2ide.editor.language.utils.CommonSymbolPairs
import io.github.rosemoe.sora.lang.Language.INTERRUPTION_LEVEL_STRONG
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.widget.SymbolPairMatch

class YamlLanguage : IDELanguage() {

  private val analyzer = YamlAnalyzer()

  override fun getAnalyzeManager(): AnalyzeManager {
    return analyzer
  }

  override fun getInterruptionLevel(): Int {
    return INTERRUPTION_LEVEL_STRONG
  }

  override fun getNewlineHandlers(): Array<NewlineHandler> {
    return emptyArray()
  }

  override fun getSymbolPairs(): SymbolPairMatch {
    return CommonSymbolPairs()
  }

  override fun destroy() {
  }
}
