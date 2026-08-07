package com.syna.util

import java.awt.SystemTray
import java.awt.TrayIcon


private var trayIcon: TrayIcon? = null

actual fun notifyMessage(title: String, body: String) {
    try {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        var icon = trayIcon
        if (icon == null) {
            val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            icon = TrayIcon(image, "Syna")
            icon.isImageAutoSize = true
            try {
                tray.add(icon)
            } catch (_: Exception) {
            }
            trayIcon = icon
        }
        icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
    } catch (_: Exception) {
    }
}
