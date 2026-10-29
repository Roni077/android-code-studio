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
    return bin != null && bin.exists()
  }

  /**
   * Configures a Flutter project's Android files for building on-device with Android Code Studio.
   * Injects the native AAPT2 override and ARM64 ABI filter.
   */
  @JvmStatic
  fun configureProjectForAndroidCS(projectDir: File): Boolean {
    val androidDir = File(projectDir, "android")
    if (!androidDir.exists() || !androidDir.isDirectory) {
      log.warn("Not an Android-enabled Flutter project: {}", projectDir)
      return false
    }

    try {
      configureGradleProperties(androidDir)
      configureAppGradle(androidDir)
      return true
    } catch (e: Exception) {
      log.error("Failed to configure Flutter project for AndroidCS", e)
      return false
    }
  }

  private fun configureGradleProperties(androidDir: File) {
    val gradleProps = File(androidDir, "gradle.properties")
    val aapt2Path = File(Environment.PREFIX, "bin/aapt2").absolutePath
    val overrideEntry = "android.aapt2FromMavenOverride=$aapt2Path"

    if (!gradleProps.exists()) {
      gradleProps.writeText("$overrideEntry\n")
      log.info("Created gradle.properties with AAPT2 override: {}", aapt2Path)
      return
    }

    val content = gradleProps.readText()
    if (!content.contains("android.aapt2FromMavenOverride")) {
      File(androidDir, "gradle.properties.bak").writeText(content)
      gradleProps.appendText("\n$overrideEntry\n")
      log.info("Appended AAPT2 override to gradle.properties: {}", aapt2Path)
    } else {
      // Replace existing entry to ensure current PREFIX is used
      val updated = content.lines().joinToString("\n") { line ->
        if (line.trim().startsWith("android.aapt2FromMavenOverride")) overrideEntry else line
      }
      if (updated != content) {
        File(androidDir, "gradle.properties.bak").writeText(content)
        gradleProps.writeText(updated)
        log.info("Updated AAPT2 override in gradle.properties: {}", aapt2Path)
      }
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

    val content = targetFile.readText()
    val isKts = targetFile.name.endsWith(".kts")

    // Check if arm64-v8a filter is already configured
    if (!content.contains("arm64-v8a")) {
      File(appDir, "${targetFile.name}.bak").writeText(content)

      // Add abiFilters into defaultConfig.ndk if possible
      val abiLine = if (isKts) {
        "        ndk {\n            abiFilters += listOf(\"arm64-v8a\")\n        }\n"
      } else {
        "        ndk {\n            abiFilters 'arm64-v8a'\n        }\n"
      }

      if (content.contains("defaultConfig {")) {
        val updated = content.replaceFirst("defaultConfig {", "defaultConfig {\n$abiLine")
        targetFile.writeText(updated)
        log.info("Injected arm64-v8a ABI filter into {}", targetFile.name)
      }
    }
  }
}
