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

package com.tom.rv2ide.templates.android

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.tom.androidcodestudio.project.manager.ProjectManager
import com.tom.rv2ide.flutter.project.FlutterProjectHelper
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.templates.AtcInterface
import com.tom.rv2ide.templates.preferences.Options
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android Code Studio template for creating standard Flutter applications from assets.
 */
class FlutterApp : Template {

  override val displayName = "Flutter App"
  override val templateType = Template.TemplateType.OTHER
  override val iconRes = R.drawable.flutter_app

  private val ASSETS_BASE_PATH = "FlutterApp"

  override fun configureOptions() {
    Options.resetToDefaults()
    Options.OPT_IS_NATIVE_CPP = false
    Options.OPT_BUILD_SYSTEM_USE_CMAKE = false
  }

  override suspend fun create(
      context: Context,
      listener: AtcInterface.TemplateCreationListener?,
      options: TemplateOptions,
  ) {
    withContext(Dispatchers.IO) {
      try {
        Log.d(TAG, "Creating Flutter project: ${options.projectName}")

        withContext(Dispatchers.Main) {
          Toast.makeText(context, "Creating Flutter App...", Toast.LENGTH_SHORT).show()
        }

        val rawName = options.projectName
        val flutterName = rawName.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').ifEmpty { "flutter_app" }
        val pkg = options.packageId
        val pkgPath = pkg.replace('.', '/')
        val projectRoot = File(options.saveLocation, options.projectName)

        if (projectRoot.exists() && projectRoot.listFiles()?.isNotEmpty() == true) {
          throw IllegalArgumentException("Project directory already exists: ${projectRoot.absolutePath}")
        }
        projectRoot.mkdirs()

        val replacements = mapOf(
            "{{PROJECT_NAME}}" to flutterName,
            "{{PACKAGE_NAME}}" to pkg,
            "{{PACKAGE_PATH}}" to pkgPath
        )

        // 1. Copy all template assets from assets/FlutterApp
        copyAssetFolderWithReplacements(context, ASSETS_BASE_PATH, projectRoot, replacements)

        // 2. Write Android MainActivity
        val mainActivityFile = File(projectRoot, "android/app/src/main/kotlin/$pkgPath/MainActivity.kt")
        mainActivityFile.parentFile?.mkdirs()
        mainActivityFile.writeText(
            """
            package $pkg

            import io.flutter.embedding.android.FlutterActivity

            class MainActivity: FlutterActivity() {
            }
            """.trimIndent()
        )

        // 3. Write android/local.properties
        val localPropsFile = File(projectRoot, "android/local.properties")
        val flutterSdk = Environment.FLUTTER_HOME?.absolutePath
            ?: File(Environment.PREFIX, "opt/flutter").absolutePath
        val sdkDir = Environment.ANDROID_HOME?.absolutePath
            ?: File(Environment.HOME, "android-sdk").absolutePath

        localPropsFile.parentFile?.mkdirs()
        localPropsFile.writeText(
            """
            sdk.dir=$sdkDir
            flutter.sdk=$flutterSdk
            flutter.buildMode=debug
            flutter.versionName=1.0.0
            flutter.versionCode=1
            """.trimIndent() + "\n"
        )

        // 4. Ensure gradlew is executable
        val gradlewFile = File(projectRoot, "android/gradlew")
        if (gradlewFile.exists()) {
          gradlewFile.setExecutable(true, false)
        }

        // 5. Inject Android Code Studio optimizations (AAPT2 override, ABI filter)
        FlutterProjectHelper.configureProjectForAndroidCS(projectRoot)

        // 6. Register with ProjectManager
        val projectManager = ProjectManager.getInstance(context)
        val projectInfo = projectManager.createTemplateProjectInfo(
            projectName = options.projectName,
            projectDir = projectRoot.absolutePath,
            projectType = "Flutter App",
        )
        projectManager.addProjectBlocking(projectInfo)

        Log.d(TAG, "Flutter project created successfully at ${projectRoot.absolutePath}")

        withContext(Dispatchers.Main) {
          listener?.onTemplateCreated(
              true,
              "Flutter project created successfully at ${projectRoot.absolutePath}",
          )
          listener?.onTemplateCreated(true, "", projectRoot)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error creating Flutter project", e)
        withContext(Dispatchers.Main) {
          listener?.onTemplateCreated(false, "Error creating Flutter project: ${e.message}")
        }
      }
    }
  }

  private fun copyAssetFolderWithReplacements(
      context: Context,
      assetPath: String,
      destDir: File,
      replacements: Map<String, String>,
  ) {
    val assetManager = context.assets
    val files = assetManager.list(assetPath) ?: emptyArray()

    if (files.isEmpty()) {
      // It's a file
      copyAssetFileWithReplacements(context, assetPath, destDir, replacements)
    } else {
      destDir.mkdirs()
      for (fileName in files) {
        val subAssetPath = "$assetPath/$fileName"
        val outName = if (fileName == "gitignore") ".gitignore" else fileName
        val subDestFile = File(destDir, outName)
        copyAssetFolderWithReplacements(context, subAssetPath, subDestFile, replacements)
      }
    }
  }

  private fun copyAssetFileWithReplacements(
      context: Context,
      assetPath: String,
      destFile: File,
      replacements: Map<String, String>,
  ) {
    destFile.parentFile?.mkdirs()

    val isText = isTextAsset(assetPath)
    if (isText) {
      val content = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
      var replacedContent = content
      for ((key, value) in replacements) {
        replacedContent = replacedContent.replace(key, value)
      }
      destFile.writeText(replacedContent, Charsets.UTF_8)
    } else {
      context.assets.open(assetPath).use { input ->
        FileOutputStream(destFile).use { output ->
          input.copyTo(output)
        }
      }
    }
  }

  private fun isTextAsset(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".yaml") ||
        lower.endsWith(".yml") ||
        lower.endsWith(".dart") ||
        lower.endsWith(".gradle") ||
        lower.endsWith(".properties") ||
        lower.endsWith(".xml") ||
        lower.endsWith(".md") ||
        lower.endsWith(".txt") ||
        lower.endsWith(".json") ||
        lower.endsWith("gitignore") ||
        lower.endsWith("gradlew") ||
        lower.endsWith("gradlew.bat")
  }

  companion object {
    private const val TAG = "FlutterApp"
  }
}
