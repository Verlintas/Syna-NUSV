/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.syna.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.syna.SynaApp

@Composable
actual fun notificationPermissionState(): PermissionState {
    var state by remember { mutableStateOf(check()) }
    // 每次重组时刷新（从系统设置返回后）
    state = check()
    return state
}

private fun check(): PermissionState {
    if (Build.VERSION.SDK_INT < 33) return PermissionState.GRANTED
    return when (ContextCompat.checkSelfPermission(SynaApp.context, Manifest.permission.POST_NOTIFICATIONS)) {
        PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
        else -> PermissionState.DENIED
    }
}

@Composable
actual fun rememberNotificationPermissionRequester(): () -> Unit {
    var requestCode by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestCode++
    }
    return {
        if (Build.VERSION.SDK_INT >= 33) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
