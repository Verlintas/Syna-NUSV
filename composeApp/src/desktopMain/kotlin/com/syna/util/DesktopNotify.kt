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
