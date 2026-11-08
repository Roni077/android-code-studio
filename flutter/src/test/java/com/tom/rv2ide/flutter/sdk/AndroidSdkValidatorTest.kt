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

package com.tom.rv2ide.flutter.sdk

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSdkValidatorTest {

  @Test
  fun testNonExistentDirectoryFailsGracefully() {
    val nonExistent = File("build/tmp/non_existent_sdk_dir_${System.currentTimeMillis()}")
    val result = AndroidSdkValidator.validate(nonExistent, forceRecheck = true)

    assertFalse(result.isSdkDirValid)
    assertEquals("MISSING", result.buildToolsStatus)
    assertEquals("MISSING", result.platformToolsStatus)
    assertEquals("MISSING", result.androidPlatformStatus)
  }

  @Test
  fun testValidMockSdkStructure() {
    val tempDir = Files.createTempDirectory("mock_sdk_").toFile()
    try {
      // 1. build-tools
      val btDir = File(tempDir, "build-tools/35.0.0").apply { mkdirs() }
      File(btDir, "aapt2").writeText("mock")

      // 2. platform-tools
      val ptDir = File(tempDir, "platform-tools").apply { mkdirs() }
      File(ptDir, "adb").writeText("mock")

      // 3. platforms
      val platDir = File(tempDir, "platforms/android-35").apply { mkdirs() }
      File(platDir, "android.jar").writeText("mock")

      // 4. ndk
      val ndkDir = File(tempDir, "ndk/28.2.13676358").apply { mkdirs() }
      File(ndkDir, "ndk-build").writeText("mock")

      // 5. package.xml
      File(btDir, "package.xml").writeText(
          """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
             <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02">
               <localPackage path="build-tools;35.0.0" />
             </ns2:repository>""".trimIndent()
      )

      val result = AndroidSdkValidator.validate(tempDir, forceRecheck = true)

      assertTrue(result.isSdkDirValid)
      assertEquals("VALID", result.buildToolsStatus)
      assertEquals("VALID", result.platformToolsStatus)
      assertEquals("VALID", result.androidPlatformStatus)
      assertEquals("VALID", result.ndkStatus)
      assertEquals("VALID", result.packageMetadataStatus)

      val report = result.formatReport()
      assertTrue(report.contains("Android SDK validation:"))
      assertTrue(report.contains("Build Tools: VALID"))
      assertTrue(report.contains("Platform Tools: VALID"))
      assertTrue(report.contains("Android Platform: VALID"))
      assertTrue(report.contains("NDK: VALID"))
      assertTrue(report.contains("Package Metadata: VALID"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun testDuplicatePackageRegistrationDetected() {
    val tempDir = Files.createTempDirectory("mock_sdk_dup_").toFile()
    try {
      val btDir1 = File(tempDir, "build-tools/35.0.0").apply { mkdirs() }
      File(btDir1, "aapt2").writeText("mock")
      File(btDir1, "package.xml").writeText(
          """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
             <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02">
               <localPackage path="build-tools;35.0.0" />
             </ns2:repository>""".trimIndent()
      )

      val btDir2 = File(tempDir, "build-tools/35.0.0-duplicate").apply { mkdirs() }
      File(btDir2, "aapt2").writeText("mock")
      File(btDir2, "package.xml").writeText(
          """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
             <ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02">
               <localPackage path="build-tools;35.0.0" />
             </ns2:repository>""".trimIndent()
      )

      val result = AndroidSdkValidator.validate(tempDir, forceRecheck = true)

      assertTrue(result.packageMetadataStatus.startsWith("WARNING"))
      assertTrue(result.details.any { it.contains("Duplicate SDK package registration") })
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
