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

data class FlutterProjectInfo(
    val rootDir: File,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val applicationId: String,
    val isExternalStorage: Boolean,
    val isStoragePatched: Boolean
)

object FlutterProjectSynchronizer {

  private val log = LoggerFactory.getLogger(FlutterProjectSynchronizer::class.java)

  @Volatile private var cachedProjectInfo: FlutterProjectInfo? = null

  /**
   * Synchronizes the Flutter project workspace: parses metadata, verifies external storage patch,
   * ensures native AAPT2 overrides, and extracts application package ID.
   */
  @Synchronized
  fun synchronize(projectDir: File): FlutterProjectInfo? {
    if (!FlutterProjectHelper.isFlutterProject(projectDir)) {
      log.warn("Cannot synchronize: Not a Flutter project: {}", projectDir.absolutePath)
      return null
    }

    // 1. Parse pubspec.yaml
    val pubspecFile = File(projectDir, "pubspec.yaml")
    val pubspec = PubspecParser.parse(pubspecFile)
    val appName = pubspec?.name ?: "flutter_app"
    val versionName = pubspec?.versionName ?: "1.0.0"
    val versionCode = pubspec?.versionCode ?: 1

    // 2. Configure Android subproject (AAPT2 override, arm64-v8a filter, sdk 36)
    FlutterProjectHelper.configureProjectForAndroidCS(projectDir)

    // 3. Ensure external storage patch is active if running from shared storage
    val isExternal = projectDir.absolutePath.startsWith("/storage/emulated/0") ||
        projectDir.absolutePath.startsWith("/sdcard")
    val isPatched = ensureStoragePatchApplied()

    // 4. Extract applicationId
    val appId = extractApplicationId(projectDir) ?: "com.example.$appName"

    val info = FlutterProjectInfo(
        rootDir = projectDir,
        name = appName,
        versionName = versionName,
        versionCode = versionCode,
        applicationId = appId,
        isExternalStorage = isExternal,
        isStoragePatched = isPatched
    )
    cachedProjectInfo = info
    log.info("Flutter project synchronized: {} (ID: {}, v{}+{})", appName, appId, versionName, versionCode)
    return info
  }

  fun getCachedProjectInfo(): FlutterProjectInfo? = cachedProjectInfo

  fun getApplicationId(projectDir: File): String? {
    cachedProjectInfo?.let {
      if (it.rootDir.absolutePath == projectDir.absolutePath) {
        return it.applicationId
      }
    }
    return extractApplicationId(projectDir)
  }

  /**
   * Automatically verifies and patches flutter_tools gradle.dart to route execution
   * through bash, bypassing the Linux kernel noexec mount restriction on shared storage.
   */
  fun ensureStoragePatchApplied(): Boolean {
    val flutterRoot = Environment.FLUTTER_HOME ?: return false
    val gradleDart = File(flutterRoot, "packages/flutter_tools/lib/src/android/gradle.dart")
    if (!gradleDart.exists()) {
      return false
    }

    try {
      val content = gradleDart.readText()
      if (content.contains("bypass noexec on external storage") && content.contains("globals.platform")) {
        return true
      }

      val bashBin = File(Environment.PREFIX, "bin/bash").absolutePath
      val target = "    final command = <String>[\n      gradleExecutablePath,"
      val replacement = """    // Patched by Android Code Studio: bypass noexec on external storage
    final command = <String>[
      if ((globals.platform.isLinux || globals.platform.isAndroid) &&
          (globals.platform.environment['PREFIX']?.isNotEmpty ?? false))
        '$bashBin',
      gradleExecutablePath,"""

      if (content.contains(target)) {
        val updated = content.replace(target, replacement)
        gradleDart.writeText(updated)
        log.info("Successfully patched flutter_tools gradle.dart for external storage execution.")
        return true
      }
    } catch (e: Exception) {
      log.warn("Failed to verify/patch gradle.dart: {}", e.message)
    }
    return false
  }

  private fun extractApplicationId(projectDir: File): String? {
    val appDir = File(projectDir, "android/app")
    if (!appDir.exists()) return null

    // Check build.gradle.kts or build.gradle
    val gradleFiles = listOf(File(appDir, "build.gradle.kts"), File(appDir, "build.gradle"))
    for (file in gradleFiles) {
      if (file.exists()) {
        val text = file.readText()
        // Match applicationId = "..." or applicationId "..."
        val appIdRegex = Regex("""applicationId\s*=?\s*["']([^"']+)["']""")
        val match = appIdRegex.find(text)
        if (match != null) {
          return match.groupValues[1]
        }
        // Match namespace = "..." or namespace "..."
        val namespaceRegex = Regex("""namespace\s*=?\s*["']([^"']+)["']""")
        val nsMatch = namespaceRegex.find(text)
        if (nsMatch != null) {
          return nsMatch.groupValues[1]
        }
      }
    }

    // Check AndroidManifest.xml
    val manifest = File(appDir, "src/main/AndroidManifest.xml")
    if (manifest.exists()) {
      val text = manifest.readText()
      val pkgRegex = Regex("""package\s*=\s*["']([^"']+)["']""")
      val pkgMatch = pkgRegex.find(text)
      if (pkgMatch != null) {
        return pkgMatch.groupValues[1]
      }
    }

    return null
  }
}
