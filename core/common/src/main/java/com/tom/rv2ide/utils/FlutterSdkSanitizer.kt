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

package com.tom.rv2ide.utils

import java.io.File
import org.slf4j.LoggerFactory

/**
 * Utility to sanitize on-device Flutter SDK scripts, ensuring valid Android/Termux
 * shebangs, removing carriage returns (CRLF), relocating stale package prefixes,
 * and restoring execute permissions.
 */
object FlutterSdkSanitizer {

  private val log = LoggerFactory.getLogger(FlutterSdkSanitizer::class.java)
  private const val MAX_SCRIPT_SIZE_BYTES = 5 * 1024 * 1024L // 5MB limit to skip large Dart binaries

  /**
   * Sanitizes all Flutter executable and helper scripts inside [flutterHome].
   *
   * @param flutterHome The Flutter SDK root directory (defaults to [Environment.FLUTTER_HOME]).
   * @param prefix The current Termux/RV2IDE userland prefix (defaults to [Environment.PREFIX]).
   * @return `true` if sanitization succeeded, `false` if the directory does not exist or was invalid.
   */
  @JvmStatic
  @JvmOverloads
  fun sanitize(
      flutterHome: File = Environment.FLUTTER_HOME,
      prefix: File = Environment.PREFIX
  ): Boolean {
    if (!flutterHome.exists() || !flutterHome.isDirectory) {
      return false
    }

    val binDir = File(flutterHome, "bin")
    if (!binDir.exists() || !binDir.isDirectory) {
      return false
    }

    val prefixPath = prefix.absolutePath
    val targetBash = "$prefixPath/bin/bash"
    val targetSh = "$prefixPath/bin/sh"
    val legacyTermuxPrefix = "/data/data/com.termux/files/usr"

    var sanitizedCount = 0

    val dirsToScan = mutableListOf(
        binDir,
        File(binDir, "internal")
    )

    val packagesDir = File(flutterHome, "packages")
    if (packagesDir.exists() && packagesDir.isDirectory) {
      dirsToScan.add(packagesDir)
    }

    for (dir in dirsToScan) {
      if (!dir.exists() || !dir.isDirectory) continue
      val files = dir.listFiles() ?: continue
      for (file in files) {
        if (!file.isFile) continue

        // Always ensure execute permissions
        file.setExecutable(true, false)

        // Only inspect text scripts under the size limit
        if (file.length() > MAX_SCRIPT_SIZE_BYTES) continue

        // Strictly protect ELF binaries from string modifications
        if (isElfBinary(file)) continue

        try {
          val content = file.readText(Charsets.UTF_8)
          var updated = content

          // 1. Strip Windows CRLF line endings
          if (updated.contains("\r\n")) {
            updated = updated.replace("\r\n", "\n")
          }

          // 2. Relocate legacy prefix if present
          if (updated.contains(legacyTermuxPrefix)) {
            updated = updated.replace(legacyTermuxPrefix, prefixPath)
          }

          // 3. Patch shebang line if pointing to non-Android FHS paths
          val lines = updated.lines().toMutableList()
          if (lines.isNotEmpty() && lines[0].startsWith("#!")) {
            val firstLine = lines[0]
            val newFirstLine = when {
              firstLine.contains("/bin/env bash") || firstLine.contains("/bin/bash") -> "#!$targetBash"
              firstLine.contains("/bin/env sh") || firstLine.contains("/bin/sh") -> "#!$targetSh"
              firstLine.startsWith("#!/usr/bin/env ") -> {
                val interpreter = firstLine.removePrefix("#!/usr/bin/env ").trim()
                "#!$prefixPath/bin/$interpreter"
              }
              else -> firstLine
            }

            if (newFirstLine != firstLine) {
              lines[0] = newFirstLine
              updated = lines.joinToString("\n")
            }
          }

          if (updated != content) {
            file.writeText(updated, Charsets.UTF_8)
            file.setExecutable(true, false)
            sanitizedCount++
            log.debug("Sanitized Flutter script: {}", file.name)
          }
        } catch (e: Exception) {
          log.warn("Could not sanitize Flutter script {}: {}", file.name, e.message)
        }
      }
    }

    // Ensure bin/flutter and bin/dart have executable permissions
    File(binDir, "flutter").setExecutable(true, false)
    File(binDir, "dart").setExecutable(true, false)
    File(binDir, "cache/dart-sdk/bin/dart").setExecutable(true, false)

    if (sanitizedCount > 0) {
      log.info("Flutter SDK sanitized: updated {} script(s).", sanitizedCount)
    }

    return true
  }

  private fun isElfBinary(file: File): Boolean {
    return try {
      if (file.length() < 4) return false
      file.inputStream().use { input ->
        val header = ByteArray(4)
        val read = input.read(header)
        read == 4 && header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
      }
    } catch (e: Exception) {
      false
    }
  }
}
