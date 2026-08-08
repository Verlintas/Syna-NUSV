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

actual fun clearReceivedFiles() {
    try {
        val dir = receivedDir()
        if (Files.exists(dir)) {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    } catch (e: Exception) {
        println("[Syna:Persist] 清除接收文件失败: ${e.message}")
    }
}
