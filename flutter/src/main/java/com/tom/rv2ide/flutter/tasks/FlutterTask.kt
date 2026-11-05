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

package com.tom.rv2ide.flutter.tasks

enum class FlutterTaskCategory(val title: String) {
  BUILD("Build & Assemble"),
  DEPENDENCIES("Dependencies (Pub)"),
  CODE_QUALITY("Analysis & Testing"),
  SYSTEM("Maintenance & Diagnostics")
}

data class FlutterTask(
    val id: String,
    val name: String,
    val description: String,
    val category: FlutterTaskCategory,
    val commandArgs: List<String>,
    val isBuildTask: Boolean = false
)

object FlutterTaskRegistry {

  fun getAvailableTasks(): List<FlutterTask> = listOf(
      // Build tasks
      FlutterTask(
          id = "flutter:build_debug",
          name = "Build Debug APK",
          description = "Assemble Android debug APK with arm64 support",
          category = FlutterTaskCategory.BUILD,
          commandArgs = listOf("build", "apk", "--debug", "--no-tree-shake-icons"),
          isBuildTask = true
      ),
      FlutterTask(
          id = "flutter:build_release",
          name = "Build Release APK",
          description = "Assemble optimized release APK for android-arm64",
          category = FlutterTaskCategory.BUILD,
          commandArgs = listOf("build", "apk", "--release", "--target-platform", "android-arm64", "--no-tree-shake-icons"),
          isBuildTask = true
      ),
      FlutterTask(
          id = "flutter:build_aab",
          name = "Build App Bundle (AAB)",
          description = "Assemble release Android App Bundle for deployment",
          category = FlutterTaskCategory.BUILD,
          commandArgs = listOf("build", "appbundle", "--release", "--target-platform", "android-arm64", "--no-tree-shake-icons"),
          isBuildTask = true
      ),

      // Pub tasks
      FlutterTask(
          id = "flutter:pub_get",
          name = "flutter pub get",
          description = "Download and resolve all packages listed in pubspec.yaml",
          category = FlutterTaskCategory.DEPENDENCIES,
          commandArgs = listOf("pub", "get")
      ),
      FlutterTask(
          id = "flutter:pub_upgrade",
          name = "flutter pub upgrade",
          description = "Upgrade packages to the highest allowable semver versions",
          category = FlutterTaskCategory.DEPENDENCIES,
          commandArgs = listOf("pub", "upgrade")
      ),

      // Code Quality
      FlutterTask(
          id = "flutter:analyze",
          name = "flutter analyze",
          description = "Run Dart static analysis to inspect code for errors and warnings",
          category = FlutterTaskCategory.CODE_QUALITY,
          commandArgs = listOf("analyze")
      ),
      FlutterTask(
          id = "flutter:test",
          name = "flutter test",
          description = "Run all unit and widget tests defined in the test/ directory",
          category = FlutterTaskCategory.CODE_QUALITY,
          commandArgs = listOf("test")
      ),

      // Maintenance
      FlutterTask(
          id = "flutter:clean",
          name = "flutter clean",
          description = "Purge generated build files, intermediate caches and .dart_tool",
          category = FlutterTaskCategory.SYSTEM,
          commandArgs = listOf("clean")
      ),
      FlutterTask(
          id = "flutter:doctor",
          name = "flutter doctor -v",
          description = "Output comprehensive diagnosis of on-device toolchain status",
          category = FlutterTaskCategory.SYSTEM,
          commandArgs = listOf("doctor", "-v")
      )
  )
}
