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

package com.tom.rv2ide.flutter.templates

import com.tom.rv2ide.resources.R
import com.tom.rv2ide.templates.Language
import com.tom.rv2ide.templates.ParameterConstraint
import com.tom.rv2ide.templates.ProjectTemplate
import com.tom.rv2ide.templates.ProjectTemplateData
import com.tom.rv2ide.templates.ProjectTemplateRecipeResult
import com.tom.rv2ide.templates.ProjectVersionData
import com.tom.rv2ide.templates.TemplateRecipe
import com.tom.rv2ide.templates.TextFieldWidget
import com.tom.rv2ide.templates.packageNameParameter
import com.tom.rv2ide.templates.projectNameParameter
import com.tom.rv2ide.templates.stringParameter
import com.tom.rv2ide.utils.AndroidUtils
import com.tom.rv2ide.utils.Environment
import java.io.File

private class FlutterRecipeResult(override val data: ProjectTemplateData) :
    ProjectTemplateRecipeResult

/**
 * Creates a project template for a new Flutter application.
 */
fun flutterProjectTemplate(): ProjectTemplate {
  val projectName = projectNameParameter {
    default = "flutter_app"
  }

  val packageName = packageNameParameter {
    default = "com.example.flutter_app"
  }

  val saveLocation = stringParameter {
    name = com.tom.rv2ide.templates.R.string.wizard_save_location
    default = Environment.PROJECTS_DIR.absolutePath
    constraints = listOf(ParameterConstraint.NONEMPTY, ParameterConstraint.DIRECTORY, ParameterConstraint.EXISTS)
    endIcon = { R.drawable.ic_folder }
  }

  projectName.observe { name ->
    val newPackage = AndroidUtils.appNameToPackageName(name.value, packageName.value)
    packageName.setValue(newPackage)
  }

  val recipe = TemplateRecipe<ProjectTemplateRecipeResult> { executor ->
    val projectDir = File(saveLocation.value, projectName.value)
    if (projectDir.exists() && projectDir.listFiles()?.isNotEmpty() == true) {
      throw IllegalArgumentException("Project directory already exists: ${projectDir.absolutePath}")
    }
    projectDir.mkdirs()

    val rawName = projectName.value
    val flutterName = rawName.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').ifEmpty { "flutter_app" }
    val pkg = packageName.value
    val pkgPath = pkg.replace('.', '/')

    // 1. pubspec.yaml
    executor.save(pubspecYaml(flutterName), File(projectDir, "pubspec.yaml"))

    // 2. analysis_options.yaml
    executor.save(analysisOptionsYaml(), File(projectDir, "analysis_options.yaml"))

    // 3. lib/main.dart
    executor.save(mainDart(), File(projectDir, "lib/main.dart"))

    // 4. README.md
    executor.save(readmeMd(rawName), File(projectDir, "README.md"))

    // 5. .gitignore
    executor.save(gitignoreContent(), File(projectDir, ".gitignore"))

    // 6. android/settings.gradle
    executor.save(androidSettingsGradle(), File(projectDir, "android/settings.gradle"))

    // 7. android/build.gradle
    executor.save(androidBuildGradle(), File(projectDir, "android/build.gradle"))

    // 8. android/gradle.properties
    executor.save(androidGradleProperties(), File(projectDir, "android/gradle.properties"))

    // 9. android/local.properties
    executor.save(androidLocalProperties(), File(projectDir, "android/local.properties"))

    // 10. android/app/build.gradle
    executor.save(androidAppBuildGradle(pkg), File(projectDir, "android/app/build.gradle"))

    // 11. android/app/src/main/AndroidManifest.xml
    executor.save(androidManifestXml(rawName), File(projectDir, "android/app/src/main/AndroidManifest.xml"))

    // 12. android/app/src/main/res/values/styles.xml
    executor.save(androidStylesXml(), File(projectDir, "android/app/src/main/res/values/styles.xml"))

    // 13. android/app/src/main/kotlin/<pkgPath>/MainActivity.kt
    executor.save(mainActivityKt(pkg), File(projectDir, "android/app/src/main/kotlin/$pkgPath/MainActivity.kt"))

    val data = ProjectTemplateData(
        name = rawName,
        projectDir = projectDir,
        version = ProjectVersionData(),
        language = Language.Kotlin,
        useKts = false,
        useNdk = false
    )

    FlutterRecipeResult(data)
  }

  return ProjectTemplate(
      emptyList(),
      R.string.template_flutter,
      R.drawable.compose_empty_activity,
      listOf(
          TextFieldWidget(projectName),
          TextFieldWidget(packageName),
          TextFieldWidget(saveLocation)
      ),
      recipe
  )
}

private fun pubspecYaml(name: String) = """
name: $name
description: "A new Flutter project created with Android Code Studio."
publish_to: 'none'
version: 1.0.0+1

environment:
  sdk: '>=3.0.0 <4.0.0'

dependencies:
  flutter:
    sdk: flutter
  cupertino_icons: ^1.0.8

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: ^3.0.0

flutter:
  uses-material-design: true
""".trimIndent()

private fun analysisOptionsYaml() = """
include: package:flutter_lints/flutter.yaml

linter:
  rules:
    prefer_const_constructors: true
    prefer_const_declarations: true
""".trimIndent()

private fun mainDart() = """
import 'package:flutter/material.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Demo',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const MyHomePage(title: 'Flutter Demo Home Page'),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  int _counter = 0;

  void _incrementCounter() {
    setState(() {
      _counter++;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: Text(widget.title),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            const Text(
              'You have pushed the button this many times:',
            ),
            Text(
              '${'$'}_counter',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _incrementCounter,
        tooltip: 'Increment',
        child: const Icon(Icons.add),
      ),
    );
  }
}
""".trimIndent()

private fun readmeMd(name: String) = """
# $name

A new Flutter project created with Android Code Studio.

## Getting Started

- Edit `lib/main.dart` to develop your UI.
- Click **Run** in the toolbar to launch `flutter run`.
- Use `flutter build apk` to compile an Android APK on-device.
""".trimIndent()

private fun gitignoreContent() = """
.dart_tool/
.packages
build/
.pub-cache/
.pub/
android/local.properties
""".trimIndent()

private fun androidSettingsGradle() = """
pluginManagement {
    def flutterSdkPath = {
        def properties = new Properties()
        file("local.properties").withInputStream { properties.load(it) }
        def flutterSdkPath = properties.getProperty("flutter.sdk")
        assert flutterSdkPath != null : "flutter.sdk not set in local.properties"
        return flutterSdkPath
    }()

    includeBuild("${'$'}flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id "dev.flutter.flutter-plugin-loader" version "1.0.0"
    id "com.android.application" version "8.5.2" apply false
    id "org.jetbrains.kotlin.android" version "1.9.24" apply false
}

include ":app"
""".trimIndent()

private fun androidBuildGradle() = """
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.buildDir = '../build'
subprojects {
    project.buildDir = "${'$'}{rootProject.buildDir}/${'$'}{project.name}"
}
subprojects {
    project.evaluationDependsOn(':app')
}

tasks.register("clean", Delete) {
    delete rootProject.buildDir
}
""".trimIndent()

private fun androidGradleProperties() = """
org.gradle.jvmargs=-Xmx2048M -Dkotlin.daemon.jvm.options="-Xmx512M"
android.useAndroidX=true
android.enableJetifier=true
android.aapt2FromMavenOverride=/data/data/com.tom.rv2ide/files/usr/bin/aapt2
""".trimIndent()

private fun androidLocalProperties() = """
sdk.dir=/data/data/com.tom.rv2ide/files/usr/lib/android-sdk
flutter.sdk=/data/data/com.tom.rv2ide/files/usr/opt/flutter
""".trimIndent()

private fun androidAppBuildGradle(pkg: String) = """
plugins {
    id "com.android.application"
    id "kotlin-android"
    id "dev.flutter.flutter-gradle-plugin"
}

def localProperties = new Properties()
def localPropertiesFile = rootProject.file('local.properties')
if (localPropertiesFile.exists()) {
    localPropertiesFile.withReader('UTF-8') { reader ->
        localProperties.load(reader)
    }
}

def flutterVersionCode = localProperties.getProperty('flutter.versionCode')
if (flutterVersionCode == null) {
    flutterVersionCode = '1'
}

def flutterVersionName = localProperties.getProperty('flutter.versionName')
if (flutterVersionName == null) {
    flutterVersionName = '1.0'
}

android {
    namespace "$pkg"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }

    sourceSets {
        main.java.srcDirs += 'src/main/kotlin'
    }

    defaultConfig {
        applicationId "$pkg"
        minSdk = 24
        targetSdk = 36
        versionCode flutterVersionCode.toInteger()
        versionName flutterVersionName
        ndk {
            abiFilters "arm64-v8a"
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.debug
        }
    }
}

flutter {
    source '../..'
}

dependencies {}
""".trimIndent()

private fun androidManifestXml(label: String) = """
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="$label"
        android:name="${'$'}{applicationName}"
        android:icon="@mipmap/ic_launcher">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:theme="@style/LaunchTheme"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
            android:hardwareAccelerated="true"
            android:windowSoftInputMode="adjustResize">
            <meta-data
              android:name="io.flutter.embedding.android.NormalTheme"
              android:resource="@style/NormalTheme"
              />
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <meta-data
            android:name="flutterEmbedding"
            android:value="2" />
    </application>
</manifest>
""".trimIndent()

private fun androidStylesXml() = """
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="LaunchTheme" parent="@android:style/Theme.Black.NoTitleBar">
        <item name="android:windowBackground">@android:color/white</item>
    </style>
    <style name="NormalTheme" parent="@android:style/Theme.Black.NoTitleBar">
        <item name="android:windowBackground">@android:color/white</item>
    </style>
</resources>
""".trimIndent()

private fun mainActivityKt(pkg: String) = """
package $pkg

import io.flutter.embedding.android.FlutterActivity

class MainActivity: FlutterActivity() {
}
""".trimIndent()
