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

package com.tom.rv2ide.terminal

import android.content.Context
import com.termux.shared.file.FileUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import com.tom.rv2ide.app.configuration.CpuArch
import com.tom.rv2ide.app.configuration.IDEBuildConfigProvider
import com.tom.rv2ide.managers.ToolsManager
import java.io.File
import java.io.FileOutputStream
import org.slf4j.LoggerFactory

/**
 * [TermuxSession] runner that executes `fluttersetup.sh` in the terminal emulator.
 */
class FlutterSetupSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val script: File,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

  companion object {

    private val log = LoggerFactory.getLogger(FlutterSetupSession::class.java)

    @JvmStatic
    fun wrap(session: TermuxSession?, script: File): FlutterSetupSession? {
      return session?.let { FlutterSetupSession(it, script) }
    }

    @JvmStatic
    fun createScript(context: Context): File? {
      val tempDir = File(context.filesDir, "temp")
      if (!tempDir.exists()) {
        tempDir.mkdirs()
      }
      val script = File(tempDir, "fluttersetup.sh")

      if (!writeFlutterSetupScript(context, script)) {
        return null
      }

      FileUtils.setFilePermissions("fluttersetupScript", script.absolutePath, "rwx")
      return script
    }

    private fun writeFlutterSetupScript(context: Context, script: File): Boolean {
      return try {
        val cpuArch = IDEBuildConfigProvider.getInstance().cpuArch
        if (cpuArch != CpuArch.AARCH64) {
          log.error("Cannot provision Flutter on non-ARM64 architecture: {}", cpuArch)
          return false
        }
        val folderName = "arm64"
        val assetName = try {
          context.assets.open(ToolsManager.getCommonAsset("${folderName}/fluttersetup.sh")).close()
          "${folderName}/fluttersetup.sh"
        } catch (_: Exception) {
          "${folderName}/fluttersetup"
        }
        context.assets.open(ToolsManager.getCommonAsset(assetName)).use { inputStream ->
          FileOutputStream(script).use { outputStream -> inputStream.copyTo(outputStream) }
        }
        true
      } catch (e: Exception) {
        log.error("Failed to write fluttersetup script: {}", e.message, e)
        false
      }
    }
  }

  private constructor(
      src: TermuxSession,
      script: File,
  ) : this(
      src.terminalSession,
      src.executionCommand,
      src.termuxSessionClient,
      src.isSetStdoutOnExit,
      script,
  )

  override fun finish() {
    super.finish()
    val error = FileUtils.deleteFile("fluttersetupScript", script.absolutePath, true)
    if (error != null) {
      log.error(error.errorLogString)
    }
  }
}
