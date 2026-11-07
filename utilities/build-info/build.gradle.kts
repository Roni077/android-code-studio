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

import com.android.SdkConstants
import com.tom.rv2ide.build.config.AGP_VERSION_MINIMUM
import com.tom.rv2ide.build.config.BuildConfig
import com.tom.rv2ide.build.config.ProjectConfig
import com.tom.rv2ide.build.config.replaceContents
import com.tom.rv2ide.build.config.simpleVersionName
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
  //noinspection JavaPluginLanguageLevel
  id("java-library")
}

description = "Information about the AndroidIDE build"

abstract class GenerateBuildInfoTask : DefaultTask() {

  @get:InputFile
  abstract val templateFile: RegularFileProperty

  @get:OutputFile
  abstract val destinationFile: RegularFileProperty

  @get:Input
  abstract val packageName: Property<String>

  @get:Input
  abstract val versionName: Property<String>

  @get:Input
  abstract val simpleVersionName: Property<String>

  @get:Input
  abstract val downloadVersion: Property<String>

  @get:Input
  abstract val agpToolingVersion: Property<String>

  @TaskAction
  fun generate() {
    val dest = destinationFile.get().asFile
    dest.parentFile.mkdirs()
    val candidates = arrayOf(
      "PACKAGE_NAME" to packageName.get(),
      "MVN_GROUP_ID" to "io.github.mohammed-baqer-null",
      "VERSION_NAME" to versionName.get(),
      "VERSION_NAME_SIMPLE" to simpleVersionName.get(),
      "VERSION_NAME_DOWNLOAD" to downloadVersion.get(),
      "REPO_HOST" to ProjectConfig.REPO_HOST,
      "REPO_OWNER" to ProjectConfig.REPO_OWNER,
      "REPO_NAME" to ProjectConfig.REPO_NAME,
      "PROJECT_SITE" to ProjectConfig.PROJECT_SITE,
      "AGP_VERSION_MININUM" to AGP_VERSION_MINIMUM,
      "AGP_VERSION_LATEST" to agpToolingVersion.get(),
      "AGP_VERSION_GRADLE_LATEST" to SdkConstants.GRADLE_LATEST_VERSION
    )
    templateFile.get().asFile.replaceContents(
      dest = dest,
      comment = "//",
      candidates = candidates
    )
  }
}

val buildInfoGenDir: Provider<Directory> = project.layout.buildDirectory.dir("generated/buildInfo")

sourceSets { getByName("main").java.srcDir(buildInfoGenDir) }

val buildInfoPath = "com/tom/rv2ide/buildinfo/BuildInfo.java"
val buildInfoInFile = layout.projectDirectory.file("src/main/java/${buildInfoPath}.in")
val buildInfoOutFile = buildInfoGenDir.map { it.file(buildInfoPath) }

val generateTask = tasks.register("generateBuildInfo", GenerateBuildInfoTask::class.java) {
  templateFile.set(buildInfoInFile)
  destinationFile.set(buildInfoOutFile)
  packageName.set(BuildConfig.packageName)
  versionName.set(rootProject.version.toString())
  simpleVersionName.set(rootProject.simpleVersionName)
  downloadVersion.set("1.0.0")
  agpToolingVersion.set(libs.versions.agp.tooling.get())
}

tasks.withType<JavaCompile>().configureEach { dependsOn(generateTask) }
tasks.withType<Jar>().configureEach { dependsOn(generateTask) }
