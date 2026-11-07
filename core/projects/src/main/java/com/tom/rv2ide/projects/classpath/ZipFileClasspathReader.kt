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

package com.tom.rv2ide.projects.classpath

import com.google.common.collect.ImmutableSet
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Lists all classes from classpath(s).
 *
 * @author Akash Yadav
 */
class ZipFileClasspathReader : IClasspathReader {

  companion object {
    private val classCache = ConcurrentHashMap<String, Pair<Long, ImmutableSet<ClassInfo>>>()
  }

  override fun listClasses(files: Collection<File>): ImmutableSet<ClassInfo> {
    val classes = ImmutableSet.builder<ClassInfo>()
    files.forEach { file ->
      if (!file.exists()) {
        return@forEach
      }

      val lastModified = file.lastModified()
      val path = file.absolutePath
      val cached = classCache[path]

      if (cached != null && cached.first == lastModified) {
        classes.addAll(cached.second)
        return@forEach
      }

      val fileClasses = ImmutableSet.builder<ClassInfo>()
      ZipFile(file).use { zipFile ->
        for (entry in zipFile.entries()) {
          if (!entry.name.endsWith(".class")) {
            continue
          }

          var name = entry.name.substringBeforeLast(".class")
          if (name.length <= 1) {
            continue
          }

          if (name.startsWith('/')) {
            name = name.substring(1)
          }

          if (name.contains('/')) {
            name = name.replace('/', '.')
          }

          ClassInfo.create(name)?.also { classInfo -> fileClasses.add(classInfo) }
        }
      }
      val resultSet = fileClasses.build()
      classCache[path] = Pair(lastModified, resultSet)
      classes.addAll(resultSet)
    }

    return classes.build()
  }
}
