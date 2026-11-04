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
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlutterSdkSanitizerTest {

  @Test
  fun testSanitizeNonExistentDirectoryReturnsFalse() {
    val nonExistent = File("build/tmp/non_existent_flutter_dir_" + System.currentTimeMillis())
    val mockPrefix = File("build/tmp/mock_prefix")
    assertFalse(FlutterSdkSanitizer.sanitize(nonExistent, mockPrefix))
  }

  @Test
  fun testSanitizeRewritesShebangsAndPrefixes() {
    val tempDir = Files.createTempDirectory("flutter_test_").toFile()
    val mockPrefix = File(tempDir, "usr")
    mockPrefix.mkdirs()

    val flutterHome = File(tempDir, "opt/flutter")
    val binDir = File(flutterHome, "bin")
    val internalDir = File(binDir, "internal")
    internalDir.mkdirs()

    // 1. Script with #!/usr/bin/env bash and legacy Termux prefix and CRLF
    val updateEngineScript = File(internalDir, "update_engine_version.sh")
    updateEngineScript.writeText(
        "#!/usr/bin/env bash\r\n" +
            "FLUTTER_ROOT=\"/data/data/com.termux/files/usr/opt/flutter\"\r\n" +
            "echo \"engine version check\"\r\n"
    )

    // 2. Script with #!/bin/bash
    val sharedScript = File(internalDir, "shared.sh")
    sharedScript.writeText("#!/bin/bash\necho \"shared\"\n")

    // 3. Script with #!/usr/bin/env sh
    val helperScript = File(binDir, "helper.sh")
    helperScript.writeText("#!/usr/bin/env sh\necho \"helper\"\n")

    // Run sanitizer
    val success = FlutterSdkSanitizer.sanitize(flutterHome, mockPrefix)
    assertTrue("Sanitization should succeed", success)

    val expectedBashShebang = "#!" + mockPrefix.absolutePath + "/bin/bash"
    val expectedShShebang = "#!" + mockPrefix.absolutePath + "/bin/sh"
    val expectedPrefixPath = mockPrefix.absolutePath

    // Verify update_engine_version.sh
    val updateEngineContent = updateEngineScript.readText()
    assertFalse("Should not contain CRLF", updateEngineContent.contains("\r\n"))
    assertFalse("Should not contain legacy Termux prefix", updateEngineContent.contains("/data/data/com.termux/files/usr"))
    assertTrue("Should have rewritten shebang to mockPrefix bash", updateEngineContent.startsWith(expectedBashShebang))
    assertTrue("Should contain updated prefix", updateEngineContent.contains(expectedPrefixPath))

    // Verify shared.sh
    val sharedContent = sharedScript.readText()
    assertTrue("Should rewrite #!/bin/bash to mockPrefix bash", sharedContent.startsWith(expectedBashShebang))

    // Verify helper.sh
    val helperContent = helperScript.readText()
    assertTrue("Should rewrite #!/usr/bin/env sh to mockPrefix sh", helperContent.startsWith(expectedShShebang))

    // Verify mockPrefix/tmp exists and has executable permissions
    val tmpDir = File(mockPrefix, "tmp")
    assertTrue("Prefix tmp directory should be created", tmpDir.exists() && tmpDir.isDirectory)
    assertTrue("Prefix tmp directory should be executable", tmpDir.canExecute())

    // Cleanup
    tempDir.deleteRecursively()
  }

  @Test
  fun testSanitizeGradleRunnerPatchesFile() {
    val tempDir = Files.createTempDirectory("flutter_gradle_test_").toFile()
    val mockPrefix = File(tempDir, "usr")
    mockPrefix.mkdirs()

    val flutterHome = File(tempDir, "opt/flutter")
    val gradleDartDir = File(flutterHome, "packages/flutter_tools/lib/src/android")
    gradleDartDir.mkdirs()

    val snapshotDir = File(flutterHome, "bin/cache")
    snapshotDir.mkdirs()
    val mockSnapshot = File(snapshotDir, "flutter_tools.snapshot").apply { writeText("mock snapshot") }
    val mockStamp = File(snapshotDir, "flutter_tools.stamp").apply { writeText("mock stamp") }

    val gradleDart = File(gradleDartDir, "gradle.dart")
    gradleDart.writeText(
        "class AndroidGradleBuilder {\n" +
            "  void _runGradleTask() {\n" +
            "    final command = <String>[\n" +
            "      gradleExecutablePath,\n" +
            "      ...options,\n" +
            "      taskName,\n" +
            "    ];\n" +
            "  }\n" +
            "}\n"
    )

    val patched = FlutterSdkSanitizer.sanitizeGradleRunner(flutterHome, mockPrefix)
    assertTrue("Sanitization of gradle.dart should succeed", patched)

    val updatedContent = gradleDart.readText()
    assertTrue("Should contain bypass noexec comment", updatedContent.contains("bypass noexec on external storage"))
    assertTrue("Should contain globals.platform reference", updatedContent.contains("globals.platform"))
    assertFalse("Should not contain obsolete _platform reference", updatedContent.contains("_platform.isLinux"))
    assertTrue("Should contain mock prefix bash", updatedContent.contains("${mockPrefix.absolutePath}/bin/bash"))
    assertFalse("Snapshot should be invalidated", mockSnapshot.exists())
    assertFalse("Stamp should be invalidated", mockStamp.exists())

    // Second run should be idempotent
    val secondRun = FlutterSdkSanitizer.sanitizeGradleRunner(flutterHome, mockPrefix)
    assertTrue("Second run should report success", secondRun)

    // Test migration of legacy _platform patch
    gradleDart.writeText(
        "class AndroidGradleBuilder {\n" +
            "  void _runGradleTask() {\n" +
            "    final command = <String>[\n" +
            "      // Termux / Android: route gradlew through bash to bypass noexec on external storage (/storage/emulated/0)\n" +
            "      if (_platform.isLinux && !gradleExecutablePath.endsWith('.bat')) ...<String>[\n" +
            "        _platform.environment['PREFIX'] != null ? '\${_platform.environment['PREFIX']}/bin/bash' : '/bin/bash',\n" +
            "      ],\n" +
            "      gradleExecutablePath,\n" +
            "    ];\n" +
            "  }\n" +
            "}\n"
    )
    val migrated = FlutterSdkSanitizer.sanitizeGradleRunner(flutterHome, mockPrefix)
    assertTrue("Migration of legacy _platform patch should succeed", migrated)
    val migratedContent = gradleDart.readText()
    assertTrue("Migrated content should contain globals.platform", migratedContent.contains("globals.platform"))
    assertFalse("Migrated content should not contain _platform.isLinux", migratedContent.contains("_platform.isLinux"))

    // Cleanup
    tempDir.deleteRecursively()
  }
}
