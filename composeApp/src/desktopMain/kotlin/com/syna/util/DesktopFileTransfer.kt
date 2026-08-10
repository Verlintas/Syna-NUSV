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

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Paths

actual fun saveReceivedFile(fileName: String, bytes: ByteArray): String {
    val dir = Paths.get(System.getProperty("user.home") ?: ".", "Downloads", "Syna")
    Files.createDirectories(dir)
    val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    // 同名文件加序号（防重复接收覆盖前一份）
    var target = dir.resolve(safeName)
    if (Files.exists(target)) {
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var n = 1
        while (Files.exists(target)) {
            target = dir.resolve("$base($n)$ext")
            n++
        }
    }
    Files.write(target, bytes)
    return target.toString()
}

actual fun readFileBytes(path: String): ByteArray {
    val f = Paths.get(path)
    // 大文件预检（与 Android 侧 200MB 上限一致，防 OOM）
    if (Files.size(f) > com.syna.net.MAX_FILE_SIZE_BYTES) {
        throw IllegalArgumentException("文件过大（上限 ${com.syna.net.MAX_FILE_SIZE_BYTES / 1024 / 1024}MB）")
    }
    return Files.readAllBytes(f)
}

@Composable
actual fun ImagePickerButton(
    onImagePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    // 桌面端复用文件对话框（可继续选择任意文件，功能不裁剪）
    TextButton(
        onClick = { showFileDialog("选择图片", onImagePicked) },
        modifier = modifier,
    ) {
        Text("🖼")
    }
}

@Composable
actual fun FilePickerButton(
    onFilePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = { showFileDialog("选择文件", onFilePicked) },
        modifier = modifier,
    ) {
        Text("📎")
    }
}

/**
 * 在 EDT 上显示 AWT FileDialog（macOS 上非 EDT 线程创建/显示会违反 AWT 线程模型，
 * 可能挂起）；读取仍在后台线程（读大文件不阻塞 EDT）。
 */
private fun showFileDialog(title: String, onPicked: (name: String, bytes: ByteArray) -> Unit) {
    var result: Pair<String, String>? = null
    try {
        java.awt.EventQueue.invokeAndWait {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                result = dir to file
            }
        }
    } catch (e: Exception) {
        println("[Syna:File] 对话框失败: ${e.message}")
        return
    }
    val (dir, file) = result ?: return
    Thread {
        try {
            val f = java.io.File(dir, file)
            onPicked(f.name, f.readBytes())
        } catch (e: Exception) {
            println("[Syna:File] 读取失败: ${e.message}")
        }
    }.start()
}
