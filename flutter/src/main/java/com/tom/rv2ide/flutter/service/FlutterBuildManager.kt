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
import com.tom.rv2ide.flutter.project.FlutterProjectSynchronizer
import com.tom.rv2ide.flutter.project.PubspecParser
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.shell.executeProcessAsync
import com.tom.rv2ide.utils.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
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
 * Resolved parameters for building a Flutter Android project via native Gradle.
 */
data class FlutterBuildInvocation(
    val isBuildTask: Boolean,
    val gradleTask: String,
    val buildMode: String,
    val targetPlatform: String,
    val treeShakeIcons: Boolean,
    val splitPerAbi: Boolean,
    val obfuscate: Boolean,
    val splitDebugInfoPath: String?,
    val extraGradleArgs: List<String>,
    val rawCommandArgs: List<String>,
)

/**
 * On-device Flutter build manager for Android Code Studio.
 *
 * Replaces high-level `flutter build apk` CLI execution with an authentic, real-time
 * native Gradle build pipeline executing the project's Gradle wrapper. Preserves Flutter's
 * complete build lifecycle (dependency validation, plugin registrant generation, local.properties
 * synchronization, AAPT2 Maven overrides, Dart compilation to kernel/AOT, asset bundling, and APK packaging)
 * while streaming real-time Gradle task execution logs (`> Task :app:...`) matching AndroidIDE native builds.
 */
object FlutterBuildManager {

  private val log = LoggerFactory.getLogger(FlutterBuildManager::class.java)
  private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("FlutterBuildManagerScope"))

  @Volatile private var activeProcess: Process? = null
  @Volatile private var activeJob: Job? = null
  private val listeners = CopyOnWriteArrayList<FlutterBuildEventListener>()

  private val ANSI_PATTERN = Pattern.compile("\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\].*?(?:\\x07|\\x1B\\\\))")
  val TASK_LINE_REGEX = Regex("""^>\s*Task\s+(:[a-zA-Z0-9_:.-]+)(?:\s+(UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED))?""")
  val ACTIONABLE_TASKS_REGEX = Regex("""^([0-9]+\s+actionable\s+tasks?:\s+.*)$""")

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
   * Forwards an output log line to all registered listeners.
   */
  fun notifyOutput(line: String) {
    notifyAllListeners { onOutput(line) }
  }

  /**
   * Resolves raw Flutter or Gradle command arguments into a structured [FlutterBuildInvocation].
   */
  fun resolveInvocation(commandArgs: List<String>): FlutterBuildInvocation {
    val isBuild = commandArgs.isEmpty() || commandArgs.any { arg ->
      arg.equals("apk", ignoreCase = true) ||
          arg.equals("appbundle", ignoreCase = true) ||
          arg.startsWith("assemble", ignoreCase = true) ||
          arg.startsWith("bundle", ignoreCase = true) ||
          arg.startsWith(":")
    }

    if (!isBuild) {
      return FlutterBuildInvocation(
          isBuildTask = false,
          gradleTask = "",
          buildMode = "debug",
          targetPlatform = "android-arm64",
          treeShakeIcons = false,
          splitPerAbi = false,
          obfuscate = false,
          splitDebugInfoPath = null,
          extraGradleArgs = emptyList(),
          rawCommandArgs = commandArgs,
      )
    }

    var buildMode = "debug"
    var isBundle = false
    var explicitTask: String? = null
    var targetPlatform = "android-arm64"
    var treeShake = false
    var splitPerAbi = false
    var obfuscate = false
    var splitDebugInfo: String? = null
    val extraArgs = mutableListOf<String>()

    var i = 0
    while (i < commandArgs.size) {
      val arg = commandArgs[i]
      when {
        arg == "--release" -> buildMode = "release"
        arg == "--profile" -> buildMode = "profile"
        arg == "--debug" -> buildMode = "debug"
        arg.equals("appbundle", ignoreCase = true) || arg.contains("bundle", ignoreCase = true) -> isBundle = true
        arg == "--no-tree-shake-icons" -> treeShake = false
        arg == "--tree-shake-icons" -> treeShake = true
        arg == "--split-per-abi" -> splitPerAbi = true
        arg == "--obfuscate" -> obfuscate = true
        arg.startsWith("--split-debug-info=") -> splitDebugInfo = arg.substringAfter("=")
        arg == "--split-debug-info" && i + 1 < commandArgs.size -> {
          splitDebugInfo = commandArgs[++i]
        }
        arg.startsWith("--target-platform=") -> targetPlatform = arg.substringAfter("=")
        arg == "--target-platform" && i + 1 < commandArgs.size -> {
          targetPlatform = commandArgs[++i]
        }
        arg.startsWith(":") -> explicitTask = arg
        arg.startsWith("assemble") -> explicitTask = ":app:$arg"
        arg.startsWith("-P") || arg.startsWith("-D") || (arg.startsWith("--") && arg != "--android-skip-build-dependency-validation") -> {
          extraArgs.add(arg)
        }
      }
      i++
    }

    // Infer buildMode from explicitTask if present (e.g. :app:assembleRelease)
    if (explicitTask != null) {
      when {
        explicitTask.contains("Release", ignoreCase = true) -> buildMode = "release"
        explicitTask.contains("Profile", ignoreCase = true) -> buildMode = "profile"
        explicitTask.contains("Debug", ignoreCase = true) -> buildMode = "debug"
      }
      if (explicitTask.contains("bundle", ignoreCase = true)) {
        isBundle = true
      }
    }

    val taskName = explicitTask ?: when {
      isBundle -> if (buildMode == "debug") ":app:bundleDebug" else ":app:bundleRelease"
      buildMode == "release" -> ":app:assembleRelease"
      buildMode == "profile" -> ":app:assembleProfile"
      else -> ":app:assembleDebug"
    }

    return FlutterBuildInvocation(
        isBuildTask = true,
        gradleTask = taskName,
        buildMode = buildMode,
        targetPlatform = targetPlatform,
        treeShakeIcons = treeShake,
        splitPerAbi = splitPerAbi,
        obfuscate = obfuscate,
        splitDebugInfoPath = splitDebugInfo,
        extraGradleArgs = extraArgs,
        rawCommandArgs = commandArgs,
    )
  }

  /**
   * Starts an on-device Flutter build asynchronously.
   *
   * For build commands, invokes native Gradle with real-time task streaming.
   * For non-build commands (e.g. pub, analyze, clean), invokes Flutter CLI directly.
   *
   * @param projectDir The root directory of the Flutter project.
   * @param commandArgs Arguments describing the build or command (e.g. `build apk --debug`).
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

    listener?.let { addListener(it) }

    val invocation = resolveInvocation(commandArgs)
    isBuildInProgress = true

    activeJob = scope.launch {
      try {
        if (invocation.isBuildTask) {
          executeNativeGradleBuild(projectDir, invocation)
        } else {
          executeRawFlutterCommand(projectDir, invocation)
        }
      } catch (ce: CancellationException) {
        log.info("Build was cancelled by user.")
        activeProcess?.destroyForcibly()
        isBuildInProgress = false
        activeProcess = null
        notifyAllListeners { onBuildFailed(-1, "Build cancelled by user.") }
      } catch (e: Exception) {
        log.error("Exception during build execution: {}", e.message, e)
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
   * Executes native Gradle task for Flutter with real-time task log streaming.
   */
  private fun executeNativeGradleBuild(
      projectDir: File,
      invocation: FlutterBuildInvocation,
  ) {
    val startTime = System.currentTimeMillis()
    val buildLogBuffer = StringBuilder()

    // Phase 1 & 2: Preprocessing & Dependency Verification
    val prepSuccess = runPreprocessing(projectDir, invocation) { line ->
      buildLogBuffer.append(line).append("\n")
      notifyAllListeners { onOutput(line) }
    }

    if (!prepSuccess) {
      isBuildInProgress = false
      activeProcess = null
      notifyAllListeners { onBuildFailed(-1, "Flutter preprocessing failed.") }
      return
    }

    val androidDir = File(projectDir, "android")
    val fullCommand = buildGradleCommand(projectDir, invocation)

    val runTasksHeader = "Run tasks : [${invocation.gradleTask}]"
    buildLogBuffer.append(runTasksHeader).append("\n")
    log.info("Launching native Gradle build: {}", fullCommand.joinToString(" "))

    notifyAllListeners { onBuildStarted(runTasksHeader) }
    notifyAllListeners { onOutput(runTasksHeader) }
    notifyAllListeners { onPhaseChanged(invocation.gradleTask) }

    val env = createBuildEnvironment()

    var process: Process? = null
    try {
      process = executeProcessAsync {
        this.command = fullCommand
        this.workingDirectory = androidDir
        this.environment = env
        this.redirectErrorStream = true
      }
      activeProcess = process

      val reader = BufferedReader(InputStreamReader(process.inputStream))
      var line: String?
      var detectedApk: File? = null
      var firstCompilerError: String? = null
      var failingTask: String? = null
      var parsedActionableTasks: String? = null

      while (reader.readLine().also { line = it } != null) {
        val rawLine = line ?: break
        val cleanLine = stripAnsi(rawLine)
        val trimmed = cleanLine.trim()

        buildLogBuffer.append(cleanLine).append("\n")

        if (trimmed.isEmpty()) {
          if (rawLine.contains("\r")) continue
        }

        // Track Gradle task execution events (e.g. > Task :app:compileFlutterBuildDebug)
        val taskMatch = TASK_LINE_REGEX.find(trimmed)
        if (taskMatch != null) {
          val taskName = taskMatch.groupValues[1]
          val outcome = taskMatch.groupValues.getOrNull(2)

          notifyAllListeners { onPhaseChanged(taskName) }
          notifyAllListeners { onTaskStarted(taskName) }
          if (!outcome.isNullOrBlank()) {
            notifyAllListeners { onTaskFinished(taskName, outcome) }
          }
        }

        // Track actionable tasks metric
        val actionableMatch = ACTIONABLE_TASKS_REGEX.find(trimmed)
        if (actionableMatch != null) {
          parsedActionableTasks = actionableMatch.groupValues[1]
        }

        // Track failing task
        if (trimmed.startsWith("Execution failed for task ") || trimmed.startsWith("FAILURE: Build failed")) {
          if (failingTask == null && trimmed.contains("'")) {
            failingTask = trimmed.substringAfter("'").substringBefore("'")
          }
        }

        // Capture first meaningful compiler or build error
        if (firstCompilerError == null && trimmed.isNotEmpty()) {
          when {
            trimmed.contains(": Error: ") -> firstCompilerError = trimmed
            trimmed.startsWith("Error: ") -> firstCompilerError = trimmed
            trimmed.startsWith("e: ") -> firstCompilerError = trimmed
            trimmed.startsWith("Target kernel_snapshot failed: ") -> firstCompilerError = trimmed
            trimmed.startsWith("Execution failed for task ") -> firstCompilerError = trimmed
            trimmed.startsWith("AAPT: error: ") -> firstCompilerError = trimmed
          }
        }

        // Check if an output APK path is mentioned in stdout
        if (cleanLine.contains(".apk", ignoreCase = true) || cleanLine.contains(".aab", ignoreCase = true)) {
          val apkCandidate = extractApkCandidate(projectDir, cleanLine)
          if (apkCandidate != null && apkCandidate.exists() && apkCandidate.length() > 0) {
            detectedApk = apkCandidate
          }
        }

        // Stream real Gradle output to listeners
        notifyAllListeners { onOutput(cleanLine) }
      }

      val exitCode = process.waitFor()
      isBuildInProgress = false
      activeProcess = null

      try {
        File(projectDir, "build.log").writeText(buildLogBuffer.toString())
      } catch (ioe: Throwable) {
        log.warn("Failed to persist build.log: {}", ioe.message)
      }

      val durationSec = ((System.currentTimeMillis() - startTime) / 1000).coerceAtLeast(1)

      if (exitCode == 0) {
        val outputApk = detectedApk ?: findOutputApk(projectDir, invocation.buildMode)
        val abiStr = mapPlatformToAbi(invocation.targetPlatform)

        notifyAllListeners { onOutput("") }
        notifyAllListeners { onOutput("BUILD SUCCESSFUL in ${durationSec}s") }
        if (outputApk != null && outputApk.exists()) {
          val relPath = try {
            outputApk.relativeTo(projectDir).path.replace('\\', '/')
          } catch (_: Throwable) {
            outputApk.absolutePath
          }
          val sizeStr = formatFileSize(outputApk.length())

          notifyAllListeners { onOutput("APK: $relPath") }
          notifyAllListeners { onOutput("Size: $sizeStr") }
          notifyAllListeners { onOutput("Variant: ${invocation.buildMode}") }
          notifyAllListeners { onOutput("ABI: $abiStr") }
        }
        if (parsedActionableTasks != null) {
          notifyAllListeners { onOutput(parsedActionableTasks) }
        }

        log.info("Native Gradle build succeeded for Flutter project: {}", outputApk?.absolutePath)
        notifyAllListeners { onBuildSuccess(outputApk) }
      } else {
        val failureSummary = firstCompilerError ?: failingTask ?: "Build failed with exit code $exitCode"
        notifyAllListeners { onOutput("") }
        notifyAllListeners { onOutput("BUILD FAILED in ${durationSec}s") }
        if (failingTask != null) {
          notifyAllListeners { onOutput("Failing task: $failingTask") }
        }
        log.error("Native Gradle build failed with exit code {}: {}", exitCode, failureSummary)
        notifyAllListeners { onBuildFailed(exitCode, failureSummary) }
      }
    } finally {
      process?.destroyForcibly()
      activeProcess = null
      isBuildInProgress = false
    }
  }

  /**
   * Executes a direct Flutter CLI command (e.g. flutter pub get, flutter clean, flutter analyze).
   */
  private fun executeRawFlutterCommand(
      projectDir: File,
      invocation: FlutterBuildInvocation,
  ) {
    val flutterBin = Environment.FLUTTER_BIN ?: File(Environment.FLUTTER_HOME, "bin/flutter")
    val fullCommand = mutableListOf(flutterBin.absolutePath).apply { addAll(invocation.rawCommandArgs) }
    val commandString = fullCommand.joinToString(" ")

    log.info("Executing Flutter CLI command: {}", commandString)
    notifyAllListeners { onBuildStarted(commandString) }
    notifyAllListeners { onOutput(commandString) }

    val env = createBuildEnvironment()
    var process: Process? = null
    try {
      process = executeProcessAsync {
        this.command = fullCommand
        this.workingDirectory = projectDir
        this.environment = env
        this.redirectErrorStream = true
      }
      activeProcess = process

      val reader = BufferedReader(InputStreamReader(process.inputStream))
      var line: String?
      while (reader.readLine().also { line = it } != null) {
        val cleanLine = stripAnsi(line ?: break)
        notifyAllListeners { onOutput(cleanLine) }
      }

      val exitCode = process.waitFor()
      isBuildInProgress = false
      activeProcess = null

      if (exitCode == 0) {
        notifyAllListeners { onBuildSuccess(null) }
      } else {
        notifyAllListeners { onBuildFailed(exitCode, "Command failed with exit code $exitCode") }
      }
    } finally {
      process?.destroyForcibly()
      activeProcess = null
      isBuildInProgress = false
    }
  }

  /**
   * Prepares the project prior to Gradle execution: validates dependencies and writes local.properties.
   */
  fun runPreprocessing(
      projectDir: File,
      invocation: FlutterBuildInvocation,
      onLog: (String) -> Unit,
  ): Boolean {
    val androidDir = File(projectDir, "android")
    if (!androidDir.exists() || !androidDir.isDirectory) {
      onLog("Error: Missing 'android' directory in Flutter project. Run 'flutter create .' to generate it.")
      return false
    }

    val flutterBin = Environment.FLUTTER_BIN ?: File(Environment.FLUTTER_HOME, "bin/flutter")
    val pubspecFile = File(projectDir, "pubspec.yaml")
    val pubspecLockFile = File(projectDir, "pubspec.lock")
    val packageConfigFile = File(projectDir, ".dart_tool/package_config.json")
    val pluginsDepFile = File(projectDir, ".flutter-plugins-dependencies")

    val isDependenciesStale = !pubspecLockFile.exists() ||
        !packageConfigFile.exists() ||
        !pluginsDepFile.exists() ||
        pubspecFile.lastModified() > pubspecLockFile.lastModified()

    if (isDependenciesStale && FlutterProjectHelper.isFlutterInstalled()) {
      onLog(">>> Validating Flutter dependencies...")
      onLog("Running \"flutter pub get\" in ${projectDir.name}...")
      try {
        val env = createBuildEnvironment()
        val pubProcess = executeProcessAsync {
          this.command = listOf(flutterBin.absolutePath, "pub", "get")
          this.workingDirectory = projectDir
          this.environment = env
          this.redirectErrorStream = true
        }
        val reader = BufferedReader(InputStreamReader(pubProcess.inputStream))
        var pubLine: String?
        while (reader.readLine().also { pubLine = it } != null) {
          val l = pubLine?.trim() ?: continue
          if (l.isNotEmpty()) {
            onLog("  $l")
          }
        }
        val exitCode = pubProcess.waitFor()
        if (exitCode != 0) {
          onLog("Error: 'flutter pub get' failed with exit code $exitCode. Aborting build.")
          return false
        }
        onLog("Flutter dependencies resolved successfully.")
      } catch (e: Exception) {
        onLog("Warning: Could not execute 'flutter pub get': ${e.message}")
      }
    }

    // Ensure local.properties
    val pubspec = PubspecParser.parse(pubspecFile)
    val versionName = pubspec?.versionName ?: "1.0.0"
    val versionCode = pubspec?.versionCode ?: 1
    FlutterProjectSynchronizer.ensureLocalProperties(
        androidDir = androidDir,
        androidSdkDir = Environment.ANDROID_HOME,
        flutterSdkDir = Environment.FLUTTER_HOME,
        versionName = versionName,
        versionCode = versionCode,
    )
    ensureBuildModeInLocalProperties(androidDir, invocation.buildMode)

    // Configure AAPT2 override, org.gradle.console=plain, bash shebang
    FlutterProjectHelper.configureProjectForAndroidCS(projectDir)

    return true
  }

  /**
   * Constructs the native Gradle command arguments.
   */
  fun buildGradleCommand(
      projectDir: File,
      invocation: FlutterBuildInvocation,
  ): List<String> {
    val androidDir = File(projectDir, "android")
    val gradlew = File(androidDir, "gradlew")
    val gradlewBat = File(androidDir, "gradlew.bat")

    val isWindows = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true
    val command = mutableListOf<String>()

    if (isWindows) {
      if (gradlewBat.exists()) {
        command.addAll(listOf("cmd.exe", "/c", gradlewBat.absolutePath))
      } else {
        command.addAll(listOf("cmd.exe", "/c", "gradlew.bat"))
      }
    } else {
      val bashBin = File(Environment.PREFIX, "bin/bash").takeIf { it.exists() }?.absolutePath ?: "/bin/bash"
      command.add(bashBin)
      command.add(if (gradlew.exists()) gradlew.absolutePath else "./gradlew")
    }

    // Tasks to run
    command.add(invocation.gradleTask)

    // Console format: plain text so real-time tasks are streamed
    command.add("--console=plain")

    // Flutter project properties
    command.add("-Ptarget-platform=${invocation.targetPlatform}")
    command.add("-Pflutter-build-mode=${invocation.buildMode}")
    if (!invocation.treeShakeIcons) {
      command.add("-Ptree-shake-icons=false")
    }
    if (invocation.splitPerAbi) {
      command.add("-Psplit-per-abi=true")
    }
    if (invocation.obfuscate) {
      command.add("-Pextra-gen-snapshot-options=--obfuscate")
    }
    if (!invocation.splitDebugInfoPath.isNullOrBlank()) {
      command.add("-Psplit-debug-info=${invocation.splitDebugInfoPath}")
    }

    // AAPT2 Maven override for Termux/AndroidCS
    val aapt2 = Environment.AAPT2 ?: File(Environment.PREFIX, "bin/aapt2")
    if (aapt2.exists()) {
      command.add("-Pandroid.aapt2FromMavenOverride=${aapt2.absolutePath}")
    }

    // Encoding and platform properties
    command.add("-Dfile.encoding=UTF-8")
    command.add("-Dsun.jnu.encoding=UTF-8")

    // Performance & cache settings from BuildPreferences
    try {
      if (BuildPreferences.isBuildCacheEnabled) {
        command.add("--build-cache")
      }
      if (BuildPreferences.isStacktraceEnabled) {
        command.add("--stacktrace")
      }
      if (BuildPreferences.isWarningModeAllEnabled) {
        command.add("--warning-mode=all")
      }
      if (BuildPreferences.isOfflineEnabled) {
        command.add("--offline")
      }
      if (BuildPreferences.isInfoEnabled) {
        command.add("--info")
      }
      if (BuildPreferences.isDebugEnabled) {
        command.add("--debug")
      }
    } catch (_: Throwable) {
      // Default build cache on if preferences unavailable
      command.add("--build-cache")
    }

    // Extra user args
    for (extra in invocation.extraGradleArgs) {
      if (!command.contains(extra)) {
        command.add(extra)
      }
    }

    return command
  }

  private fun ensureBuildModeInLocalProperties(androidDir: File, buildMode: String) {
    val localProps = File(androidDir, "local.properties")
    if (!localProps.exists()) return
    try {
      val props = Properties()
      localProps.inputStream().use { props.load(it) }
      if (props.getProperty("flutter.buildMode") != buildMode) {
        props.setProperty("flutter.buildMode", buildMode)
        localProps.outputStream().use { props.store(it, "Updated by RV2IDE") }
      }
    } catch (_: Throwable) {}
  }

  private fun createBuildEnvironment(): Map<String, String> {
    val env = mutableMapOf<String, String>()
    Environment.putEnvironment(env, false)
    env["TERM"] = "xterm-256color"

    val tmpDir = Environment.ensureTmpDir() ?: File(Environment.PREFIX, "tmp")
    if (!tmpDir.exists()) {
      tmpDir.mkdirs()
    }
    tmpDir.setReadable(true, false)
    tmpDir.setWritable(true, false)
    tmpDir.setExecutable(true, false)
    env["TMPDIR"] = tmpDir.absolutePath
    env["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Duser.language=en -Duser.country=US"
    env["GRADLE_OPTS"] = "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
    return env
  }

  /**
   * Cancels the currently running build.
   */
  @Synchronized
  fun cancelBuild(): Boolean {
    if (!isBuildInProgress) {
      return false
    }

    log.info("Cancelling running build...")
    try {
      activeProcess?.destroyForcibly()
      activeJob?.cancel(CancellationException("Build cancelled by user"))
    } catch (e: Exception) {
      log.warn("Error while stopping process: {}", e.message)
    } finally {
      activeProcess = null
      isBuildInProgress = false
    }
    return true
  }

  /**
   * Locates the built APK or AAB inside the project hierarchy.
   */
  fun findOutputApk(projectDir: File, buildMode: String = "debug"): File? {
    val modeName = buildMode.lowercase()
    val candidateDirs = listOf(
        File(projectDir, "build/app/outputs/flutter-apk"),
        File(projectDir, "android/app/build/outputs/apk/$modeName"),
        File(projectDir, "android/app/build/outputs/apk"),
        File(projectDir, "build/app/outputs/bundle/$modeName"),
        File(projectDir, "android/app/build/outputs/bundle/$modeName"),
    )

    // 1. Direct name match in candidate dirs
    for (dir in candidateDirs) {
      if (!dir.exists() || !dir.isDirectory) continue
      val directMatch = File(dir, "app-$modeName.apk")
      if (directMatch.exists() && directMatch.length() > 0) return directMatch
      val directAab = File(dir, "app-$modeName.aab")
      if (directAab.exists() && directAab.length() > 0) return directAab
    }

    // 2. Any .apk in flutter-apk dir
    val flutterApkDir = File(projectDir, "build/app/outputs/flutter-apk")
    if (flutterApkDir.exists() && flutterApkDir.isDirectory) {
      val anyApk = flutterApkDir.listFiles { f ->
        f.isFile && f.extension.equals("apk", ignoreCase = true) && f.length() > 0
      }?.maxByOrNull { it.lastModified() }
      if (anyApk != null) return anyApk
    }

    // 3. Fallback: scan candidate dirs for newest .apk or .aab
    for (dir in candidateDirs) {
      if (!dir.exists() || !dir.isDirectory) continue
      val anyFile = dir.walkTopDown().maxDepth(3).filter { f ->
        f.isFile && (f.extension.equals("apk", ignoreCase = true) || f.extension.equals("aab", ignoreCase = true)) && f.length() > 0
      }.maxByOrNull { it.lastModified() }
      if (anyFile != null) return anyFile
    }

    return null
  }

  fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val clamped = digitGroups.coerceIn(0, units.size - 1)
    return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, clamped.toDouble()), units[clamped])
  }

  fun mapPlatformToAbi(platform: String): String {
    return when (platform.lowercase()) {
      "android-arm64", "arm64" -> "arm64-v8a"
      "android-arm", "arm" -> "armeabi-v7a"
      "android-x64", "x64", "x86_64" -> "x86_64"
      "android-x86", "x86" -> "x86"
      else -> platform
    }
  }

  private fun extractApkCandidate(projectDir: File, line: String): File? {
    val words = line.split("\\s+".toRegex())
    for (word in words) {
      if (word.endsWith(".apk", ignoreCase = true) || word.endsWith(".aab", ignoreCase = true)) {
        val file = File(word)
        val target = if (file.isAbsolute) file else File(projectDir, word)
        if (target.exists() && target.length() > 0) {
          return target
        }
      }
    }
    return null
  }

  fun stripAnsi(text: String): String {
    return ANSI_PATTERN.matcher(text).replaceAll("").replace("\r", "")
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
