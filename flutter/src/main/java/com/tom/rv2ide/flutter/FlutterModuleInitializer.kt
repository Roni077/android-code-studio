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

package com.tom.rv2ide.flutter

import com.tom.rv2ide.editor.language.LanguageRegistry
import com.tom.rv2ide.flutter.editor.dart.DartLanguage
import com.tom.rv2ide.flutter.editor.yaml.YamlLanguage

/**
 * Initializes the Flutter module and registers language providers.
 */
object FlutterModuleInitializer {

  private var initialized = false

  @JvmStatic
  fun init() {
    if (initialized) return
    initialized = true

    // Register Dart & YAML languages into the editor LanguageRegistry
    LanguageRegistry.register("dart") { DartLanguage() }
    LanguageRegistry.register("yaml") { YamlLanguage() }
    LanguageRegistry.register("yml") { YamlLanguage() }
  }
}
