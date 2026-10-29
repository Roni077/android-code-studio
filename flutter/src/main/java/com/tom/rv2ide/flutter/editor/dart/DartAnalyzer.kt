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

import com.tom.rv2ide.syntax.colorschemes.SchemeAndroidIDE
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager.LineTokenizeResult
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content

class DartToken(val type: TokenType, val startIndex: Int, val length: Int)

enum class TokenType {
  NORMAL,
  KEYWORD,
  WIDGET,
  STRING,
  COMMENT,
  NUMBER,
  ANNOTATION,
  OPERATOR
}

class DartAnalyzer : AsyncIncrementalAnalyzeManager<Int, DartToken>() {

  companion object {
    const val STATE_NORMAL = 0
    const val STATE_BLOCK_COMMENT = 1

    private val KEYWORDS_SET = DartSnippets.KEYWORDS.toHashSet()
    private val WIDGETS_SET = DartSnippets.FLUTTER_WIDGETS.toHashSet()
  }

  override fun getInitialState(): Int = STATE_NORMAL

  override fun stateEquals(state: Int?, another: Int?): Boolean = state == another

  override fun computeBlocks(
      text: Content,
      delegate: CodeBlockAnalyzeDelegate
  ): List<CodeBlock> = emptyList()

  override fun tokenizeLine(lineText: CharSequence, state: Int, line: Int): LineTokenizeResult<Int, DartToken> {
    val tokens = mutableListOf<DartToken>()
    var currentState = state
    val len = lineText.length
    var i = 0

    while (i < len) {
      if (currentState == STATE_BLOCK_COMMENT) {
        val endIdx = lineText.indexOf("*/", i)
        if (endIdx != -1) {
          tokens.add(DartToken(TokenType.COMMENT, i, (endIdx + 2) - i))
          i = endIdx + 2
          currentState = STATE_NORMAL
        } else {
          tokens.add(DartToken(TokenType.COMMENT, i, len - i))
          i = len
        }
        continue
      }

      val ch = lineText[i]

      // Whitespace
      if (ch.isWhitespace()) {
        val start = i
        while (i < len && lineText[i].isWhitespace()) i++
        tokens.add(DartToken(TokenType.NORMAL, start, i - start))
        continue
      }

      // Single line and block comments
      if (ch == '/' && i + 1 < len) {
        if (lineText[i + 1] == '/') {
          tokens.add(DartToken(TokenType.COMMENT, i, len - i))
          i = len
          continue
        } else if (lineText[i + 1] == '*') {
          val endIdx = lineText.indexOf("*/", i + 2)
          if (endIdx != -1) {
            tokens.add(DartToken(TokenType.COMMENT, i, (endIdx + 2) - i))
            i = endIdx + 2
          } else {
            tokens.add(DartToken(TokenType.COMMENT, i, len - i))
            i = len
            currentState = STATE_BLOCK_COMMENT
          }
          continue
        }
      }

      // Strings ('...' or "...")
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
        tokens.add(DartToken(TokenType.STRING, start, i - start))
        continue
      }

      // Annotations (@override, etc.)
      if (ch == '@') {
        val start = i
        i++
        while (i < len && (lineText[i].isLetterOrDigit() || lineText[i] == '_')) i++
        tokens.add(DartToken(TokenType.ANNOTATION, start, i - start))
        continue
      }

      // Numbers
      if (ch.isDigit()) {
        val start = i
        while (i < len && (lineText[i].isLetterOrDigit() || lineText[i] == '.')) i++
        tokens.add(DartToken(TokenType.NUMBER, start, i - start))
        continue
      }

      // Identifiers / Keywords / Widgets
      if (ch.isLetter() || ch == '_') {
        val start = i
        while (i < len && (lineText[i].isLetterOrDigit() || lineText[i] == '_')) i++
        val word = lineText.substring(start, i)
        val type = when {
          KEYWORDS_SET.contains(word) -> TokenType.KEYWORD
          WIDGETS_SET.contains(word) -> TokenType.WIDGET
          else -> TokenType.NORMAL
        }
        tokens.add(DartToken(type, start, i - start))
        continue
      }

      // Operators and punctuation
      val start = i
      i++
      tokens.add(DartToken(TokenType.OPERATOR, start, 1))
    }

    return LineTokenizeResult(currentState, tokens)
  }

  override fun generateSpansForLine(tokens: LineTokenizeResult<Int, DartToken>): List<Span> {
    val spans = mutableListOf<Span>()
    for (token in tokens.tokens) {
      val style = when (token.type) {
        TokenType.KEYWORD -> SchemeAndroidIDE.forKeyword()
        TokenType.WIDGET -> TextStyle.makeStyle(SchemeAndroidIDE.TYPE_NAME)
        TokenType.STRING -> TextStyle.makeStyle(SchemeAndroidIDE.LITERAL)
        TokenType.COMMENT -> TextStyle.makeStyle(SchemeAndroidIDE.COMMENT)
        TokenType.NUMBER -> TextStyle.makeStyle(SchemeAndroidIDE.LITERAL)
        TokenType.ANNOTATION -> TextStyle.makeStyle(SchemeAndroidIDE.ANNOTATION)
        TokenType.OPERATOR -> TextStyle.makeStyle(SchemeAndroidIDE.OPERATOR)
        TokenType.NORMAL -> TextStyle.makeStyle(SchemeAndroidIDE.TEXT_NORMAL)
      }
      spans.add(SpanFactory.obtain(token.startIndex, style))
    }
    return spans
  }
}
