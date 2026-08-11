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

import android.content.ClipData
import android.content.ClipDescription
import com.syna.SynaApp
import java.io.File

actual fun keyPinsPath(): String =
    java.io.File(com.syna.SynaApp.context.filesDir, "syna_key_pins").absolutePath

actual fun shieldEventsPath(): String =
    File(SynaApp.context.filesDir, "shield_events.jsonl").absolutePath

actual fun clearNotifications() {
    try {
        val nm = SynaApp.context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        nm.cancelAll()
    } catch (e: Exception) {
    }
}

actual fun shieldUsageAccessGranted(): Boolean {
    return try {
        val appOps = SynaApp.context.getSystemService(android.app.AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            SynaApp.context.packageName,
        )
        mode == android.app.AppOpsManager.MODE_ALLOWED ||
            mode == android.app.AppOpsManager.MODE_DEFAULT
    } catch (e: Exception) {
        false
    }
}

actual fun requestUsageAccessPermission() {
    try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        // 定位到本应用（API 30+ 支持 EXTRA_APP_PACKAGE；低版本忽略该参数仍打开列表）
        try {
            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, SynaApp.context.packageName)
        } catch (e: Exception) {
        }
        SynaApp.context.startActivity(intent)
    } catch (e: Exception) {
    }
}

actual fun clearOwnClipboard() {
    try {
        val cm = SynaApp.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        // Android 10+ 仅清除本应用写入的剪贴板内容（不影响其他应用）
        cm.clearPrimaryClip()
    } catch (e: Exception) {
    }
}
