package com.syna.util

import androidx.compose.runtime.Composable

enum class PermissionState {
    GRANTED,
    DENIED,
    NOT_APPLICABLE,
}

/** 当前通知权限状态（Android 动态权限；桌面端恒为 NOT_APPLICABLE） */
@Composable
expect fun notificationPermissionState(): PermissionState

/** 触发系统权限请求（Android）；桌面端为 no-op */
@Composable
expect fun rememberNotificationPermissionRequester(): () -> Unit
