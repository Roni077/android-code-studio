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
import androidx.core.app.NotificationManagerCompat
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.flutter.tasks.FlutterTask
import com.tom.rv2ide.resources.R
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Android Foreground Service for on-device Flutter builds and task execution.
 *
 * Displays ongoing progress notifications, acquires CPU wakelocks, ensures
 * resilience against the Android low-memory killer (LMK), and supports user cancellation.
 */
class FlutterBuildService : Service(), FlutterBuildEventListener {

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

  val isBuildInProgress: Boolean
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
