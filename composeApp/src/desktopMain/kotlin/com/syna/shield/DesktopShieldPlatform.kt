package com.syna.shield

import java.awt.Toolkit
import java.nio.file.Paths

actual fun shieldEventsPath(): String =
    Paths.get(System.getProperty("user.home") ?: ".", ".syna", "shield_events.jsonl").toString()

actual fun clearNotifications() {
    // 桌面端无系统通知中心清理接口
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
