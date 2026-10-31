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

package com.tom.rv2ide.backup.models

enum class ArchiveFormat(val displayName: String, val extension: String) {
    TAR_GZ("Termux Tarball (.tar.gz)", ".tar.gz"),
    TAR_XZ("Termux Tarball (.tar.xz)", ".tar.xz"),
    ZIP("ACS Archive (.zip)", ".zip"),
    JSON("Preferences File (.json)", ".json")
}

data class BackupInspection(
    val isValid: Boolean,
    val manifest: BackupManifest? = null,
    val format: ArchiveFormat = ArchiveFormat.TAR_GZ,
    val availableCategories: Set<BackupCategory> = emptySet(),
    val isStandaloneJson: Boolean = false,
    val isTermuxTarball: Boolean = false,
    val preferenceFilesCount: Int = 0,
    val totalUncompressedBytes: Long = 0L,
    val errorMessage: String? = null
)
