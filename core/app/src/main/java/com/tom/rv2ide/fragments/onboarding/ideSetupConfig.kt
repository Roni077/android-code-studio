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

package com.tom.rv2ide.fragments.onboarding

import com.tom.rv2ide.app.configuration.CpuArch

private val ARM_ONLY = arrayOf(CpuArch.AARCH64, CpuArch.ARM)
private val ALL = arrayOf(CpuArch.AARCH64, CpuArch.ARM, CpuArch.X86_64)

/**
 * Android SDK versions.
 *
 * @author Akash Yadav
 */
enum class SdkVersion(val version: String, val supportedArchs: Array<CpuArch>) {
  SDK_33_0_1("33.0.1", ARM_ONLY),
  SDK_34_0_0("34.0.0", ARM_ONLY),
  SDK_34_0_1("34.0.1", ARM_ONLY),
  SDK_34_0_3("34.0.3", ARM_ONLY),
  SDK_34_0_4("34.0.4", ALL),
  SDK_35_0_1("35.0.1", ALL),
  SDK_35_0_2("35.0.2", ALL),
  SDK_36_0_0("36.0.0", ALL),
  SDK_36_0_1("36.0.1", ALL),
  SDK_37_0_0("37.0.0", ALL),
  ;

  val displayName = "SDK $version"

  companion object {
    @JvmStatic
    fun fromDisplayName(displayName: CharSequence) =
        entries.firstOrNull { it.displayName.contentEquals(displayName) } ?: SDK_35_0_2

    @JvmStatic
    fun fromVersion(version: CharSequence) =
        entries.firstOrNull { it.version.contentEquals(version) } ?: SDK_35_0_2

    @JvmStatic
    fun parseVersionFromDisplayName(displayName: CharSequence): String {
      val text = displayName.toString().trim()
      val match = entries.firstOrNull { it.displayName.contentEquals(displayName) }
      if (match != null) {
        return match.version
      }
      return if (text.startsWith("SDK ", ignoreCase = true)) {
        text.substring(4).trim()
      } else {
        text
      }
    }
  }
}

/**
 * JDK versions.
 *
 * @author Akash Yadav
 */
enum class JdkVersion(val version: String) {

  JDK_17("17"),
  JDK_21("21"),
  ;

  val displayName = "JDK $version"

  companion object {

    @JvmStatic
    fun fromDisplayName(displayName: CharSequence) =
        entries.first { it.displayName.contentEquals(displayName) }

    @JvmStatic
    fun fromVersion(version: CharSequence) = entries.first { it.version.contentEquals(version) }
  }
}

/**
 * NDK versions.
 *
 * @author Mohammed-baqer-null
 */
enum class NdkVersion(val version: String) {

  NDK_DISABLE("0"),
  NDK_28("28.2.13676358"),
  NDK_29("29.0.14033849"),
  NDK_30("30.0.16248370"),
  ;

  val displayName = if (version == "0") "Skip" else "NDK $version"

  companion object {

    @JvmStatic
    fun fromDisplayName(displayName: CharSequence): NdkVersion =
        entries.firstOrNull { it.displayName.contentEquals(displayName) } ?: NDK_DISABLE

    @JvmStatic
    fun fromVersion(version: CharSequence): NdkVersion =
        entries.firstOrNull { it.version.contentEquals(version) } ?: NDK_DISABLE

    @JvmStatic
    fun parseVersionFromDisplayName(displayName: CharSequence): String {
      val text = displayName.toString().trim()
      if (text.equals("Skip", ignoreCase = true) || text == "0") {
        return "0"
      }
      val match = entries.firstOrNull { it.displayName.contentEquals(displayName) }
      if (match != null) {
        return match.version
      }
      return if (text.startsWith("NDK ", ignoreCase = true)) {
        text.substring(4).trim()
      } else {
        text
      }
    }
  }
}
