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

package com.tom.rv2ide.projects.internal

import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.java.JavaModule
import com.tom.rv2ide.tooling.api.IAndroidProject
import com.tom.rv2ide.tooling.api.IGradleProject
import com.tom.rv2ide.tooling.api.IJavaProject
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.ProjectType
import com.tom.rv2ide.tooling.api.models.AndroidProjectMetadata
import com.tom.rv2ide.tooling.api.models.BasicProjectMetadata
import com.tom.rv2ide.tooling.api.models.JavaProjectMetadata
import com.tom.rv2ide.tooling.api.models.params.StringParameter
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transforms project models from tooling API to the projects API.
 *
 * @author Akash Yadav
 */
internal object WorkspaceModelBuilder {

  private val log = LoggerFactory.getLogger(WorkspaceModelBuilder::class.java)

  fun build(
    projectDir: File,
    project: IProject
  ): WorkspaceImpl? {
    try {
      val allProjects = project.getProjects().get()
      val selectionResult = project.selectProject(StringParameter("")).get()
      check(selectionResult.isSuccessful) {
        "Cannot find root project"
      }

      val rootProject = when (project.getType().get()) {
        ProjectType.Gradle -> transform(project.asGradleProject())
        ProjectType.Android -> transform(project.asAndroidProject())
        else -> throw IllegalStateException(
          "Root project must be either an Android project or a Gradle project"
        )
      }

      return WorkspaceImpl(
        projectDir,
        rootProject,
        CopyOnWriteArrayList(transform(allProjects, project)),
        project.getProjectSyncIssues().get()
      )
    } catch (error: Throwable) {
      log.error("Unable to transform project", error)
      return null
    }
  }

  private fun transform(rootProject: IGradleProject): GradleProject {
    val metadataFuture = rootProject.getMetadata()
    val tasksFuture = rootProject.getTasks()
    java.util.concurrent.CompletableFuture.allOf(metadataFuture, tasksFuture).join()

    val metadata = metadataFuture.get()
    return GradleProject(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,

      // The list will never change, we could make these thread-safe with
      // CopyOnWriteArrayList
      tasks = CopyOnWriteArrayList(tasksFuture.get() ?: listOf()),
    )
  }

  private fun transform(
    project: IAndroidProject
  ): AndroidModule {
    val metadataFuture = project.getMetadata()
    val libraryMapFuture = project.getLibraryMap()
    val variantsFuture = project.getVariants()
    val configuredVariantFuture = project.getConfiguredVariant()
    val tasksFuture = project.getTasks()
    val mainSourceSetFuture = project.getMainSourceSet()
    val bootClasspathsFuture = project.getBootClasspaths()
    val lintCheckJarsFuture = project.getLintCheckJars()

    java.util.concurrent.CompletableFuture.allOf(
      metadataFuture,
      libraryMapFuture,
      variantsFuture,
      configuredVariantFuture,
      tasksFuture,
      mainSourceSetFuture,
      bootClasspathsFuture,
      lintCheckJarsFuture
    ).join()

    val metadata = metadataFuture.get() as AndroidProjectMetadata
    val libraryMap = libraryMapFuture.get()
    val variants = variantsFuture.get()
    val configuredVariant = configuredVariantFuture.get()

    return AndroidModule(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,
      tasks = tasksFuture.get(),
      resourcePrefix = metadata.resourcePrefix,
      namespace = metadata.namespace,
      androidTestNamespace = metadata.androidTestNamespace,
      testFixtureNamespace = metadata.testFixtureNamespace,
      projectType = metadata.androidType,
      mainSourceSet = mainSourceSetFuture.get(),
      flags = metadata.flags,
      compilerSettings = metadata.javaCompileOptions,
      viewBindingOptions = metadata.viewBindingOptions,
      bootClassPaths = bootClasspathsFuture.get(),
      libraries = libraryMap.keys,
      libraryMap = libraryMap,
      lintCheckJars = lintCheckJarsFuture.get(),
      variants = variants,
      configuredVariant = variants.find { it.name == configuredVariant },
      classesJar = metadata.classesJar
    )
  }

  private fun transform(project: IJavaProject): JavaModule {
    val metadataFuture = project.getMetadata()
    val tasksFuture = project.getTasks()
    val contentRootsFuture = project.getContentRoots()
    val dependenciesFuture = project.getDependencies()

    java.util.concurrent.CompletableFuture.allOf(
      metadataFuture,
      tasksFuture,
      contentRootsFuture,
      dependenciesFuture
    ).join()

    val metadata = metadataFuture.get() as JavaProjectMetadata
    return JavaModule(
      name = metadata.name ?: IProject.PROJECT_UNKNOWN,
      description = metadata.description ?: "",
      path = metadata.projectPath,
      projectDir = metadata.projectDir,
      buildDir = metadata.buildDir,
      buildScript = metadata.buildScript,
      tasks = tasksFuture.get(),
      contentRoots = contentRootsFuture.get(),
      dependencies = dependenciesFuture.get(),
      compilerSettings = metadata.compilerSettings,
      classesJar = metadata.classesJar
    )
  }

  private fun transform(modules: List<BasicProjectMetadata>, root: IProject): List<GradleProject> {
    return mutableListOf<GradleProject>().apply {
      for (module in modules) {
        add(createProject(module, root))
      }
    }
  }

  private fun createProject(moduleMetadata: BasicProjectMetadata, root: IProject): GradleProject {
    val selectionResult = root.selectProject(StringParameter(moduleMetadata.projectPath)).get()
    check(selectionResult.isSuccessful) {
      "Selection failed for project '${moduleMetadata.projectPath}' but it is included in all projects."
    }

    val type = root.getType().get() ?: throw java.lang.IllegalStateException("Invalid module data")

    return when (type) {
      ProjectType.Gradle,
      ProjectType.Unknown -> transform(root.asGradleProject())

      ProjectType.Android -> transform(root.asAndroidProject())
      ProjectType.Java -> transform(root.asJavaProject())
    }
  }
}
