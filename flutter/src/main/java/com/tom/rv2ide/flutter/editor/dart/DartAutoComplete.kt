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
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.util.MyCharacter
import java.util.Locale

class DartAutoComplete {

  fun complete(
      content: ContentReference,
      position: CharPosition,
      publisher: CompletionPublisher,
      extraArguments: Bundle? = null
  ) {
    val prefix = CompletionHelper.computePrefix(content, position) {
      MyCharacter.isJavaIdentifierPart(it)
    }

    if (prefix.isNullOrEmpty()) {
      return
    }

    val lowerPrefix = prefix.lowercase(Locale.ROOT)

    // 1. Flutter snippets
    for (snippet in DartSnippets.SNIPPETS) {
      if (snippet.label.toString().lowercase(Locale.ROOT).startsWith(lowerPrefix)) {
        publisher.addItem(snippet)
      }
    }

    // 2. Flutter widgets
    for (widget in DartSnippets.FLUTTER_WIDGETS) {
      if (widget.lowercase(Locale.ROOT).startsWith(lowerPrefix)) {
        publisher.addItem(
            SimpleCompletionItem(
                widget,
                "Flutter Widget",
                prefix.length,
                widget
            )
        )
      }
    }

    // 3. Dart keywords
    for (kw in DartSnippets.KEYWORDS) {
      if (kw.lowercase(Locale.ROOT).startsWith(lowerPrefix)) {
        publisher.addItem(
            SimpleCompletionItem(
                kw,
                "Keyword",
                prefix.length,
                kw
            )
        )
      }
    }
  }
}
