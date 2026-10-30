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

import com.google.gson.annotations.SerializedName

data class BackupManifest(
    @SerializedName("version")
    val version: Int = CURRENT_VERSION,

    @SerializedName("created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @SerializedName("app_version")
    val appVersion: String,

    @SerializedName("package_name")
    val packageName: String,

    @SerializedName("device_info")
    val deviceInfo: String,

    @SerializedName("included_categories")
    val includedCategories: List<String>,

    @SerializedName("file_count")
    val fileCount: Int = 0,

    @SerializedName("checksum")
    val checksum: String = ""
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val MANIFEST_FILE_NAME = "backup_manifest.json"
    }
}
