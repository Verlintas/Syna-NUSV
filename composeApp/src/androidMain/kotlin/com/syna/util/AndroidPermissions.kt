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
