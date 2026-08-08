package com.syna.storage

import com.syna.SynaApp
import java.io.File

actual fun chatPersistencePath(): String =
    File(SynaApp.context.filesDir, "syna_chat.jsonl").absolutePath

private fun receivedDir(): File = File(SynaApp.context.filesDir, "syna_received")

actual fun receivedFilesSize(): Long = receivedDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

actual fun clearReceivedFiles() {
    receivedDir().walkTopDown().sortedByDescending { it.absolutePath.length }.forEach { f ->
        // 防数据恢复：删除前覆写零值
        try {
            if (f.isFile && f.length() in 1..(64L * 1024 * 1024)) {
                java.io.FileOutputStream(f).use { it.write(ByteArray(f.length().toInt())) }
            }
        } catch (e: Exception) {
        }
        f.delete()
    }
}
