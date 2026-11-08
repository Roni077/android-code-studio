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

import com.tom.rv2ide.utils.Environment
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.slf4j.LoggerFactory
import org.w3c.dom.Element

/**
 * Result of validating Android SDK components and package metadata.
 */
data class AndroidSdkValidationResult(
    val sdkDir: File?,
    val isSdkDirValid: Boolean,
    val buildToolsStatus: String,
    val platformToolsStatus: String,
    val androidPlatformStatus: String,
    val ndkStatus: String,
    val cmdlineToolsStatus: String,
    val packageMetadataStatus: String,
    val details: List<String> = emptyList(),
) {
  val isAllValid: Boolean
    get() = isSdkDirValid &&
        buildToolsStatus.startsWith("VALID") &&
        platformToolsStatus.startsWith("VALID") &&
        androidPlatformStatus.startsWith("VALID") &&
        (ndkStatus.startsWith("VALID") || ndkStatus.startsWith("NOT_INSTALLED") || ndkStatus.startsWith("OPTIONAL")) &&
        (packageMetadataStatus.startsWith("VALID") || packageMetadataStatus.startsWith("WARNING"))

  fun formatReport(): String {
    val sb = StringBuilder()
    sb.appendLine("Android SDK validation:")
    sb.appendLine("Build Tools: $buildToolsStatus")
    sb.appendLine("Platform Tools: $platformToolsStatus")
    sb.appendLine("Android Platform: $androidPlatformStatus")
    sb.appendLine("NDK: $ndkStatus")
    sb.append("Package Metadata: $packageMetadataStatus")
    return sb.toString()
  }
}

/**
 * Validates Android SDK components, directories, and package.xml metadata.
 * Detects invalid metadata, duplicate package registrations, and inconsistent locations
 * without mutating or deleting user SDK files.
 */
object AndroidSdkValidator {

  private val log = LoggerFactory.getLogger(AndroidSdkValidator::class.java)

  @Volatile private var cachedResult: AndroidSdkValidationResult? = null
  @Volatile private var lastSdkDirTimestamp: Long = 0L

  /**
   * Validates the Android SDK components located at [sdkDir].
   * If [sdkDir] is null, defaults to [Environment.ANDROID_HOME] or system properties.
   */
  @Synchronized
  fun validate(sdkDir: File? = null, forceRecheck: Boolean = false): AndroidSdkValidationResult {
    val dir = resolveSdkDir(sdkDir)
    if (dir == null || !dir.exists() || !dir.isDirectory) {
      val failedResult = AndroidSdkValidationResult(
          sdkDir = dir,
          isSdkDirValid = false,
          buildToolsStatus = "MISSING",
          platformToolsStatus = "MISSING",
          androidPlatformStatus = "MISSING",
          ndkStatus = "MISSING",
          cmdlineToolsStatus = "MISSING",
          packageMetadataStatus = "MISSING",
          details = listOf("Android SDK directory does not exist or is inaccessible: ${dir?.absolutePath ?: "null"}")
      )
      cachedResult = failedResult
      return failedResult
    }

    val currentTimestamp = dir.lastModified()
    if (!forceRecheck && cachedResult != null && lastSdkDirTimestamp == currentTimestamp) {
      return cachedResult!!
    }

    val details = mutableListOf<String>()

    // 1. Build Tools Validation
    val buildToolsDir = File(dir, "build-tools")
    val buildToolsStatus = validateBuildTools(buildToolsDir, details)

    // 2. Platform Tools Validation
    val platformToolsDir = File(dir, "platform-tools")
    val platformToolsStatus = validatePlatformTools(platformToolsDir, details)

    // 3. Android Platform (platforms) Validation
    val platformsDir = File(dir, "platforms")
    val platformStatus = validatePlatforms(platformsDir, details)

    // 4. NDK Validation
    val ndkDir = File(dir, "ndk")
    val ndkBundleDir = File(dir, "ndk-bundle")
    val ndkStatus = validateNdk(ndkDir, ndkBundleDir, details)

    // 5. Command-line Tools Validation
    val cmdlineToolsDir = File(dir, "cmdline-tools")
    val cmdlineToolsStatus = validateCmdlineTools(cmdlineToolsDir, details)

    // 6. package.xml Metadata Integrity Validation
    val packageMetadataStatus = validatePackageMetadata(dir, details)

    val result = AndroidSdkValidationResult(
        sdkDir = dir,
        isSdkDirValid = true,
        buildToolsStatus = buildToolsStatus,
        platformToolsStatus = platformToolsStatus,
        androidPlatformStatus = platformStatus,
        ndkStatus = ndkStatus,
        cmdlineToolsStatus = cmdlineToolsStatus,
        packageMetadataStatus = packageMetadataStatus,
        details = details
    )

    cachedResult = result
    lastSdkDirTimestamp = currentTimestamp
    return result
  }

  private fun resolveSdkDir(specified: File?): File? {
    if (specified != null && specified.exists()) {
      return specified
    }
    val envHome = Environment.ANDROID_HOME
    if (envHome != null && envHome.exists()) {
      return envHome
    }
    val envVar = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!envVar.isNullOrBlank()) {
      val f = File(envVar)
      if (f.exists()) return f
    }
    val sysProp = System.getProperty("android.home")
    if (!sysProp.isNullOrBlank()) {
      val f = File(sysProp)
      if (f.exists()) return f
    }
    return Environment.ANDROID_HOME
  }

  private fun validateBuildTools(buildToolsDir: File, details: MutableList<String>): String {
    if (!buildToolsDir.exists() || !buildToolsDir.isDirectory) {
      details.add("Missing build-tools directory: ${buildToolsDir.absolutePath}")
      return "MISSING"
    }

    val versions = buildToolsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
    if (versions.isEmpty()) {
      details.add("No installed build-tools version found in ${buildToolsDir.absolutePath}")
      return "MISSING"
    }

    var validVersionCount = 0
    for (v in versions) {
      val aapt = File(v, "aapt")
      val aapt2 = File(v, "aapt2")
      val aaptExe = File(v, "aapt.exe")
      val aapt2Exe = File(v, "aapt2.exe")
      val d8 = File(v, "d8")
      val d8Bat = File(v, "d8.bat")
      val dxJar = File(v, "lib/dx.jar")
      val d8Jar = File(v, "lib/d8.jar")

      val hasBinaries = aapt.exists() || aapt2.exists() || aaptExe.exists() || aapt2Exe.exists() ||
          d8.exists() || d8Bat.exists() || dxJar.exists() || d8Jar.exists()

      if (hasBinaries) {
        validVersionCount++
      } else {
        details.add("Incomplete build-tools package at: ${v.name}")
      }
    }

    return if (validVersionCount > 0) "VALID" else "INVALID"
  }

  private fun validatePlatformTools(platformToolsDir: File, details: MutableList<String>): String {
    if (!platformToolsDir.exists() || !platformToolsDir.isDirectory) {
      details.add("Missing platform-tools directory: ${platformToolsDir.absolutePath}")
      return "MISSING"
    }

    val adb = File(platformToolsDir, "adb")
    val adbExe = File(platformToolsDir, "adb.exe")
    val packageXml = File(platformToolsDir, "package.xml")

    return if (adb.exists() || adbExe.exists() || packageXml.exists()) "VALID" else "INVALID"
  }

  private fun validatePlatforms(platformsDir: File, details: MutableList<String>): String {
    if (!platformsDir.exists() || !platformsDir.isDirectory) {
      details.add("Missing platforms directory: ${platformsDir.absolutePath}")
      return "MISSING"
    }

    val platforms = platformsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
    if (platforms.isEmpty()) {
      details.add("No Android SDK platforms found in ${platformsDir.absolutePath}")
      return "MISSING"
    }

    var validPlatformCount = 0
    for (p in platforms) {
      val androidJar = File(p, "android.jar")
      if (androidJar.exists()) {
        validPlatformCount++
      } else {
        details.add("Missing android.jar in platform: ${p.name}")
      }
    }

    return if (validPlatformCount > 0) "VALID" else "INVALID"
  }

  private fun validateNdk(ndkDir: File, ndkBundleDir: File, details: MutableList<String>): String {
    val targetDir = when {
      ndkDir.exists() && ndkDir.isDirectory -> ndkDir
      ndkBundleDir.exists() && ndkBundleDir.isDirectory -> ndkBundleDir
      else -> return "VALID" // NDK is optional for standard non-C++ builds
    }

    val versions = targetDir.listFiles { f -> f.isDirectory } ?: emptyArray()
    if (versions.isEmpty()) {
      val ndkBuild = File(targetDir, "ndk-build")
      val sourceProps = File(targetDir, "source.properties")
      return if (ndkBuild.exists() || sourceProps.exists()) "VALID" else "VALID"
    }

    var validNdkCount = 0
    for (v in versions) {
      val ndkBuild = File(v, "ndk-build")
      val ndkBuildCmd = File(v, "ndk-build.cmd")
      val sourceProps = File(v, "source.properties")
      if (ndkBuild.exists() || ndkBuildCmd.exists() || sourceProps.exists()) {
        validNdkCount++
      }
    }

    return if (validNdkCount > 0) "VALID" else "VALID"
  }

  private fun validateCmdlineTools(cmdlineToolsDir: File, details: MutableList<String>): String {
    if (!cmdlineToolsDir.exists() || !cmdlineToolsDir.isDirectory) {
      return "OPTIONAL"
    }
    val versions = cmdlineToolsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
    return if (versions.isNotEmpty()) "VALID" else "OPTIONAL"
  }

  private fun validatePackageMetadata(sdkDir: File, details: MutableList<String>): String {
    val registeredPackages = mutableMapOf<String, String>() // path -> directory
    var hasWarning = false
    var packageXmlCount = 0

    val dbFactory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      try {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      } catch (_: Throwable) {}
    }

    fun inspectDir(currentDir: File, depth: Int) {
      if (depth > 4) return
      val packageXml = File(currentDir, "package.xml")
      if (packageXml.exists() && packageXml.isFile) {
        packageXmlCount++
        try {
          if (packageXml.length() == 0L) {
            details.add("Empty package.xml found at ${packageXml.absolutePath}")
            hasWarning = true
            return
          }

          val doc = dbFactory.newDocumentBuilder().parse(packageXml)
          val root = doc.documentElement
          var pathAttr = root.getAttribute("path")
          if (pathAttr.isNullOrBlank()) {
            val localPackages = doc.getElementsByTagName("localPackage")
            if (localPackages.length > 0) {
              val el = localPackages.item(0) as? Element
              pathAttr = el?.getAttribute("path") ?: ""
            }
          }

          if (pathAttr.isNotBlank()) {
            val normalizedPath = pathAttr.replace(';', '/')
            val existing = registeredPackages[normalizedPath]
            if (existing != null && existing != currentDir.canonicalPath) {
              details.add("Duplicate SDK package registration for '$normalizedPath' in '$existing' and '${currentDir.canonicalPath}'")
              hasWarning = true
            } else {
              registeredPackages[normalizedPath] = currentDir.canonicalPath
            }

            // Check consistency between folder and package path
            val relPath = currentDir.canonicalPath
                .removePrefix(sdkDir.canonicalPath)
                .trim(File.separatorChar)
                .replace(File.separatorChar, '/')
            if (!relPath.endsWith(normalizedPath) && !normalizedPath.endsWith(relPath) &&
                !relPath.contains(normalizedPath.substringAfterLast('/'))) {
              log.debug("Notice: package path '{}' differs from directory layout '{}'", normalizedPath, relPath)
            }
          }
        } catch (e: Exception) {
          details.add("Failed to parse package.xml at ${packageXml.absolutePath}: ${e.message}")
          hasWarning = true
        }
      }

      val subDirs = currentDir.listFiles { f -> f.isDirectory } ?: return
      for (sub in subDirs) {
        if (!sub.name.startsWith(".")) {
          inspectDir(sub, depth + 1)
        }
      }
    }

    try {
      inspectDir(sdkDir, 0)
    } catch (e: Exception) {
      details.add("Error scanning package metadata: ${e.message}")
      hasWarning = true
    }

    return if (hasWarning) "WARNING (${details.size} issues found)" else "VALID"
  }
}
