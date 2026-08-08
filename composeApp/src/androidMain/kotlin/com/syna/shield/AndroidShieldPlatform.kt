package com.syna.shield

import android.content.ClipData
import android.content.ClipDescription
import com.syna.SynaApp
import java.io.File

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

actual fun clearOwnClipboard() {
    try {
        val cm = SynaApp.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        // Android 10+ 仅清除本应用写入的剪贴板内容（不影响其他应用）
        cm.clearPrimaryClip()
    } catch (e: Exception) {
    }
}
