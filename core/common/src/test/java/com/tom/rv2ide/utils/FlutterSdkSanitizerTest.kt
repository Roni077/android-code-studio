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

    // Cleanup
    tempDir.deleteRecursively()
  }
}
