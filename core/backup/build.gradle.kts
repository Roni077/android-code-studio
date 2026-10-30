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

import com.tom.rv2ide.build.config.BuildConfig

plugins {
  id("com.android.library")
  id("kotlin-android")
  id("kotlin-parcelize")
}

android {
  namespace = "${BuildConfig.packageName}.backup"
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.resources)
  implementation(projects.utilities.preferences)
  implementation(projects.utilities.buildInfo)

  implementation(libs.androidx.annotation)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.preference)
  implementation(libs.google.material)
  implementation(libs.google.gson)
  implementation(libs.common.kotlin.coroutines.android)
  implementation("org.apache.commons:commons-compress:1.21")

  testImplementation(libs.tests.junit)
}
