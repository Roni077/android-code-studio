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

package com.tom.rv2ide.plugins

import com.tom.rv2ide.build.config.BuildConfig
import java.io.File
import org.antlr.v4.Tool
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Task to generate ANTLR lexers incrementally with proper input/output caching.
 */
abstract class GenerateLexersTask : DefaultTask() {

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val grammarSourceDir: DirectoryProperty

  @get:OutputDirectory
  abstract val outputSourceDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val grammarDir = grammarSourceDir.get().asFile
    if (!grammarDir.exists()) {
      logger.warn("ANTLR grammars directory does not exist: $grammarDir")
      return
    }

    val subdirs = grammarDir.listFiles()?.filter { it.isDirectory } ?: return
    for (subdir in subdirs) {
      val pck = "${LexerGeneratorPlugin.LEXER_BASE_PACKAGE}.${subdir.name}"
      val files =
          subdir.listFiles()?.filter { it.isFile && it.extension == LexerGeneratorPlugin.EXT_G4 }
              ?.map { it.absolutePath }
              ?: continue

      if (files.isEmpty()) continue

      val outDir = File(outputSourceDir.get().asFile, pck.replace('.', '/'))
      if (!outDir.exists() && !outDir.mkdirs()) {
        logger.error("Failed to create directory $outDir")
        continue
      }

      val options =
          mutableListOf(
              "-o",
              outDir.absolutePath,
              "-package",
              pck,
              "-listener",
              "-visitor",
              "-Xexact-output-dir",
          )
      options.addAll(files)

      Tool(options.toTypedArray()).processGrammarsOnCommandLine()
    }
  }
}

/**
 * Generates lexers from the grammar files in 'src/main/antlr' directory of the project.
 *
 * @author Akash Yadav
 */
class LexerGeneratorPlugin : Plugin<Project> {

  companion object {
    const val LEXER_BASE_PACKAGE = "${BuildConfig.packageName}.lexers"
    const val EXT_G4 = "g4"
  }

  override fun apply(target: Project) {
    val grammarDir = target.file("src/main/antlr")
    if (!grammarDir.exists()) {
      target.logger.warn(
          "${LexerGeneratorPlugin::class.simpleName} has been applied to project '${target.path}' but antlr grammars directory was not found."
      )
      return
    }

    val taskProvider =
        target.tasks.register("generateLexers", GenerateLexersTask::class.java) {
          grammarSourceDir.set(grammarDir)
          outputSourceDir.set(target.file("src/main/java"))
        }

    target.tasks.matching {
      it.name.startsWith("compile") || it.name == "sourcesJar"
    }.configureEach {
      dependsOn(taskProvider)
    }
  }
}
