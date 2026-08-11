package com.syna.storage

import com.syna.SynaApp
import java.io.File

actual fun chatPersistencePath(): String =
    File(SynaApp.context.filesDir, "syna_chat.jsonl").absolutePath

private fun receivedDir(): File = File(SynaApp.context.filesDir, "syna_received")

actual fun receivedFilesSize(): Long = receivedDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }

actual fun copyTextToClipboard(text: String) {
    try {
        val cm = com.syna.SynaApp.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("syna", text))
    } catch (e: Exception) {
    }
}

actual fun deviceIdentityChanged(): Boolean {
    return try {
        val androidId = android.provider.Settings.Secure.getString(
            com.syna.SynaApp.context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: return false
        val baseFile = java.io.File(com.syna.SynaApp.context.filesDir, "syna_device_base")
        if (!baseFile.exists()) {
            // 首次：固化基准
            baseFile.writeBytes(com.syna.shield.ShieldStorageKey.encryptWithMaster(androidId.toByteArray()) ?: return false)
            return false
        }
        val base = com.syna.shield.ShieldStorageKey.decryptWithMaster(baseFile.readBytes())?.decodeToString()
        if (base != null && base != androidId) {
            // 更新基准（下次不再提示）
            baseFile.writeBytes(com.syna.shield.ShieldStorageKey.encryptWithMaster(androidId.toByteArray()) ?: return false)
            return true
        }
        false
    } catch (e: Exception) {
        false
    }
}

actual fun destructPlatformArtifacts() {
    try {
        val dir = com.syna.SynaApp.context.filesDir
        listOf("syna_totp_seed", "syna_session_blob", "syna_dex_base", "syna_version_base", "syna_key_pins", "crash.log")
            .forEach { name -> com.syna.util.SecureWipe.wipeFile(java.io.File(dir, name).absolutePath) }
        // 录音缓存（cacheDir/voice）覆写清理
        try {
            com.syna.util.SecureWipe.wipeDir(java.io.File(com.syna.SynaApp.context.cacheDir, "voice"))
        } catch (e: Exception) {
        }
    } catch (e: Exception) {
    }
}

actual fun clearReceivedFiles() {
    // 安全覆写删除（随机 2 遍 + fsync，防取证恢复）
    com.syna.util.SecureWipe.wipeDir(receivedDir())
}
