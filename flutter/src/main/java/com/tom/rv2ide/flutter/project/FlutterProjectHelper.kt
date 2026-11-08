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
    // Ensure temporary directory exists and has correct permissions
    try {
      Environment.ensureTmpDir()
    } catch (e: Exception) {
      log.warn("Failed to ensure temporary directory: {}", e.message)
    }

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
      configureGradleWrapper(androidDir)
      configureGradleProperties(androidDir)
      configureSettingsGradle(androidDir)
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

  private fun configureGradleWrapper(androidDir: File) {
    val wrapperProps = File(androidDir, "gradle/wrapper/gradle-wrapper.properties")
    if (!wrapperProps.exists()) return
    var content = wrapperProps.readText()
    var modified = false

    val timeoutRegex = Regex("""networkTimeout\s*=\s*([0-9]+)""")
    val match = timeoutRegex.find(content)
    if (match != null) {
      val currentTimeout = match.groupValues[1].toIntOrNull() ?: 10000
      if (currentTimeout < 60000) {
        content = content.replaceFirst(timeoutRegex, "networkTimeout=60000")
        modified = true
      }
    } else {
      content += "\nnetworkTimeout=60000\n"
      modified = true
    }

    if (modified) {
      wrapperProps.writeText(content)
      log.info("Updated Gradle wrapper networkTimeout to 60000ms")
    }
  }

  private fun configureGradleProperties(androidDir: File) {
    val gradleProps = File(androidDir, "gradle.properties")
    val aapt2Path = File(Environment.PREFIX, "bin/aapt2").absolutePath
    val overrideEntry = "android.aapt2FromMavenOverride=$aapt2Path"
    val optimizationsEntry = "android.enableResourceOptimizations=false"
    val shrinkEntry = "shrink=false"
    val jvmArgsEntry = "org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
    val inProcessEntry = "kotlin.compiler.execution.strategy=in-process"
    val newDslEntry = "android.newDsl=false"
    val builtInKotlinEntry = "android.builtInKotlin=false"

    val consolePlainEntry = "org.gradle.console=plain"

    if (!gradleProps.exists()) {
      gradleProps.writeText("$overrideEntry\n$optimizationsEntry\n$shrinkEntry\n$jvmArgsEntry\n$consolePlainEntry\n$newDslEntry\n$builtInKotlinEntry\n$inProcessEntry\n")
      log.info("Created gradle.properties with AAPT2 override and AndroidCS settings: {}", aapt2Path)
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

    if (!content.contains("org.gradle.console")) {
      content += "$consolePlainEntry\n"
      modified = true
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
    if (!content.contains("kotlin.compiler.execution.strategy")) {
      content += "$inProcessEntry\n"
      modified = true
    }
    if (!content.contains("android.newDsl")) {
      content += "$newDslEntry\n"
      modified = true
    }
    if (!content.contains("android.builtInKotlin")) {
      content += "$builtInKotlinEntry\n"
      modified = true
    }

    if (modified) {
      File(androidDir, "gradle.properties.bak").writeText(gradleProps.readText())
      gradleProps.writeText(content)
      log.info("Updated gradle.properties with AAPT2 override & upstream settings: {}", aapt2Path)
    }
  }

  private fun configureSettingsGradle(androidDir: File) {
    val settingsKts = File(androidDir, "settings.gradle.kts")
    val settingsGroovy = File(androidDir, "settings.gradle")
    val targetFile = when {
      settingsKts.exists() -> settingsKts
      settingsGroovy.exists() -> settingsGroovy
      else -> return
    }

    var content = targetFile.readText()
    var modified = false

    // Upgrade AGP if < 9.1.0
    val agpRegex = Regex("""id\(?["']com\.android\.application["']\)?\s+version\s+["']([0-9.]+)["']""")
    val agpMatch = agpRegex.find(content)
    if (agpMatch != null) {
      val currentAgp = agpMatch.groupValues[1]
      if (isVersionLower(currentAgp, "9.1.0")) {
        val oldStr = agpMatch.value
        val newStr = oldStr.replace(currentAgp, "9.1.0")
        content = content.replace(oldStr, newStr)
        modified = true
        log.info("Auto-upgraded AGP from {} to 9.1.0 in {}", currentAgp, targetFile.name)
      }
    }

    // Upgrade Kotlin if < 2.4.0
    val kotlinRegex = Regex("""id\(?["']org\.jetbrains\.kotlin\.android["']\)?\s+version\s+["']([0-9.]+)["']""")
    val kotlinMatch = kotlinRegex.find(content)
    if (kotlinMatch != null) {
      val currentKotlin = kotlinMatch.groupValues[1]
      if (isVersionLower(currentKotlin, "2.4.0")) {
        val oldStr = kotlinMatch.value
        val newStr = oldStr.replace(currentKotlin, "2.4.0")
        content = content.replace(oldStr, newStr)
        modified = true
        log.info("Auto-upgraded Kotlin from {} to 2.4.0 in {}", currentKotlin, targetFile.name)
      }
    }

    if (modified) {
      File(androidDir, "${targetFile.name}.bak").writeText(targetFile.readText())
      targetFile.writeText(content)
      log.info("Upgraded build dependencies in {}", targetFile.name)
    }
  }

  private fun isVersionLower(current: String, target: String): Boolean {
    val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
    val targetParts = target.split(".").mapNotNull { it.toIntOrNull() }
    val maxLen = maxOf(currentParts.size, targetParts.size)
    for (i in 0 until maxLen) {
      val c = currentParts.getOrElse(i) { 0 }
      val t = targetParts.getOrElse(i) { 0 }
      if (c < t) return true
      if (c > t) return false
    }
    return false
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

    // 3. Ensure compileSdk and targetSdk are 36 for AAPT2 16.0.0.4 compatibility
    val compileSdkRegex = Regex("""compileSdk\s*=?\s*([0-9]+|flutter\.compileSdkVersion)""")
    if (compileSdkRegex.containsMatchIn(content)) {
      val match = compileSdkRegex.find(content)?.value ?: ""
      val replacement = if (isKts) "compileSdk = 36" else "compileSdk 36"
      if (match != replacement) {
        content = content.replaceFirst(compileSdkRegex, replacement)
        modified = true
        log.info("Updated compileSdk to 36 in {}", targetFile.name)
      }
    }

    val targetSdkRegex = Regex("""(targetSdk|targetSdkVersion)\s*=?\s*([0-9]+|flutter\.targetSdkVersion)""")
    if (targetSdkRegex.containsMatchIn(content)) {
      val match = targetSdkRegex.find(content)?.value ?: ""
      val replacement = if (isKts) "targetSdk = 36" else "targetSdkVersion 36"
      if (match != replacement) {
        content = content.replaceFirst(targetSdkRegex, replacement)
        modified = true
        log.info("Updated targetSdk to 36 in {}", targetFile.name)
      }
    }

    if (modified) {
      File(appDir, "${targetFile.name}.bak").writeText(targetFile.readText())
      targetFile.writeText(content)
      log.info("Configured release settings in {}", targetFile.name)
    }
  }
}
