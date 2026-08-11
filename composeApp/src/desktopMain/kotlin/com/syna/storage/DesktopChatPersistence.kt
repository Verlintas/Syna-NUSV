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
package com.syna.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

actual fun chatPersistencePath(): String =
    Paths.get(System.getProperty("user.home") ?: ".", ".syna", "chat.jsonl").toString()

private fun receivedDir(): Path =
    Paths.get(System.getProperty("user.home") ?: ".", "Downloads", "Syna")

actual fun receivedFilesSize(): Long = try {
    val dir = receivedDir()
    if (!Files.exists(dir)) 0L
    else Files.walk(dir).filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
} catch (e: Exception) {
    0L
}

actual fun copyTextToClipboard(text: String) {
    try {
        val selection = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    } catch (e: Exception) {
    }
}

actual fun deviceIdentityChanged(): Boolean = false

actual fun destructPlatformArtifacts() {
    try {
        val dir = java.io.File(System.getProperty("user.home") ?: ".", ".syna")
        dir.listFiles()?.forEach { f ->
            if (f.isFile) com.syna.util.SecureWipe.wipeFile(f.absolutePath)
        }
        // 桌面录音临时文件（syna-voice-*）覆写清理
        try {
            val tmp = java.io.File(System.getProperty("java.io.tmpdir"))
            tmp.listFiles()?.filter { it.name.startsWith("syna-voice-") }?.forEach { f ->
                com.syna.util.SecureWipe.wipeFile(f.absolutePath)
            }
        } catch (e: Exception) {
        }
    } catch (e: Exception) {
    }
}

actual fun clearReceivedFiles() {
    // 安全覆写删除（随机 2 遍 + fsync，防取证恢复）
    com.syna.util.SecureWipe.wipeDir(receivedDir().toFile())
}
