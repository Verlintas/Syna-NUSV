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
