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
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import org.slf4j.LoggerFactory

/**
 * [TermuxSession] runner that executes `cmakesetup.sh` in the terminal emulator.
 */
class CMakeSetupSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val script: File,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

  companion object {

    private val log = LoggerFactory.getLogger(CMakeSetupSession::class.java)

    @JvmStatic
    fun wrap(session: TermuxSession?, script: File): CMakeSetupSession? {
      return session?.let { CMakeSetupSession(it, script) }
    }

    @JvmStatic
    fun createScript(context: Context): File? {
      val tempDir = File(context.filesDir, "temp")
      if (!tempDir.exists()) {
        tempDir.mkdirs()
      }
      val script = File(tempDir, "cmakesetup.sh")

      if (!writeCMakeSetupScript(context, script)) {
        return null
      }

      FileUtils.setFilePermissions("cmakesetupScript", script.absolutePath, "rwx")
      return script
    }

    private fun writeCMakeSetupScript(context: Context, script: File): Boolean {
      return try {
        val cpuArch = IDEBuildConfigProvider.getInstance().cpuArch
        val folderName =
            when (cpuArch) {
              CpuArch.AARCH64 -> "arm64"
              CpuArch.ARM -> "arm"
              CpuArch.X86_64 -> "x86_64"
              CpuArch.X86 -> "x86"
            }
        val assetName = try {
          context.assets.open(ToolsManager.getCommonAsset("${folderName}/cmakesetup.sh")).close()
          "${folderName}/cmakesetup.sh"
        } catch (_: Exception) {
          try {
            context.assets.open(ToolsManager.getCommonAsset("${folderName}/cmakesetup")).close()
            "${folderName}/cmakesetup"
          } catch (_: Exception) {
            "arm64/cmakesetup.sh"
          }
        }
        context.assets.open(ToolsManager.getCommonAsset(assetName)).use { inputStream ->
          FileOutputStream(script).use { outputStream -> inputStream.copyTo(outputStream) }
        }
        true
      } catch (e: Exception) {
        log.error("Failed to write cmakesetup script: {}", e.message, e)
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
    val logDir = File(Environment.HOME, "setup_logs")
    log.info("CMake setup session finished. Detailed logs stored in: {}", logDir.absolutePath)
    val error = FileUtils.deleteFile("cmakesetupScript", script.absolutePath, true)
    if (error != null) {
      log.error(error.errorLogString)
    }
  }
}
