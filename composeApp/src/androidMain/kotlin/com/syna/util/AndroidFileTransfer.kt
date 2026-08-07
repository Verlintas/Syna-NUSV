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
            try {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                } ?: "file"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) onFilePicked(name, bytes)
            } catch (e: Exception) {
                println("[Syna:File] 读取失败: ${e.message}")
            }
        }
    }
    TextButton(onClick = { launcher.launch("*/*") }, modifier = modifier) {
        Text("📎")
    }
}
