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

package com.tom.rv2ide.flutter.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import org.slf4j.LoggerFactory

class FlutterBuildServiceConnection : ServiceConnection {

  var onConnected: ((FlutterBuildService) -> Unit)? = null
  var onDisconnected: (() -> Unit)? = null

  companion object {
    private val log = LoggerFactory.getLogger(FlutterBuildServiceConnection::class.java)
  }

  override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
    val binder = service as? FlutterServiceBinder
    val s = binder?.service
    if (s != null) {
      log.info("Connected to FlutterBuildService, updating BuildService in Lookup")
      Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, s)
      onConnected?.invoke(s)
    }
  }

  override fun onServiceDisconnected(name: ComponentName?) {
    log.info("Disconnected from FlutterBuildService")
    onConnected = null
    onDisconnected?.invoke()
  }
}
