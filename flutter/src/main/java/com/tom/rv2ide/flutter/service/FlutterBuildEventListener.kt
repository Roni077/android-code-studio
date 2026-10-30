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

import java.io.File

/**
 * Event listener interface for monitoring Flutter build lifecycle events,
 * log streaming, phase progression, and APK generation.
 */
interface FlutterBuildEventListener {

  /**
   * Called when a Flutter build execution has started.
   *
   * @param command The full command being executed.
   */
  fun onBuildStarted(command: String)

  /**
   * Called for each stdout or stderr line output by the Flutter process.
   *
   * @param line The raw log line.
   */
  fun onOutput(line: String)

  /**
   * Called when the build advances to a new logical phase.
   *
   * @param phase Human-readable phase description (e.g., "Compiling Dart...", "Gradle Assembly").
   */
  fun onPhaseChanged(phase: String)

  /**
   * Called when the build completes successfully with code 0.
   *
   * @param apkFile The generated APK file, or null if not yet located.
   */
  fun onBuildSuccess(apkFile: File?)

  /**
   * Called when the build fails or is cancelled by the user.
   *
   * @param exitCode The process exit code (-1 for cancellation / runtime exception).
   * @param errorSummary Brief summary of the failure reason.
   */
  fun onBuildFailed(exitCode: Int, errorSummary: String?)
}
