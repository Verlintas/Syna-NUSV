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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.provider.OpenableColumns
import com.syna.SynaApp
import java.io.File

actual fun saveReceivedFile(fileName: String, bytes: ByteArray): String {
    val dir = File(SynaApp.context.filesDir, "syna_received")
    dir.mkdirs()
    val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val target = File(dir, safeName)
    target.writeBytes(bytes)
    return target.absolutePath
}

actual fun readFileBytes(path: String): ByteArray = File(path).readBytes()

@Composable
actual fun FilePickerButton(
    onFilePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    val context = SynaApp.context
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (name, size) = try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    var n: String? = null
                    var s = -1L
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) n = cursor.getString(nameIdx)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) s = cursor.getLong(sizeIdx)
                    }
                    n to s
                } ?: ("file" to -1L)
            } catch (e: Exception) {
                "file" to -1L
            }
            // 大文件防护：超过 200MB 直接拒绝（全量读入会 OOM 闪退）
            if (size > com.syna.net.MAX_FILE_SIZE_BYTES) {
                try {
                    android.widget.Toast.makeText(
                        context,
                        "文件过大（上限 ${com.syna.net.MAX_FILE_SIZE_BYTES / 1024 / 1024}MB）",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } catch (e: Exception) {
                }
                return@rememberLauncherForActivityResult
            }
            // 后台线程读取，避免阻塞主线程
            Thread {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        onFilePicked(name ?: "file", bytes)
                    }
                } catch (e: Throwable) {
                    println("[Syna:File] 读取失败: ${e.message}")
                    try {
                        android.widget.Toast.makeText(context, "文件读取失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e2: Exception) {
                    }
                }
            }.start()
        }
    }
    TextButton(
        onClick = {
            try {
                launcher.launch("*/*")
            } catch (e: Throwable) {
                // 系统文件选择器不可用（部分 ROM 禁用 DocumentsUI 等）
                println("[Syna:File] 文件选择器启动失败: ${e.message}")
                try {
                    android.widget.Toast.makeText(
                        context,
                        "无法打开文件选择器: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } catch (e2: Exception) {
                }
            }
        },
        modifier = modifier,
    ) {
        Text("📎")
    }
}


@Composable
actual fun ImagePickerButton(
    onImagePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    val context = SynaApp.context
    // 系统相册（Photo Picker）：Android 13+ 系统级、无需任何权限；低版本自动回退
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val (name, size) = try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    var n: String? = null
                    var s = -1L
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) n = cursor.getString(nameIdx)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) s = cursor.getLong(sizeIdx)
                    }
                    n to s
                } ?: ("photo.jpg" to -1L)
            } catch (e: Exception) {
                "photo.jpg" to -1L
            }
            if (size > com.syna.net.MAX_FILE_SIZE_BYTES) {
                try {
                    android.widget.Toast.makeText(context, "图片过大（上限 200MB）", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                }
                return@rememberLauncherForActivityResult
            }
            Thread {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        onImagePicked(name ?: "photo.jpg", bytes)
                    }
                } catch (e: Throwable) {
                    println("[Syna:Image] 读取失败: ${e.message}")
                    try {
                        android.widget.Toast.makeText(context, "图片读取失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e2: Exception) {
                    }
                }
            }.start()
        }
    }
    TextButton(
        onClick = {
            try {
                launcher.launch("image/*")
            } catch (e: Throwable) {
                println("[Syna:Image] 相册启动失败: ${e.message}")
                try {
                    android.widget.Toast.makeText(context, "无法打开相册: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e2: Exception) {
                }
            }
        },
        modifier = modifier,
    ) {
        Text("🖼")
    }
}
