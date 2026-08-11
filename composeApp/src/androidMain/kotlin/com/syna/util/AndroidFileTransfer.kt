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
import android.provider.OpenableColumns
import com.syna.SynaApp
import java.io.File

actual fun saveReceivedFile(fileName: String, bytes: ByteArray): String {
    val dir = File(SynaApp.context.filesDir, "syna_received")
    dir.mkdirs()
    val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    // 同名文件加序号（防重复接收覆盖前一份）
    var target = File(dir, safeName)
    if (target.exists()) {
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var n = 1
        while (target.exists()) {
            target = File(dir, "$base($n)$ext")
            n++
        }
    }
    target.writeBytes(bytes)
    return target.absolutePath
}

actual fun readFileBytes(path: String): ByteArray = File(path).readBytes()

@Composable
actual fun FilePickerButton(
    onFilePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = {
            com.syna.MainActivity.launchFilePicker { uri ->
                if (uri != null) {
                    handlePicked(uri, onFilePicked, "file", "*/*")
                }
            }
        },
        modifier = modifier,
    ) {
        Text("文件")
    }
}


@Composable
actual fun ImagePickerButton(
    onImagePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = {
            com.syna.MainActivity.launchImagePicker { uri ->
                if (uri != null) {
                    handlePicked(uri, onImagePicked, "photo.jpg", "image/*")
                }
            }
        },
        modifier = modifier,
    ) {
        Text("图片")
    }
}

/**
 * 统一处理选择结果：查询名称/大小 → 大文件拦截 → 后台线程读取 → 回调。
 * 使用固定 requestCode 的 startActivityForResult 路径（不走 ActivityResultRegistry，
 * 避免其 requestCode 恒 ≥ 65536 触发平台 "Can only use lower 16 bits" 崩溃）。
 */
private fun handlePicked(
    uri: android.net.Uri,
    onPicked: (name: String, bytes: ByteArray) -> Unit,
    defaultName: String,
    kindLabel: String,
) {
    val context = SynaApp.context
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
        } ?: (defaultName to -1L)
    } catch (e: Exception) {
        defaultName to -1L
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
        return
    }
    // 后台线程读取，避免阻塞主线程
    Thread {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                // SIZE 未知（云盘/流式 provider）时边读边限，防绕过 200MB 上限 OOM
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > com.syna.net.MAX_FILE_SIZE_BYTES) {
                        throw IllegalArgumentException("文件过大（上限 ${com.syna.net.MAX_FILE_SIZE_BYTES / 1024 / 1024}MB）")
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            if (bytes != null) {
                onPicked(name ?: defaultName, bytes)
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

actual fun appCacheDir(): String = com.syna.SynaApp.context.cacheDir.absolutePath
