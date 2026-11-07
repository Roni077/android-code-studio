/************************************************************************************
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 **************************************************************************************/
package com.tom.rv2ide.utils;

/**
 * Fast Java identifier part/start character classification.
 * Optimized with zero-allocation ASCII fast-paths and Unicode fallback.
 */
public class JavaCharacter {

  /** 
   * Retained for backward binary compatibility. No-op since lookup is zero-allocation.
   */
  public static void initMap() {
    // No-op
  }

  /**
   * @param key Character
   * @return Whether an identifier part
   * @see Character#isJavaIdentifierPart(char)
   */
  public static boolean isJavaIdentifierPart(int key) {
    if (key >= 'a' && key <= 'z') return true;
    if (key >= 'A' && key <= 'Z') return true;
    if (key >= '0' && key <= '9') return true;
    if (key == '_' || key == '$') return true;
    if (key < 128 || key > 65535) return false;
    return Character.isJavaIdentifierPart((char) key);
  }

  /**
   * @param key Character
   * @return Whether an identifier start
   * @see Character#isJavaIdentifierStart(char)
   */
  public static boolean isJavaIdentifierStart(int key) {
    if (key >= 'a' && key <= 'z') return true;
    if (key >= 'A' && key <= 'Z') return true;
    if (key == '_' || key == '$') return true;
    if (key < 128 || key > 65535) return false;
    return Character.isJavaIdentifierStart((char) key);
  }
}
