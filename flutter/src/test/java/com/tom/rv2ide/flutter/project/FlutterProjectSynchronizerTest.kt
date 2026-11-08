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

package com.tom.rv2ide.flutter.project

import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlutterProjectSynchronizerTest {

  @Test
  fun testNonFlutterProjectFails() {
    val tempDir = Files.createTempDirectory("not_flutter_").toFile()
    try {
      val result = FlutterProjectSynchronizer.prepareSync(tempDir)
      assertFalse(result.isSuccessful)
      assertTrue(result.errorMessage?.contains("Not a Flutter project") == true)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun testFlutterProjectMissingAndroidFailsWithClearMessage() {
    val tempDir = Files.createTempDirectory("flutter_no_android_").toFile()
    try {
      File(tempDir, "pubspec.yaml").writeText("name: sample_app\nversion: 1.2.3+4\n")
      val result = FlutterProjectSynchronizer.prepareSync(tempDir)
      assertFalse(result.isSuccessful)
      assertTrue(result.errorMessage?.contains("No 'android' directory found") == true)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun testEnsureLocalPropertiesCreatesAndUpdates() {
    val tempDir = Files.createTempDirectory("flutter_local_props_").toFile()
    try {
      val androidDir = File(tempDir, "android").apply { mkdirs() }
      val mockSdk = File(tempDir, "mock_sdk").apply { mkdirs() }
      val mockFlutter = File(tempDir, "mock_flutter").apply { mkdirs() }

      FlutterProjectSynchronizer.ensureLocalProperties(
          androidDir = androidDir,
          androidSdkDir = mockSdk,
          flutterSdkDir = mockFlutter,
          versionName = "2.0.1",
          versionCode = 42
      )

      val propsFile = File(androidDir, "local.properties")
      assertTrue(propsFile.exists())

      val props = Properties()
      propsFile.inputStream().use { props.load(it) }

      assertEquals(mockSdk.absolutePath, props.getProperty("sdk.dir"))
      assertEquals(mockFlutter.absolutePath, props.getProperty("flutter.sdk"))
      assertEquals("2.0.1", props.getProperty("flutter.versionName"))
      assertEquals("42", props.getProperty("flutter.versionCode"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun testSuccessfulPreparationAndMetadataExtraction() {
    val tempDir = Files.createTempDirectory("flutter_full_").toFile()
    try {
      File(tempDir, "pubspec.yaml").writeText(
          """
          name: demo_flutter_app
          description: A demo Flutter app.
          version: 1.0.5+12
          environment:
            sdk: '>=3.0.0 <4.0.0'
          """.trimIndent()
      )
      val androidDir = File(tempDir, "android").apply { mkdirs() }
      val appDir = File(androidDir, "app").apply { mkdirs() }
      File(appDir, "build.gradle").writeText(
          """
          android {
              namespace 'com.example.demoflutter'
              defaultConfig {
                  applicationId "com.example.demoflutter"
              }
          }
          """.trimIndent()
      )
      val wrapperDir = File(androidDir, "gradle/wrapper").apply { mkdirs() }
      File(wrapperDir, "gradle-wrapper.properties").writeText(
          "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n"
      )

      val logs = mutableListOf<String>()
      val result = FlutterProjectSynchronizer.prepareSync(tempDir, forcePubGet = false, onLog = { logs.add(it) })

      assertTrue(result.isSuccessful)
      assertNotNull(result.projectInfo)
      assertEquals("demo_flutter_app", result.projectInfo?.name)
      assertEquals("1.0.5", result.projectInfo?.versionName)
      assertEquals(12, result.projectInfo?.versionCode)
      assertEquals("com.example.demoflutter", result.projectInfo?.applicationId)

      assertTrue(logs.any { it.contains("Project: demo_flutter_app") })
      assertTrue(logs.any { it.contains("Version: 1.0.5+12") })
      assertTrue(logs.any { it.contains("Application ID: com.example.demoflutter") })
    } finally {
      tempDir.deleteRecursively()
    }
  }
}
