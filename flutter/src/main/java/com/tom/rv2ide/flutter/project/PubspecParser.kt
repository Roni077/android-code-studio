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

package com.tom.rv2ide.flutter.project

import java.io.File
import org.slf4j.LoggerFactory

data class PubspecData(
    val name: String,
    val description: String,
    val versionName: String,
    val versionCode: Int,
    val dependencies: List<String>
)

object PubspecParser {

  private val log = LoggerFactory.getLogger(PubspecParser::class.java)

  fun parse(pubspecFile: File): PubspecData? {
    if (!pubspecFile.exists() || !pubspecFile.isFile) {
      log.warn("pubspec.yaml does not exist at {}", pubspecFile.absolutePath)
      return null
    }

    try {
      val lines = pubspecFile.readLines()
      var name = "flutter_app"
      var description = ""
      var versionName = "1.0.0"
      var versionCode = 1
      val dependencies = mutableListOf<String>()
      var inDependencies = false

      for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || trimmed.isEmpty()) continue

        if (trimmed.startsWith("name:")) {
          name = trimmed.substringAfter("name:").trim()
        } else if (trimmed.startsWith("description:")) {
          description = trimmed.substringAfter("description:").trim().trim('"', '\'')
        } else if (trimmed.startsWith("version:")) {
          val rawVersion = trimmed.substringAfter("version:").trim()
          if (rawVersion.contains("+")) {
            versionName = rawVersion.substringBefore("+").trim()
            versionCode = rawVersion.substringAfter("+").trim().toIntOrNull() ?: 1
          } else {
            versionName = rawVersion
            versionCode = 1
          }
        } else if (line.startsWith("dependencies:")) {
          inDependencies = true
        } else if (inDependencies && !line.startsWith(" ") && !line.startsWith("\t")) {
          inDependencies = false
        } else if (inDependencies) {
          val depName = trimmed.substringBefore(":").trim()
          if (depName.isNotEmpty() && !depName.startsWith("#")) {
            dependencies.add(depName)
          }
        }
      }

      return PubspecData(
          name = name,
          description = description,
          versionName = versionName,
          versionCode = versionCode,
          dependencies = dependencies
      )
    } catch (e: Exception) {
      log.error("Failed to parse pubspec.yaml: {}", e.message)
      return null
    }
  }
}
