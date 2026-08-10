package com.syna.storage

import com.syna.SynaApp
import java.io.File

actual fun chatPersistencePath(): String =
    File(SynaApp.context.filesDir, "syna_chat.jsonl").absolutePath

private fun receivedDir(): File = File(SynaApp.context.filesDir, "syna_received")

actual fun receivedFilesSize(): Long = receivedDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

actual fun destructPlatformArtifacts() {
    try {
        val dir = com.syna.SynaApp.context.filesDir
        listOf("syna_totp_seed", "syna_session_blob", "syna_dex_base", "syna_version_base", "crash.log")
            .forEach { name -> com.syna.util.SecureWipe.wipeFile(java.io.File(dir, name).absolutePath) }
    } catch (e: Exception) {
    }
}

actual fun clearReceivedFiles() {
    // 安全覆写删除（随机 2 遍 + fsync，防取证恢复）
    com.syna.util.SecureWipe.wipeDir(receivedDir())
}
