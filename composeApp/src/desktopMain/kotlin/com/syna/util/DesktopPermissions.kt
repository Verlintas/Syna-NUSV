package com.syna.util

import androidx.compose.runtime.Composable

@Composable
actual fun notificationPermissionState(): PermissionState = PermissionState.NOT_APPLICABLE

@Composable
actual fun rememberNotificationPermissionRequester(): () -> Unit = {}
