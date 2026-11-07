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

package com.tom.rv2ide.utils

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TypefaceUtilsTest {

  private lateinit var tempHome: File
  private var originalHome: File? = null
  private var originalTermuxDir: File? = null
  private var originalTermuxFontsDir: File? = null
  private var originalTermuxFontFile: File? = null

  @Before
  fun setUp() {
    originalHome = Environment.HOME
    originalTermuxDir = Environment.TERMUX_DIR
    originalTermuxFontsDir = Environment.TERMUX_FONTS_DIR
    originalTermuxFontFile = Environment.TERMUX_FONT_FILE

    tempHome = Files.createTempDirectory("acs_termux_test_").toFile()
    Environment.HOME = tempHome
    Environment.TERMUX_DIR = File(tempHome, ".termux")
    Environment.TERMUX_FONTS_DIR = File(Environment.TERMUX_DIR, "fonts")
    Environment.TERMUX_FONT_FILE = File(Environment.TERMUX_DIR, "font.ttf")
  }

  @After
  fun tearDown() {
    tempHome.deleteRecursively()
    Environment.HOME = originalHome
    Environment.TERMUX_DIR = originalTermuxDir
    Environment.TERMUX_FONTS_DIR = originalTermuxFontsDir
    Environment.TERMUX_FONT_FILE = originalTermuxFontFile
  }

  @Test
  fun testTermuxPathsResolution() {
    val termuxDir = TypefaceUtils.getTermuxDir()
    val fontsDir = TypefaceUtils.getTermuxFontsDir()
    val fontFile = TypefaceUtils.getTermuxFontFile()

    assertEquals(File(tempHome, ".termux"), termuxDir)
    assertEquals(File(tempHome, ".termux/fonts"), fontsDir)
    assertEquals(File(tempHome, ".termux/font.ttf"), fontFile)
    assertTrue("Fonts dir should be created if not exists", fontsDir.exists())
  }

  @Test
  fun testGetAvailableTerminalFontsFiltersAndSorts() {
    val fontsDir = TypefaceUtils.getTermuxFontsDir()
    File(fontsDir, "FiraCode.ttf").writeText("dummy font")
    File(fontsDir, "Hack.otf").writeText("dummy font")
    File(fontsDir, "readme.txt").writeText("not a font")

    // Place active font.ttf in .termux - should NOT appear as a custom font choice
    val termuxDir = TypefaceUtils.getTermuxDir()
    File(termuxDir, "font.ttf").writeText("active font")
    File(termuxDir, "CascadiaMono.ttf").writeText("termux font")

    val available = TypefaceUtils.getAvailableTerminalFonts()
    assertEquals(listOf("CascadiaMono.ttf", "FiraCode.ttf", "Hack.otf"), available)
  }

  @Test
  fun testApplyTerminalFontCopiesFile() {
    val fontsDir = TypefaceUtils.getTermuxFontsDir()
    val fontSource = File(fontsDir, "CustomMono.ttf")
    fontSource.writeText("sample-font-bytes")

    val applied = TypefaceUtils.applyTerminalFont("CustomMono.ttf")
    assertTrue(applied)

    val targetFont = TypefaceUtils.getTermuxFontFile()
    assertTrue(targetFont.exists())
    assertEquals("sample-font-bytes", targetFont.readText())

    // Clearing font removes font.ttf
    val cleared = TypefaceUtils.applyTerminalFont(null)
    assertTrue(cleared)
    assertFalse(targetFont.exists())
  }
}
