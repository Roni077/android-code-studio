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

package com.tom.rv2ide.editor.language

import io.github.rosemoe.sora.lang.Language

/**
 * Extensible registry for non-TreeSitter languages.
 * Allows external modules (such as :flutter) to register language implementations
 * for specific file extensions dynamically.
 */
object LanguageRegistry {

  private val providers = mutableMapOf<String, () -> Language>()

  @JvmStatic
  fun register(extension: String, factory: () -> Language) {
    providers[extension.lowercase()] = factory
  }

  @JvmStatic
  fun hasLanguage(extension: String): Boolean {
    return providers.containsKey(extension.lowercase())
  }

  @JvmStatic
  fun forExtension(extension: String): Language? {
    return providers[extension.lowercase()]?.invoke()
  }

  @JvmStatic
  fun clear() {
    providers.clear()
  }
}
