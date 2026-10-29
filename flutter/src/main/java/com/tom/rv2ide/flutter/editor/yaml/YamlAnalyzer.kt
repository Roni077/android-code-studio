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

import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager.LineTokenizeResult
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content

class YamlToken(val type: YamlTokenType, val startIndex: Int, val length: Int)

enum class YamlTokenType {
  NORMAL,
  KEY,
  STRING,
  COMMENT,
  NUMBER,
  OPERATOR
}

class YamlAnalyzer : AsyncIncrementalAnalyzeManager<Int, YamlToken>() {

  override fun getInitialState(): Int = 0

  override fun stateEquals(state: Int?, another: Int?): Boolean = state == another

  override fun computeBlocks(
      text: Content,
      delegate: CodeBlockAnalyzeDelegate
  ): List<CodeBlock> = emptyList()

  override fun tokenizeLine(lineText: CharSequence, state: Int, line: Int): LineTokenizeResult<Int, YamlToken> {
    val tokens = mutableListOf<YamlToken>()
    val len = lineText.length
    var i = 0

    while (i < len) {
      val ch = lineText[i]

      // Whitespace
      if (ch.isWhitespace()) {
        val start = i
        while (i < len && lineText[i].isWhitespace()) i++
        tokens.add(YamlToken(YamlTokenType.NORMAL, start, i - start))
        continue
      }

      // Comments (# ...)
      if (ch == '#') {
        tokens.add(YamlToken(YamlTokenType.COMMENT, i, len - i))
        i = len
        continue
      }

      // Strings
      if (ch == '\'' || ch == '"') {
        val quote = ch
        val start = i
        i++
        while (i < len) {
          if (lineText[i] == '\\' && i + 1 < len) {
            i += 2
          } else if (lineText[i] == quote) {
            i++
            break
          } else {
            i++
          }
        }
        tokens.add(YamlToken(YamlTokenType.STRING, start, i - start))
        continue
      }

      // Numbers
      if (ch.isDigit()) {
        val start = i
        while (i < len && (lineText[i].isDigit() || lineText[i] == '.')) i++
        tokens.add(YamlToken(YamlTokenType.NUMBER, start, i - start))
        continue
      }

      // Keys (text before colon :)
      val colonIdx = lineText.indexOf(':', i)
      if (colonIdx != -1 && (colonIdx + 1 == len || lineText[colonIdx + 1].isWhitespace())) {
        val keyPart = lineText.substring(i, colonIdx)
        if (!keyPart.contains('#')) {
          tokens.add(YamlToken(YamlTokenType.KEY, i, colonIdx - i))
          tokens.add(YamlToken(YamlTokenType.OPERATOR, colonIdx, 1))
          i = colonIdx + 1
          continue
        }
      }

      // Regular text / operators
      val start = i
      i++
      tokens.add(YamlToken(YamlTokenType.NORMAL, start, 1))
    }

    return LineTokenizeResult(0, tokens)
  }

  override fun generateSpansForLine(tokens: LineTokenizeResult<Int, YamlToken>): List<Span> {
    val spans = mutableListOf<Span>()
    for (token in tokens.tokens) {
      val style = when (token.type) {
        YamlTokenType.KEY -> SchemeAndroidIDE.forKeyword()
        YamlTokenType.STRING -> TextStyle.makeStyle(SchemeAndroidIDE.LITERAL)
        YamlTokenType.COMMENT -> TextStyle.makeStyle(SchemeAndroidIDE.COMMENT)
        YamlTokenType.NUMBER -> TextStyle.makeStyle(SchemeAndroidIDE.LITERAL)
        YamlTokenType.OPERATOR -> TextStyle.makeStyle(SchemeAndroidIDE.OPERATOR)
        YamlTokenType.NORMAL -> TextStyle.makeStyle(SchemeAndroidIDE.TEXT_NORMAL)
      }
      spans.add(SpanFactory.obtain(token.startIndex, style))
    }
    return spans
  }
}
