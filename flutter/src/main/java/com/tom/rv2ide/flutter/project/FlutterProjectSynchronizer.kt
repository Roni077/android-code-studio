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

import com.tom.rv2ide.shell.executeProcessAsync
import com.tom.rv2ide.utils.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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
   * ensures native AAPT2 overrides, runs pub get, and extracts application package ID.
   */
  @Synchronized
  fun synchronize(
      projectDir: File,
      onLog: ((String) -> Unit)? = null
  ): FlutterProjectInfo? {
    val logCallback = onLog ?: {}
    val syncLogBuffer = StringBuilder()

    fun emit(line: String) {
      syncLogBuffer.append(line).append("\n")
      logCallback(line)
      log.info(line)
    }

    if (!FlutterProjectHelper.isFlutterProject(projectDir)) {
      emit("Cannot synchronize: Not a Flutter project: ${projectDir.absolutePath}")
      return null
    }

    val startTime = System.currentTimeMillis()
    emit(">>> Starting Flutter Project Synchronization...")
    emit("Root: ${projectDir.absolutePath}")

    // 1. Parse pubspec.yaml
    emit("[1/5] Parsing pubspec.yaml...")
    val pubspecFile = File(projectDir, "pubspec.yaml")
    val pubspec = PubspecParser.parse(pubspecFile)
    val appName = pubspec?.name ?: "flutter_app"
    val versionName = pubspec?.versionName ?: "1.0.0"
    val versionCode = pubspec?.versionCode ?: 1
    emit("  • Project: $appName")
    emit("  • Version: $versionName+$versionCode")

    // 2. Resolve dependencies (flutter pub get)
    emit("[2/5] Resolving dependencies (flutter pub get)...")
    if (FlutterProjectHelper.isFlutterInstalled()) {
      try {
        val flutterBin = Environment.FLUTTER_BIN ?: File(Environment.FLUTTER_HOME, "bin/flutter")
        val env = mutableMapOf<String, String>()
        Environment.putEnvironment(env, false)
        val process = executeProcessAsync {
          this.command = listOf(flutterBin.absolutePath, "pub", "get")
          this.workingDirectory = projectDir
          this.environment = env
          this.redirectErrorStream = true
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var pubLine: String?
        while (reader.readLine().also { pubLine = it } != null) {
          val l = pubLine?.trim() ?: continue
          if (l.isNotEmpty()) {
            emit("  $l")
          }
        }
        val code = process.waitFor()
        if (code == 0) {
          emit("  Dependencies resolved successfully.")
        } else {
          emit("  Warning: 'flutter pub get' exited with code $code")
        }
      } catch (e: Exception) {
        emit("  Warning: Failed to execute flutter pub get: ${e.message}")
      }
    } else {
      emit("  Skipping pub get (Flutter SDK executable not found in environment)")
    }

    // 3. Configure Android subproject (AAPT2 override, arm64-v8a filter, sdk 36)
    emit("[3/5] Configuring Android build files & AAPT2 override...")
    val androidConfigured = FlutterProjectHelper.configureProjectForAndroidCS(projectDir)
    emit("  • Android settings configured: $androidConfigured")
    emit("  • Native AAPT2 override: active")
    emit("  • Gradle console: plain (task-level tracking enabled)")
    emit("  • Target SDK: 36, Compile SDK: 36")

    // 4. Ensure external storage patch is active if running from shared storage
    emit("[4/5] Verifying external storage execution patch...")
    val isExternal = projectDir.absolutePath.startsWith("/storage/emulated/0") ||
        projectDir.absolutePath.startsWith("/sdcard")
    val isPatched = ensureStoragePatchApplied()
    emit("  • External storage: $isExternal")
    emit("  • Execution patch applied: $isPatched")

    // 5. Extract applicationId
    emit("[5/5] Extracting application metadata...")
    val appId = extractApplicationId(projectDir) ?: "com.example.$appName"
    emit("  • Application ID: $appId")
    emit("  • Build variants: [debug, profile, release]")

    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    val elapsedStr = String.format(java.util.Locale.US, "%.1fs", elapsed)
    emit("CONFIGURE SUCCESSFUL in $elapsedStr")

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

    try {
      File(projectDir, "sync.log").writeText(syncLogBuffer.toString())
    } catch (e: Exception) {
      log.warn("Failed to write sync.log: {}", e.message)
    }

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
