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


import com.tom.rv2ide.build.config.BuildConfig

@Suppress("JavaPluginLanguageLevel")
plugins {
  id("com.github.johnrengelman.shadow") version "8.1.1"
  id("java-library")
  alias(libs.plugins.ksp)
  id("org.jetbrains.kotlin.jvm")
}



tasks.withType<Jar> {
  manifest { attributes("Main-Class" to "${BuildConfig.packageName}.tooling.impl.Main") }
}

tasks.register<Delete>("deleteExistingJarFiles") {
  delete(layout.buildDirectory.dir("libs"))
}

tasks.register<Copy>("copyJar") {
  dependsOn("shadowJar")
  val libsDir = layout.buildDirectory.dir("libs")
  from(libsDir)
  into(libsDir)
  include("*-all.jar")
  rename { "tooling-api-all.jar" }
}

tasks.named("jar").configure {
  dependsOn("deleteExistingJarFiles")
  finalizedBy("shadowJar")
}

tasks.named("shadowJar").configure {
  finalizedBy("copyJar")
}

dependencies {

  implementation("io.hotmoka:toml4j:0.7.3")
  
  ksp(libs.auto.service.ksp)
  compileOnly(libs.google.auto.service.annotations)

  api(projects.tooling.api)

  implementation(projects.utilities.buildInfo)
  implementation(projects.utilities.shared)

  implementation(libs.common.jkotlin)
  implementation(libs.xml.xercesImpl)
  implementation(libs.xml.apis)
  implementation(libs.tooling.gradleApi)

  // No additional deps for proxy (socket-based)


  runtimeOnly(libs.tooling.slf4j)
}
