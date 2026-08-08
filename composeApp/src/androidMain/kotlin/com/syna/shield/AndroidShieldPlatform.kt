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
