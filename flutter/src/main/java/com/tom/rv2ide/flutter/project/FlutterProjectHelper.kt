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

import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.FlutterSdkSanitizer
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Helper utility for Flutter projects detection and on-device build configuration.
 */
object FlutterProjectHelper {

  private val log = LoggerFactory.getLogger(FlutterProjectHelper::class.java)

  /**
   * Checks whether the given directory represents a Flutter project.
   */
  @JvmStatic
  fun isFlutterProject(dir: File?): Boolean {
    if (dir == null || !dir.exists() || !dir.isDirectory) {
      return false
    }
    return File(dir, "pubspec.yaml").exists()
  }

  /**
   * Checks whether the Flutter SDK is installed and executable in the IDE environment.
   */
  @JvmStatic
  fun isFlutterInstalled(): Boolean {
    val bin = Environment.FLUTTER_BIN
    if (bin != null && bin.exists()) {
      bin.setExecutable(true, false)
      return true
    }
    return false
  }

  /**
   * Configures a Flutter project's Android files for building on-device with Android Code Studio.
   * Injects the native AAPT2 override and ARM64 ABI filter.
   */
  @JvmStatic
  fun configureProjectForAndroidCS(projectDir: File): Boolean {
    // Ensure on-device Flutter SDK scripts have valid Android shebangs & permissions
    try {
      FlutterSdkSanitizer.sanitize()
    } catch (e: Exception) {
      log.warn("Failed to sanitize Flutter SDK scripts: {}", e.message)
    }

    val androidDir = File(projectDir, "android")
    if (!androidDir.exists() || !androidDir.isDirectory) {
      log.warn("Not an Android-enabled Flutter project: {}", projectDir)
      return false
    }

    try {
      configureGradlew(androidDir)
      configureGradleProperties(androidDir)
      configureAppGradle(androidDir)
      return true
    } catch (e: Exception) {
      log.error("Failed to configure Flutter project for AndroidCS", e)
      return false
    }
  }

  private fun configureGradlew(androidDir: File) {
    val gradlew = File(androidDir, "gradlew")
    if (gradlew.exists()) {
      val bashPath = File(Environment.PREFIX, "bin/bash").absolutePath
      val content = gradlew.readText()
      if (!content.startsWith("#!$bashPath")) {
        val lines = content.lines()
        val updated = "#!$bashPath\n" + (if (lines.isNotEmpty()) lines.drop(1).joinToString("\n") else "")
        gradlew.writeText(updated)
        gradlew.setExecutable(true, false)
        log.info("Updated gradlew shebang to #!{}", bashPath)
      }
    }
  }

  private fun configureGradleProperties(androidDir: File) {
    val gradleProps = File(androidDir, "gradle.properties")
    val aapt2Path = File(Environment.PREFIX, "bin/aapt2").absolutePath
    val overrideEntry = "android.aapt2FromMavenOverride=$aapt2Path"
    val optimizationsEntry = "android.enableResourceOptimizations=false"
    val shrinkEntry = "shrink=false"
    val jvmArgsEntry = "org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"

    if (!gradleProps.exists()) {
      gradleProps.writeText("$overrideEntry\n$optimizationsEntry\n$shrinkEntry\n$jvmArgsEntry\n")
      log.info("Created gradle.properties with AAPT2 override and GeneralKaos666 settings: {}", aapt2Path)
      return
    }

    var content = gradleProps.readText()
    var modified = false

    if (!content.contains("android.aapt2FromMavenOverride")) {
      content += "\n$overrideEntry\n"
      modified = true
    } else {
      val updated = content.lines().joinToString("\n") { line ->
        if (line.trim().startsWith("android.aapt2FromMavenOverride")) overrideEntry else line
      }
      if (updated != content) {
        content = updated
        modified = true
      }
    }

    if (!content.contains("android.enableResourceOptimizations")) {
      content += "$optimizationsEntry\n"
      modified = true
    }
    if (!content.contains("shrink=")) {
      content += "$shrinkEntry\n"
      modified = true
    }
    if (!content.contains("org.gradle.jvmargs")) {
      content += "$jvmArgsEntry\n"
      modified = true
    }

    if (modified) {
      File(androidDir, "gradle.properties.bak").writeText(gradleProps.readText())
      gradleProps.writeText(content)
      log.info("Updated gradle.properties with AAPT2 override & upstream settings: {}", aapt2Path)
    }
  }

  private fun configureAppGradle(androidDir: File) {
    val appDir = File(androidDir, "app")
    val buildGradleKts = File(appDir, "build.gradle.kts")
    val buildGradle = File(appDir, "build.gradle")

    val targetFile = when {
      buildGradleKts.exists() -> buildGradleKts
      buildGradle.exists() -> buildGradle
      else -> return
    }

    var content = targetFile.readText()
    val isKts = targetFile.name.endsWith(".kts")
    var modified = false

    // 1. Add arm64-v8a filter if not present
    if (!content.contains("arm64-v8a")) {
      val abiLine = if (isKts) {
        "        ndk {\n            abiFilters += listOf(\"arm64-v8a\")\n        }\n"
      } else {
        "        ndk {\n            abiFilters 'arm64-v8a'\n        }\n"
      }

      if (content.contains("defaultConfig {")) {
        content = content.replaceFirst("defaultConfig {", "defaultConfig {\n$abiLine")
        modified = true
        log.info("Injected arm64-v8a ABI filter into {}", targetFile.name)
      }
    }

    // 2. Disable minification and shrinking for release (GeneralKaos666 requirement)
    if (!content.contains("isMinifyEnabled") && !content.contains("minifyEnabled false")) {
      val releaseConfig = if (isKts) {
        "            isMinifyEnabled = false\n            isShrinkResources = false\n"
      } else {
        "            minifyEnabled false\n            shrinkResources false\n"
      }

      if (content.contains("buildTypes {")) {
        if (content.contains("release {")) {
          content = content.replaceFirst("release {", "release {\n$releaseConfig")
          modified = true
        } else if (content.contains("getByName(\"release\") {")) {
          content = content.replaceFirst("getByName(\"release\") {", "getByName(\"release\") {\n$releaseConfig")
          modified = true
        }
      }
    }

    if (modified) {
      File(appDir, "${targetFile.name}.bak").writeText(targetFile.readText())
      targetFile.writeText(content)
      log.info("Configured release settings in {}", targetFile.name)
    }
  }
}
