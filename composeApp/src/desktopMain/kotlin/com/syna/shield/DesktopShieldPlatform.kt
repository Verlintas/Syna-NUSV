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
package com.syna.shield

import java.awt.Toolkit
import java.nio.file.Paths

actual fun keyPinsPath(): String =
    java.io.File(System.getProperty("user.home") ?: ".", ".syna/syna_key_pins").absolutePath

actual fun shieldEventsPath(): String =
    Paths.get(System.getProperty("user.home") ?: ".", ".syna", "shield_events.jsonl").toString()

actual fun clearNotifications() {
    // 桌面端无系统通知中心清理接口
}

actual fun shieldUsageAccessGranted(): Boolean = false

actual fun requestUsageAccessPermission() {
    // 桌面端无使用情况访问概念
}

actual fun clearOwnClipboard() {
    try {
        // 清空系统剪贴板（桌面端无"仅清除本应用"语义，直接清空）
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            java.awt.datatransfer.StringSelection(""),
            null,
        )
    } catch (e: Exception) {
    }
}
