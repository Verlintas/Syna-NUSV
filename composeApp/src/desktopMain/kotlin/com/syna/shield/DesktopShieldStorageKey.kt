package com.syna.shield

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 桌面：32 字节 AES 密钥存于 0600 权限文件，加密聊天记录静态文件 */
actual object ShieldStorageKey {

    private const val KEY_FILE = "storage.key"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12

    private fun keyFile(): Path =
        Paths.get(System.getProperty("user.home") ?: ".", ".syna", KEY_FILE)

    private fun keyBytes(): ByteArray? {
        return try {
            val f = keyFile()
            if (Files.exists(f)) {
                val bytes = Files.readAllBytes(f)
                if (bytes.size == 32) return bytes
            }
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            Files.createDirectories(f.parent)
            Files.write(f, key)
            try {
                Files.setPosixFilePermissions(
                    f,
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    ),
                )
            } catch (_: Exception) {
            }
            key
        } catch (e: Exception) {
            null
        }
    }

    actual fun encrypt(data: ByteArray): ByteArray? {
        val key = keyBytes() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ct = cipher.doFinal(data)
            nonce + ct
        } catch (e: Exception) {
            null
        }
    }

    actual fun decrypt(payload: ByteArray): ByteArray? {
        val key = keyBytes() ?: return null
        if (payload.size <= NONCE_LEN) return null
        return try {
            val nonce = payload.copyOfRange(0, NONCE_LEN)
            val ct = payload.copyOfRange(NONCE_LEN, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            null
        }
    }
}
