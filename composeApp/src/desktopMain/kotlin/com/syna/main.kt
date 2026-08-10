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
package com.syna

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ImageIcon

fun main() = application {
    // 全局崩溃日志：启动异常时写入 ~/.syna/crash.log
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val file = java.io.File(
                java.io.File(System.getProperty("user.home") ?: ".", ".syna"),
                "crash.log",
            )
            file.parentFile?.mkdirs()
            java.io.FileWriter(file, true).use { w ->
                w.write(
                    "${System.currentTimeMillis()} [${thread.name}] " +
                        "${throwable::class.java.name}: ${throwable.message}\n" +
                        throwable.stackTraceToString() + "\n\n",
                )
            }
        } catch (e: Exception) {
        }
        throwable.printStackTrace()
    }
    var windowVisible by mutableStateOf(true)
    var trayReady = false

    // 首次关闭窗口时：最小化到托盘（双击托盘图标恢复；托盘菜单可退出）
    fun ensureTray(onOpen: () -> Unit, onExit: () -> Unit) {
        if (trayReady || !SystemTray.isSupported()) return
        trayReady = true
        try {
            val image = ImageIcon(ClassLoader.getSystemResource("icons/logo16.png"))?.image
                ?: java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val menu = PopupMenu()
            menu.add(MenuItem("打开 Syna").apply { addActionListener { onOpen() } })
            menu.add(MenuItem("退出").apply { addActionListener { onExit() } })
            val trayIcon = TrayIcon(image, "Syna 局域网通信").apply {
                isImageAutoSize = true
                popupMenu = menu
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount >= 2) onOpen()
                    }
                })
            }
            SystemTray.getSystemTray().add(trayIcon)
            println("[Syna] 已最小化到系统托盘（双击图标恢复窗口，托盘菜单可退出）")
        } catch (e: Exception) {
            println("[Syna] 托盘初始化失败: ${e.message}")
        }
    }

    Window(
        onCloseRequest = {
            // 关闭按钮 → 最小化到托盘（继续后台收消息）；托盘菜单"退出"才真正退出。
            // 平台无托盘（如 macOS）时不得隐藏窗口——隐藏后无恢复入口，只能强杀重启
            if (SystemTray.isSupported()) {
                windowVisible = false
                ensureTray(onOpen = { windowVisible = true }, onExit = ::exitApplication)
            } else {
                exitApplication()
            }
        },
        visible = windowVisible,
        title = "Syna",
        state = rememberWindowState(width = 420.dp, height = 760.dp),
    ) {
        App()
    }
}
