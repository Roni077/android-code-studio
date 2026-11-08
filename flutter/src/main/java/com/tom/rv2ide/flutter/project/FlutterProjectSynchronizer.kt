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

import com.tom.rv2ide.flutter.sdk.AndroidSdkValidator
import com.tom.rv2ide.shell.executeProcessAsync
import com.tom.rv2ide.utils.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
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

data class FlutterPreparationResult(
    val isSuccessful: Boolean,
    val projectInfo: FlutterProjectInfo?,
    val errorMessage: String? = null,
)

object FlutterProjectSynchronizer {

  private val log = LoggerFactory.getLogger(FlutterProjectSynchronizer::class.java)

  @Volatile private var cachedProjectInfo: FlutterProjectInfo? = null
  private val syncLock = Any()

  // Cache fingerprints for avoiding unnecessary flutter pub get executions
  private val pubspecTimestamps = ConcurrentHashMap<String, Long>()
  private val pubspecLockTimestamps = ConcurrentHashMap<String, Long>()

  /**
   * Prepares the Flutter project synchronization (Phase 1 & Phase 2).
   * Validates pubspec, detects SDKs, validates Android SDK integrity,
   * runs flutter pub get when dependencies have changed, ensures
   * local.properties and external storage execution patches are active.
   */
  fun prepareSync(
      projectDir: File,
      forcePubGet: Boolean = false,
      onLog: ((String) -> Unit)? = null,
  ): FlutterPreparationResult = synchronized(syncLock) {
    val logCallback = onLog ?: {}
    val syncLogBuffer = StringBuilder()

    fun emit(line: String) {
      syncLogBuffer.append(line).append("\n")
      logCallback(line)
      log.info(line)
    }

    if (!FlutterProjectHelper.isFlutterProject(projectDir)) {
      val msg = "Cannot synchronize: Not a Flutter project: ${projectDir.absolutePath}"
      emit(msg)
      return FlutterPreparationResult(false, null, msg)
    }

    val androidDir = File(projectDir, "android")
    if (!androidDir.exists() || !androidDir.isDirectory) {
      val msg = "No 'android' directory found in Flutter project at ${projectDir.absolutePath}. Run 'flutter create .' to generate Android platform support."
      emit(msg)
      return FlutterPreparationResult(false, null, msg)
    }

    emit(">>> Starting Flutter Project Discovery & SDK Validation...")
    emit("Flutter Root: ${projectDir.absolutePath}")
    emit("Android Subproject: ${androidDir.absolutePath}")

    // --- Phase 1: Flutter Project Discovery & SDK Validation ---
    val pubspecFile = File(projectDir, "pubspec.yaml")
    val pubspec = PubspecParser.parse(pubspecFile)
    val appName = pubspec?.name ?: "flutter_app"
    val versionName = pubspec?.versionName ?: "1.0.0"
    val versionCode = pubspec?.versionCode ?: 1
    emit("  • Project: $appName")
    emit("  • Version: $versionName+$versionCode")

    // Detect SDK Locations
    val flutterBin = Environment.FLUTTER_BIN ?: File(Environment.FLUTTER_HOME, "bin/flutter")
    val flutterInstalled = FlutterProjectHelper.isFlutterInstalled()
    val androidSdkDir = Environment.ANDROID_HOME
    emit("  • Flutter SDK: ${if (flutterInstalled) flutterBin.absolutePath else "Not Detected"}")
    emit("  • Android SDK: ${androidSdkDir?.absolutePath ?: "Not Detected"}")

    // Android SDK Validation (Section 4)
    val sdkValidation = AndroidSdkValidator.validate(androidSdkDir)
    emit(sdkValidation.formatReport())
    if (sdkValidation.details.isNotEmpty()) {
      for (detail in sdkValidation.details) {
        log.warn("SDK validation detail: {}", detail)
      }
    }

    // Detect Gradle Wrapper, Gradle, and AGP versions
    val wrapperPropsFile = File(androidDir, "gradle/wrapper/gradle-wrapper.properties")
    val gradleVersion = detectGradleVersion(wrapperPropsFile)
    val agpVersion = detectAgpVersion(androidDir)
    emit("  • Gradle wrapper: ${if (wrapperPropsFile.exists()) "Present" else "Missing"}")
    emit("  • Gradle distribution: $gradleVersion")
    emit("  • Android Gradle Plugin: $agpVersion")

    // --- Phase 2: Flutter Dependency Resolution ---
    emit(">>> Resolving Flutter dependencies...")
    val pubspecLockFile = File(projectDir, "pubspec.lock")
    val packageConfigFile = File(projectDir, ".dart_tool/package_config.json")
    val isUpToDate = !forcePubGet && isDependenciesUpToDate(projectDir, pubspecFile, pubspecLockFile, packageConfigFile)

    if (isUpToDate) {
      emit("  Dependencies up to date (pubspec unchanged). Skipping 'flutter pub get'.")
    } else if (flutterInstalled) {
      try {
        emit("  Executing 'flutter pub get'...")
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
        val exitCode = process.waitFor()
        if (exitCode == 0) {
          emit("  Dependencies resolved successfully.")
          pubspecTimestamps[projectDir.absolutePath] = pubspecFile.lastModified()
          pubspecLockTimestamps[projectDir.absolutePath] = pubspecLockFile.lastModified()
        } else {
          val msg = "Flutter dependency resolution failed ('flutter pub get' exited with code $exitCode). Check pubspec.yaml for package conflicts."
          emit("  Error: $msg")
          return FlutterPreparationResult(false, null, msg)
        }
      } catch (e: Exception) {
        val msg = "Failed to execute 'flutter pub get': ${e.message}"
        emit("  Error: $msg")
        return FlutterPreparationResult(false, null, msg)
      }
    } else {
      emit("  Warning: Flutter SDK executable not found; skipping 'flutter pub get'.")
    }

    // Configure local.properties for Flutter & Android SDK paths
    ensureLocalProperties(androidDir, androidSdkDir, Environment.FLUTTER_HOME, versionName, versionCode)

    // Configure Android subproject build files (AAPT2 override, ABI filters)
    val androidConfigured = FlutterProjectHelper.configureProjectForAndroidCS(projectDir)
    emit("  • Android configuration applied: $androidConfigured")

    // Verify external storage execution patch
    val isExternal = projectDir.absolutePath.startsWith("/storage/emulated/0") ||
        projectDir.absolutePath.startsWith("/sdcard")
    val isPatched = ensureStoragePatchApplied()
    emit("  • External storage: $isExternal (Patch applied: $isPatched)")

    // Extract Application Metadata
    val appId = extractApplicationId(projectDir) ?: "com.example.$appName"
    emit("  • Application ID: $appId")

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

    return FlutterPreparationResult(true, info)
  }

  /**
   * Backwards compatible synchronize method that runs discovery, validation,
   * pub get, and caches FlutterProjectInfo.
   */
  @Synchronized
  fun synchronize(
      projectDir: File,
      onLog: ((String) -> Unit)? = null
  ): FlutterProjectInfo? {
    val result = prepareSync(projectDir, false, onLog)
    return result.projectInfo
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
   * Checks whether Flutter dependencies are already up-to-date to avoid duplicate pub get executions.
   */
  private fun isDependenciesUpToDate(
      projectDir: File,
      pubspecFile: File,
      pubspecLockFile: File,
      packageConfigFile: File,
  ): Boolean {
    if (!pubspecLockFile.exists() || !packageConfigFile.exists()) {
      return false
    }

    val lastPubspec = pubspecTimestamps[projectDir.absolutePath] ?: 0L
    val lastLock = pubspecLockTimestamps[projectDir.absolutePath] ?: 0L

    val currentPubspecTime = pubspecFile.lastModified()
    val currentLockTime = pubspecLockFile.lastModified()

    return lastPubspec == currentPubspecTime &&
        lastLock == currentLockTime &&
        currentLockTime >= currentPubspecTime
  }

  /**
   * Ensures `<flutter_project>/android/local.properties` contains valid sdk.dir and flutter.sdk keys.
   */
  fun ensureLocalProperties(
      androidDir: File,
      androidSdkDir: File?,
      flutterSdkDir: File?,
      versionName: String,
      versionCode: Int
  ) {
    val localPropsFile = File(androidDir, "local.properties")
    val props = Properties()

    if (localPropsFile.exists()) {
      try {
        localPropsFile.inputStream().use { props.load(it) }
      } catch (e: Exception) {
        log.warn("Failed to read existing local.properties: {}", e.message)
      }
    }

    var modified = false

    if (androidSdkDir != null && androidSdkDir.exists()) {
      val existingSdk = props.getProperty("sdk.dir")
      if (existingSdk.isNullOrBlank() || !File(existingSdk).exists()) {
        props.setProperty("sdk.dir", androidSdkDir.absolutePath)
        modified = true
      }
    }

    if (flutterSdkDir != null && flutterSdkDir.exists()) {
      val existingFlutter = props.getProperty("flutter.sdk")
      if (existingFlutter.isNullOrBlank() || !File(existingFlutter).exists()) {
        props.setProperty("flutter.sdk", flutterSdkDir.absolutePath)
        modified = true
      }
    }

    if (!props.containsKey("flutter.versionCode")) {
      props.setProperty("flutter.versionCode", versionCode.toString())
      modified = true
    }

    if (!props.containsKey("flutter.versionName")) {
      props.setProperty("flutter.versionName", versionName)
      modified = true
    }

    if (modified || !localPropsFile.exists()) {
      try {
        localPropsFile.outputStream().use { props.store(it, "Generated by RV2IDE Flutter Sync") }
        log.info("Ensured local.properties in: {}", androidDir.absolutePath)
      } catch (e: Exception) {
        log.error("Failed to write local.properties: {}", e.message)
      }
    }
  }

  private fun detectGradleVersion(wrapperPropsFile: File): String {
    if (!wrapperPropsFile.exists()) return "unknown (wrapper missing)"
    return try {
      val text = wrapperPropsFile.readText()
      val match = Regex("""gradle-([0-9.]+)-(bin|all)\.zip""").find(text)
      match?.groupValues?.get(1) ?: "custom"
    } catch (_: Exception) {
      "unknown"
    }
  }

  private fun detectAgpVersion(androidDir: File): String {
    val settingsFiles = listOf(File(androidDir, "settings.gradle.kts"), File(androidDir, "settings.gradle"))
    for (f in settingsFiles) {
      if (f.exists()) {
        try {
          val match = Regex("""id\(?["']com\.android\.application["']\)?\s+version\s+["']([0-9.]+)["']""").find(f.readText())
          if (match != null) return match.groupValues[1]
        } catch (_: Exception) {}
      }
    }
    val buildFiles = listOf(File(androidDir, "build.gradle.kts"), File(androidDir, "build.gradle"))
    for (f in buildFiles) {
      if (f.exists()) {
        try {
          val match = Regex("""com\.android\.tools\.build:gradle:([0-9.]+)""").find(f.readText())
          if (match != null) return match.groupValues[1]
        } catch (_: Exception) {}
      }
    }
    return "auto"
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
