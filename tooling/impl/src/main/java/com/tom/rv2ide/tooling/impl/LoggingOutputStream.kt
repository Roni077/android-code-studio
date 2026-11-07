/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.tooling.impl

import com.tom.rv2ide.tooling.api.IToolingApiClient
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Sends the output received from Gradle build to the [IToolingApiClient] in UTF-8 decoded, batched chunks.
 *
 * @author Akash Yadav
 */
class LoggingOutputStream : OutputStream() {
  private val byteBuffer = ByteArrayOutputStream(4096)
  private val lineBatch = StringBuilder(4096)
  private var lineCount = 0

  @Synchronized
  override fun write(b: Int) {
    if (b == '\n'.code) {
      flushLine()
    } else if (b != '\r'.code) {
      byteBuffer.write(b)
    }
  }

  @Synchronized
  override fun write(b: ByteArray, off: Int, len: Int) {
    var start = off
    val end = off + len
    for (i in off until end) {
      val byte = b[i]
      if (byte == '\n'.code.toByte()) {
        val segmentLen = i - start
        if (segmentLen > 0) {
          byteBuffer.write(b, start, segmentLen)
        }
        flushLine()
        start = i + 1
      } else if (byte == '\r'.code.toByte()) {
        val segmentLen = i - start
        if (segmentLen > 0) {
          byteBuffer.write(b, start, segmentLen)
        }
        start = i + 1
      }
    }
    if (start < end) {
      byteBuffer.write(b, start, end - start)
    }
  }

  private fun flushLine() {
    val line = byteBuffer.toString(StandardCharsets.UTF_8)
    byteBuffer.reset()
    if (lineBatch.isNotEmpty()) {
      lineBatch.append('\n')
    }
    lineBatch.append(line)
    lineCount++

    if (lineCount >= 50 || lineBatch.length >= 4096) {
      dispatchBatch()
    }
  }

  @Synchronized
  private fun dispatchBatch() {
    if (lineBatch.isEmpty()) return
    val text = lineBatch.toString()
    lineBatch.setLength(0)
    lineCount = 0

    val clientRef = Main.client
    clientRef?.logOutput(text)
  }

  @Synchronized
  override fun flush() {
    if (byteBuffer.size() > 0) {
      val remaining = byteBuffer.toString(StandardCharsets.UTF_8)
      byteBuffer.reset()
      if (lineBatch.isNotEmpty()) {
        lineBatch.append('\n')
      }
      lineBatch.append(remaining)
    }
    dispatchBatch()
  }

  @Synchronized
  override fun close() {
    flush()
    super.close()
  }
}
