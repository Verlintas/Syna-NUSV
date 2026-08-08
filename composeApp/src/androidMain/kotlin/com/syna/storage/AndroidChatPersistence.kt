package com.syna.storage

import com.syna.SynaApp
import java.io.File

actual fun chatPersistencePath(): String =
    File(SynaApp.context.filesDir, "syna_chat.jsonl").absolutePath

private fun receivedDir(): File = File(SynaApp.context.filesDir, "syna_received")

actual fun receivedFilesSize(): Long = receivedDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

actual fun clearReceivedFiles() {
    receivedDir().walkTopDown().sortedByDescending { it.absolutePath.length }.forEach { it.delete() }
}
