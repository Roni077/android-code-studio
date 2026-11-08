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
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlutterBuildManagerTest {

  @Test
  fun testResolveDefaultDebugInvocation() {
    val invocation = FlutterBuildManager.resolveInvocation(emptyList())
    assertTrue(invocation.isBuildTask)
    assertEquals(":app:assembleDebug", invocation.gradleTask)
    assertEquals("debug", invocation.buildMode)
    assertEquals("android-arm64", invocation.targetPlatform)
    assertFalse(invocation.treeShakeIcons)
    assertFalse(invocation.splitPerAbi)
    assertFalse(invocation.obfuscate)
  }

  @Test
  fun testResolveReleaseApkInvocation() {
    val args = listOf("build", "apk", "--release", "--target-platform", "android-arm64", "--no-tree-shake-icons")
    val invocation = FlutterBuildManager.resolveInvocation(args)
    assertTrue(invocation.isBuildTask)
    assertEquals(":app:assembleRelease", invocation.gradleTask)
    assertEquals("release", invocation.buildMode)
    assertEquals("android-arm64", invocation.targetPlatform)
    assertFalse(invocation.treeShakeIcons)
  }

  @Test
  fun testResolveProfileApkInvocation() {
    val args = listOf("build", "apk", "--profile", "--target-platform", "android-arm64", "--no-tree-shake-icons")
    val invocation = FlutterBuildManager.resolveInvocation(args)
    assertTrue(invocation.isBuildTask)
    assertEquals(":app:assembleProfile", invocation.gradleTask)
    assertEquals("profile", invocation.buildMode)
    assertEquals("android-arm64", invocation.targetPlatform)
  }

  @Test
  fun testResolveAppBundleInvocation() {
    val args = listOf("build", "appbundle", "--release", "--target-platform", "android-arm64", "--no-tree-shake-icons")
    val invocation = FlutterBuildManager.resolveInvocation(args)
    assertTrue(invocation.isBuildTask)
    assertEquals(":app:bundleRelease", invocation.gradleTask)
    assertEquals("release", invocation.buildMode)
  }

  @Test
  fun testResolveExplicitGradleTask() {
    val args = listOf(":app:assembleDebug")
    val invocation = FlutterBuildManager.resolveInvocation(args)
    assertTrue(invocation.isBuildTask)
    assertEquals(":app:assembleDebug", invocation.gradleTask)
    assertEquals("debug", invocation.buildMode)
  }

  @Test
  fun testResolveCustomFlags() {
    val args = listOf(
        "build", "apk", "--release",
        "--split-per-abi",
        "--obfuscate",
        "--split-debug-info=build/symbols",
        "--no-tree-shake-icons"
    )
    val invocation = FlutterBuildManager.resolveInvocation(args)
    assertTrue(invocation.isBuildTask)
    assertTrue(invocation.splitPerAbi)
    assertTrue(invocation.obfuscate)
    assertEquals("build/symbols", invocation.splitDebugInfoPath)
    assertFalse(invocation.treeShakeIcons)
  }

  @Test
  fun testResolveNonBuildCommand() {
    val pubInvocation = FlutterBuildManager.resolveInvocation(listOf("pub", "get"))
    assertFalse(pubInvocation.isBuildTask)

    val cleanInvocation = FlutterBuildManager.resolveInvocation(listOf("clean"))
    assertFalse(cleanInvocation.isBuildTask)

    val analyzeInvocation = FlutterBuildManager.resolveInvocation(listOf("analyze"))
    assertFalse(analyzeInvocation.isBuildTask)
  }

  @Test
  fun testBuildGradleCommandContainsPlainConsoleAndProperties() {
    val tempDir = Files.createTempDirectory("flutter_test_proj_").toFile()
    try {
      File(tempDir, "android").apply { mkdirs() }
      val invocation = FlutterBuildInvocation(
          isBuildTask = true,
          gradleTask = ":app:assembleDebug",
          buildMode = "debug",
          targetPlatform = "android-arm64",
          treeShakeIcons = false,
          splitPerAbi = true,
          obfuscate = false,
          splitDebugInfoPath = null,
          extraGradleArgs = emptyList(),
          rawCommandArgs = emptyList()
      )

      val cmd = FlutterBuildManager.buildGradleCommand(tempDir, invocation)
      assertTrue(cmd.contains(":app:assembleDebug"))
      assertTrue(cmd.contains("--console=plain"))
      assertTrue(cmd.contains("-Ptarget-platform=android-arm64"))
      assertTrue(cmd.contains("-Pflutter-build-mode=debug"))
      assertTrue(cmd.contains("-Ptree-shake-icons=false"))
      assertTrue(cmd.contains("-Psplit-per-abi=true"))
      assertTrue(cmd.contains("-Dfile.encoding=UTF-8"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun testTaskLineRegexMatchesActualGradleTaskOutputs() {
    val line1 = "> Task :app:preBuild UP-TO-DATE"
    val match1 = FlutterBuildManager.TASK_LINE_REGEX.find(line1)
    assertNotNull(match1)
    assertEquals(":app:preBuild", match1!!.groupValues[1])
    assertEquals("UP-TO-DATE", match1.groupValues[2])

    val line2 = "> Task :app:compileFlutterBuildDebug"
    val match2 = FlutterBuildManager.TASK_LINE_REGEX.find(line2)
    assertNotNull(match2)
    assertEquals(":app:compileFlutterBuildDebug", match2!!.groupValues[1])
    assertEquals("", match2.groupValues[2])

    val line3 = "> Task :app:compileDebugKotlin FROM-CACHE"
    val match3 = FlutterBuildManager.TASK_LINE_REGEX.find(line3)
    assertNotNull(match3)
    assertEquals(":app:compileDebugKotlin", match3!!.groupValues[1])
    assertEquals("FROM-CACHE", match3.groupValues[2])

    val line4 = "> Task :app:compileDebugShaders NO-SOURCE"
    val match4 = FlutterBuildManager.TASK_LINE_REGEX.find(line4)
    assertNotNull(match4)
    assertEquals(":app:compileDebugShaders", match4!!.groupValues[1])
    assertEquals("NO-SOURCE", match4.groupValues[2])

    val line5 = "> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED"
    val match5 = FlutterBuildManager.TASK_LINE_REGEX.find(line5)
    assertNotNull(match5)
    assertEquals(":app:checkKotlinGradlePluginConfigurationErrors", match5!!.groupValues[1])
    assertEquals("SKIPPED", match5.groupValues[2])
  }

  @Test
  fun testActionableTasksRegex() {
    val line = "37 actionable tasks: 37 executed"
    val match = FlutterBuildManager.ACTIONABLE_TASKS_REGEX.find(line)
    assertNotNull(match)
    assertEquals("37 actionable tasks: 37 executed", match!!.groupValues[1])

    val line2 = "12 actionable tasks: 10 executed, 2 up-to-date"
    val match2 = FlutterBuildManager.ACTIONABLE_TASKS_REGEX.find(line2)
    assertNotNull(match2)
    assertEquals("12 actionable tasks: 10 executed, 2 up-to-date", match2!!.groupValues[1])
  }

  @Test
  fun testFormatFileSize() {
    assertEquals("0 B", FlutterBuildManager.formatFileSize(0))
    assertEquals("512.0 B", FlutterBuildManager.formatFileSize(512))
    assertEquals("1.0 KB", FlutterBuildManager.formatFileSize(1024))
    assertEquals("45.2 MB", FlutterBuildManager.formatFileSize((45.2 * 1024 * 1024).toLong()))
    assertEquals("1.5 GB", FlutterBuildManager.formatFileSize((1.5 * 1024 * 1024 * 1024).toLong()))
  }

  @Test
  fun testMapPlatformToAbi() {
    assertEquals("arm64-v8a", FlutterBuildManager.mapPlatformToAbi("android-arm64"))
    assertEquals("arm64-v8a", FlutterBuildManager.mapPlatformToAbi("arm64"))
    assertEquals("armeabi-v7a", FlutterBuildManager.mapPlatformToAbi("android-arm"))
    assertEquals("x86_64", FlutterBuildManager.mapPlatformToAbi("android-x64"))
    assertEquals("x86_64", FlutterBuildManager.mapPlatformToAbi("x86_64"))
  }

  @Test
  fun testFindOutputApkDiscoversExistingApk() {
    val tempDir = Files.createTempDirectory("flutter_find_apk_").toFile()
    try {
      val flutterApkDir = File(tempDir, "build/app/outputs/flutter-apk").apply { mkdirs() }
      val apkFile = File(flutterApkDir, "app-debug.apk").apply { writeText("dummy apk content") }

      val discovered = FlutterBuildManager.findOutputApk(tempDir, "debug")
      assertNotNull(discovered)
      assertEquals(apkFile.absolutePath, discovered!!.absolutePath)
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
