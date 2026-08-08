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
    val target = dir.resolve(safeName)
    Files.write(target, bytes)
    return target.toString()
}

actual fun readFileBytes(path: String): ByteArray = Files.readAllBytes(Paths.get(path))

@Composable
actual fun ImagePickerButton(
    onImagePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    // 桌面端复用文件对话框（可继续选择任意文件，功能不裁剪）
    TextButton(
        onClick = {
            Thread {
                val dialog = FileDialog(null as Frame?, "选择图片", FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    try {
                        val f = java.io.File(dir, file)
                        onImagePicked(f.name, f.readBytes())
                    } catch (e: Exception) {
                        println("[Syna:Image] 读取失败: ${e.message}")
                    }
                }
            }.start()
        },
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
        onClick = {
            Thread {
                val dialog = FileDialog(null as Frame?, "选择文件", FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    try {
                        val f = java.io.File(dir, file)
                        onFilePicked(f.name, f.readBytes())
                    } catch (e: Exception) {
                        println("[Syna:File] 读取失败: ${e.message}")
                    }
                }
            }.start()
        },
        modifier = modifier,
    ) {
        Text("📎")
    }
}
