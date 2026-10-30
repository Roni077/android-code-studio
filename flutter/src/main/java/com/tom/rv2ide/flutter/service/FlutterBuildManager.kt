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

package com.tom.rv2ide.flutter.service

import com.tom.rv2ide.flutter.project.FlutterProjectHelper
import com.tom.rv2ide.shell.executeProcessAsync
import com.tom.rv2ide.utils.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * On-device Flutter build manager for Android Code Studio.
 *
 * Orchestrates Flutter command execution (e.g. `flutter build apk`), streams stdout/stderr
 * in real-time, monitors build phases, discovers generated APKs, and handles user cancellation.
 */
object FlutterBuildManager {

  private val log = LoggerFactory.getLogger(FlutterBuildManager::class.java)
  private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("FlutterBuildManagerScope"))

  @Volatile private var activeProcess: Process? = null
  @Volatile private var activeJob: Job? = null
  private val listeners = CopyOnWriteArrayList<FlutterBuildEventListener>()

  private val ANSI_PATTERN = Pattern.compile("\\x1B\\[[;?0-9]*[a-zA-Z]")

  @Volatile
  var isBuildInProgress: Boolean = false
    private set

  /**
   * Registers a global [FlutterBuildEventListener].
   */
  fun addListener(listener: FlutterBuildEventListener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener)
    }
  }

  /**
   * Unregisters a [FlutterBuildEventListener].
   */
  fun removeListener(listener: FlutterBuildEventListener) {
    listeners.remove(listener)
  }

  /**
   * Starts an on-device Flutter build asynchronously.
   *
   * @param projectDir The root directory of the Flutter project.
   * @param commandArgs The arguments to pass to the `flutter` binary (default: `build apk --debug --no-tree-shake-icons`).
   * @param listener Optional listener to receive callbacks specifically for this build.
   * @return `true` if the build was successfully queued, `false` otherwise.
   */
  @Synchronized
  fun startBuild(
      projectDir: File,
      commandArgs: List<String> = listOf("build", "apk", "--debug", "--no-tree-shake-icons"),
      listener: FlutterBuildEventListener? = null,
  ): Boolean {
    if (isBuildInProgress) {
      log.warn("Cannot start build: Flutter build is already in progress.")
      return false
    }

    if (!FlutterProjectHelper.isFlutterInstalled()) {
      log.error("Cannot start build: Flutter SDK executable not found.")
      return false
    }

    // Ensure native AAPT2 override and ARM64 ABI filter are configured
    FlutterProjectHelper.configureProjectForAndroidCS(projectDir)

    listener?.let { addListener(it) }

    isBuildInProgress = true
    val flutterBin = Environment.FLUTTER_BIN ?: File(Environment.FLUTTER_HOME, "bin/flutter")
    val fullCommand = mutableListOf(flutterBin.absolutePath).apply { addAll(commandArgs) }
    val commandString = fullCommand.joinToString(" ")

    log.info("Launching Flutter build command: {}", commandString)
    notifyAllListeners { onBuildStarted(commandString) }

    activeJob = scope.launch {
      var process: Process? = null
      try {
        val env = mutableMapOf<String, String>()
        Environment.putEnvironment(env, false)
        env["TERM"] = "xterm-256color"

        process = executeProcessAsync {
          this.command = fullCommand
          this.workingDirectory = projectDir
          this.environment = env
          this.redirectErrorStream = true
        }
        activeProcess = process

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        var detectedApk: File? = null

        while (reader.readLine().also { line = it } != null) {
          val rawLine = line ?: break
          val cleanLine = stripAnsi(rawLine)

          notifyAllListeners { onOutput(cleanLine) }

          // Phase tracking
          parseBuildPhase(cleanLine)?.let { phase ->
            notifyAllListeners { onPhaseChanged(phase) }
          }

          // Check if an output APK path is mentioned in the log
          if (cleanLine.contains(".apk", ignoreCase = true)) {
            val apkCandidate = extractApkCandidate(projectDir, cleanLine)
            if (apkCandidate != null && apkCandidate.exists()) {
              detectedApk = apkCandidate
            }
          }
        }

        val exitCode = process.waitFor()
        isBuildInProgress = false
        activeProcess = null

        if (exitCode == 0) {
          val outputApk = detectedApk ?: findOutputApk(projectDir)
          log.info("Flutter build succeeded! Output APK: {}", outputApk?.absolutePath)
          notifyAllListeners { onBuildSuccess(outputApk) }
        } else {
          log.error("Flutter build failed with exit code: {}", exitCode)
          notifyAllListeners { onBuildFailed(exitCode, "Build failed with exit code $exitCode") }
        }
      } catch (ce: CancellationException) {
        log.info("Flutter build was cancelled by user.")
        process?.destroyForcibly()
        isBuildInProgress = false
        activeProcess = null
        notifyAllListeners { onBuildFailed(-1, "Build cancelled by user.") }
      } catch (e: Exception) {
        log.error("Exception during Flutter build execution: {}", e.message, e)
        isBuildInProgress = false
        activeProcess = null
        notifyAllListeners { onBuildFailed(-1, e.message ?: "Unknown error occurred.") }
      } finally {
        listener?.let { removeListener(it) }
      }
    }

    return true
  }

  /**
   * Cancels the currently running Flutter build.
   */
  @Synchronized
  fun cancelBuild(): Boolean {
    if (!isBuildInProgress) {
      return false
    }

    log.info("Cancelling running Flutter build...")
    try {
      activeProcess?.destroyForcibly()
      activeJob?.cancel(CancellationException("Build cancelled by user"))
    } catch (e: Exception) {
      log.warn("Error while stopping Flutter process: {}", e.message)
    } finally {
      activeProcess = null
      isBuildInProgress = false
    }
    return true
  }

  /**
   * Locates the built APK inside the project hierarchy.
   */
  fun findOutputApk(projectDir: File): File? {
    val flutterApkDir = File(projectDir, "build/app/outputs/flutter-apk")
    if (flutterApkDir.exists() && flutterApkDir.isDirectory) {
      val debugApk = File(flutterApkDir, "app-debug.apk")
      if (debugApk.exists()) return debugApk

      val releaseApk = File(flutterApkDir, "app-release.apk")
      if (releaseApk.exists()) return releaseApk

      val anyApk = flutterApkDir.listFiles { file -> file.extension.equals("apk", ignoreCase = true) }?.firstOrNull()
      if (anyApk != null) return anyApk
    }

    // Fallback: check standard Gradle apk outputs
    val gradleApkDir = File(projectDir, "android/app/build/outputs/apk/debug")
    if (gradleApkDir.exists() && gradleApkDir.isDirectory) {
      val apk = gradleApkDir.listFiles { file -> file.extension.equals("apk", ignoreCase = true) }?.firstOrNull()
      if (apk != null) return apk
    }

    return null
  }

  private fun parseBuildPhase(line: String): String? {
    return when {
      line.contains("Resolving dependencies", ignoreCase = true) ||
      line.contains("Running \"flutter pub get\"", ignoreCase = true) -> "Resolving Dependencies"

      line.contains("Running Gradle task", ignoreCase = true) -> {
        val taskName = line.substringAfter("Running Gradle task '").substringBefore("'")
        if (taskName.isNotEmpty() && taskName != line) "Running Gradle ($taskName)" else "Running Gradle Task"
      }

      line.contains("Compiling", ignoreCase = true) && line.contains("to kernel", ignoreCase = true) ->
        "Compiling Dart to Kernel"

      line.contains("Building APK", ignoreCase = true) || line.contains("assembleDebug", ignoreCase = true) ->
        "Building APK"

      line.contains("Signing", ignoreCase = true) ->
        "Signing APK"

      else -> null
    }
  }

  private fun extractApkCandidate(projectDir: File, line: String): File? {
    val words = line.split("\\s+".toRegex())
    for (word in words) {
      if (word.endsWith(".apk", ignoreCase = true)) {
        val file = File(word)
        val target = if (file.isAbsolute) file else File(projectDir, word)
        if (target.exists()) {
          return target
        }
      }
    }
    return null
  }

  private fun stripAnsi(text: String): String {
    return ANSI_PATTERN.matcher(text).replaceAll("")
  }

  private inline fun notifyAllListeners(action: FlutterBuildEventListener.() -> Unit) {
    for (listener in listeners) {
      try {
        listener.action()
      } catch (t: Throwable) {
        log.warn("Error in FlutterBuildEventListener callback: {}", t.message)
      }
    }
  }
}
