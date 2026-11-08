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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.flutter.project.FlutterProjectHelper
import com.tom.rv2ide.flutter.project.FlutterProjectSynchronizer
import com.tom.rv2ide.flutter.tasks.FlutterTask
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Android Foreground Service for on-device Flutter builds and task execution.
 *
 * Implements [BuildService] to polymorphically unify Flutter task execution and lifecycle
 * management with the IDE's core build infrastructure.
 */
class FlutterBuildService : Service(), BuildService, FlutterBuildEventListener {

  companion object {
    private val log = LoggerFactory.getLogger(FlutterBuildService::class.java)
    const val NOTIFICATION_ID = 2002
    const val ACTION_CANCEL_BUILD = "com.tom.rv2ide.flutter.ACTION_CANCEL_BUILD"
    const val EXTRA_PROJECT_DIR = "com.tom.rv2ide.flutter.EXTRA_PROJECT_DIR"
    const val EXTRA_TASK_COMMAND = "com.tom.rv2ide.flutter.EXTRA_TASK_COMMAND"
  }

  private var binder: FlutterServiceBinder? = null
  private var notificationManager: NotificationManager? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var currentPhase: String = "Building..."

  override val isBuildInProgress: Boolean
    get() = FlutterBuildManager.isBuildInProgress

  override fun onCreate() {
    super.onCreate()
    log.info("Creating FlutterBuildService...")
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    createNotificationChannel()
    FlutterBuildManager.addListener(this)
  }

  override fun onBind(intent: Intent?): IBinder? {
    if (binder == null) {
      binder = FlutterServiceBinder(this)
    }
    return binder
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) return START_NOT_STICKY

    if (intent.action == ACTION_CANCEL_BUILD) {
      log.info("Received cancellation intent for Flutter build.")
      cancelBuild()
      return START_NOT_STICKY
    }

    val dirPath = intent.getStringExtra(EXTRA_PROJECT_DIR)
    val commandArgs = intent.getStringArrayExtra(EXTRA_TASK_COMMAND)?.toList()

    if (dirPath != null && commandArgs != null) {
      val projectDir = File(dirPath)
      startBuild(projectDir, commandArgs)
    }

    return START_NOT_STICKY
  }

  fun executeTask(
      projectDir: File,
      task: FlutterTask,
      listener: FlutterBuildEventListener? = null
  ): Boolean {
    return startBuild(projectDir, task.commandArgs, listener)
  }

  fun startBuild(
      projectDir: File,
      commandArgs: List<String> = listOf("build", "apk", "--debug", "--no-tree-shake-icons"),
      listener: FlutterBuildEventListener? = null
  ): Boolean {
    acquireWakeLock()
    currentPhase = "Starting Flutter command..."
    startForeground(NOTIFICATION_ID, buildNotification(currentPhase, true))
    return FlutterBuildManager.startBuild(projectDir, commandArgs, listener)
  }

  fun cancelBuild(): Boolean {
    val cancelled = FlutterBuildManager.cancelBuild()
    if (cancelled) {
      releaseWakeLock()
      updateNotification("Flutter build cancelled", false)
      stopForeground(STOP_FOREGROUND_DETACH)
    }
    return cancelled
  }

  fun setEventListener(listener: FlutterBuildEventListener) {
    FlutterBuildManager.addListener(listener)
  }

  override fun isToolingServerStarted(): Boolean = true

  override fun metadata(): CompletableFuture<ToolingServerMetadata> {
    return CompletableFuture.completedFuture(ToolingServerMetadata(Process.myPid()))
  }

  override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> {
    val future = CompletableFuture<InitializeResult>()
    val projectDir = File(params.directory)
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val result = FlutterProjectSynchronizer.prepareSync(projectDir) { line ->
          FlutterBuildManager.notifyOutput(line)
        }
        future.complete(
            if (result.isSuccessful) InitializeResult(true, null)
            else InitializeResult(false, TaskExecutionResult.Failure.BUILD_FAILED)
        )
      } catch (e: Exception) {
        log.error("Failed to initialize Flutter project: {}", e.message)
        future.complete(InitializeResult(false, TaskExecutionResult.Failure.BUILD_FAILED))
      }
    }
    return future
  }

  override fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult> {
    val future = CompletableFuture<TaskExecutionResult>()
    val projectDir = IProjectManager.getInstance().projectDir ?: run {
      future.complete(TaskExecutionResult(false, TaskExecutionResult.Failure.PROJECT_NOT_FOUND))
      return future
    }

    if (!FlutterProjectHelper.isFlutterInstalled()) {
      future.complete(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED))
      return future
    }

    val commandList = mutableListOf<String>()
    if (tasks.isEmpty()) {
      commandList.addAll(listOf("build", "apk", "--debug", "--no-tree-shake-icons"))
    } else {
      for (task in tasks) {
        val clean = if (task.startsWith("flutter ")) task.removePrefix("flutter ") else task
        commandList.addAll(clean.split("\\s+".toRegex()).filter { it.isNotBlank() })
      }
    }

    val started = startBuild(projectDir, commandList, object : FlutterBuildEventListener {
      override fun onBuildStarted(command: String) {}
      override fun onOutput(line: String) {}
      override fun onPhaseChanged(phase: String) {}
      override fun onBuildSuccess(apkFile: File?) {
        future.complete(TaskExecutionResult.SUCCESS)
      }
      override fun onBuildFailed(exitCode: Int, errorSummary: String?) {
        future.complete(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED))
      }
    })

    if (!started) {
      future.complete(TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED))
    }

    return future
  }

  override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
    val wasRunning = FlutterBuildManager.isBuildInProgress
    val cancelled = cancelBuild()
    return CompletableFuture.completedFuture(
        if (wasRunning && cancelled) BuildCancellationRequestResult(true, null)
        else BuildCancellationRequestResult(false, BuildCancellationRequestResult.Reason.NO_RUNNING_BUILD)
    )
  }

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidCodeStudio:FlutterBuildLock")
    }
    try {
      if (wakeLock?.isHeld != true) {
        wakeLock?.acquire(45 * 60 * 1000L /* 45 minutes max */)
      }
    } catch (e: Exception) {
      log.warn("Failed to acquire wakelock: {}", e.message)
    }
  }

  private fun releaseWakeLock() {
    try {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    } catch (_: Exception) {}
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
        BaseApplication.NOTIFICATION_FLUTTER_BUILD_SERVICE,
        getString(R.string.title_flutter_service_notification),
        NotificationManager.IMPORTANCE_LOW
    )
    NotificationManagerCompat.from(this).createNotificationChannel(channel)
  }

  private fun buildNotification(message: String, isProgress: Boolean): Notification {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = if (launchIntent != null) {
      PendingIntent.getActivity(
          this,
          0,
          launchIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
    } else null

    val cancelIntent = Intent(this, FlutterBuildService::class.java).apply {
      action = ACTION_CANCEL_BUILD
    }
    val cancelPending = PendingIntent.getService(
        this,
        1,
        cancelIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = Notification.Builder(this, BaseApplication.NOTIFICATION_FLUTTER_BUILD_SERVICE)
        .setSmallIcon(R.drawable.ic_launcher_notification)
        .setContentTitle(getString(R.string.title_flutter_service_notification))
        .setContentText(message)
        .setTicker(getString(R.string.title_flutter_service_notification_ticker))
        .setOngoing(isProgress)

    if (contentIntent != null) {
      builder.setContentIntent(contentIntent)
    }

    if (isProgress) {
      builder.setProgress(100, 0, true)
      builder.addAction(Notification.Action.Builder(null, "Cancel", cancelPending).build())
    }

    return builder.build()
  }

  private fun updateNotification(message: String, isProgress: Boolean) {
    try {
      notificationManager?.notify(NOTIFICATION_ID, buildNotification(message, isProgress))
    } catch (e: Exception) {
      log.warn("Unable to update notification: {}", e.message)
    }
  }

  override fun onBuildStarted(command: String) {
    currentPhase = "Running Flutter..."
    updateNotification(currentPhase, true)
  }

  override fun onOutput(line: String) {}

  override fun onPhaseChanged(phase: String) {
    currentPhase = phase
    updateNotification(phase, true)
  }

  override fun onBuildSuccess(apkFile: File?) {
    releaseWakeLock()
    updateNotification("Flutter build finished successfully", false)
    stopForeground(STOP_FOREGROUND_DETACH)
  }

  override fun onBuildFailed(exitCode: Int, errorSummary: String?) {
    releaseWakeLock()
    val msg = errorSummary ?: "Flutter build failed with exit code $exitCode"
    updateNotification(msg, false)
    stopForeground(STOP_FOREGROUND_DETACH)
  }

  override fun onDestroy() {
    log.info("Destroying FlutterBuildService...")
    FlutterBuildManager.removeListener(this)
    releaseWakeLock()
    binder?.release()
    binder = null
    super.onDestroy()
  }
}

class FlutterServiceBinder(service: FlutterBuildService?) : Binder() {
  var service: FlutterBuildService? = service
    private set

  fun release() {
    service = null
  }
}
