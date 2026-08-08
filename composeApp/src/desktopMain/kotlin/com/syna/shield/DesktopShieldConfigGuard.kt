package com.syna.shield

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 桌面：HMAC 密钥存于用户目录（0600 权限）。
 * 应用数据被清除时密钥文件一并删除 → keyExists() = false。
 */
actual object ShieldConfigGuard {

    private const val ALIAS = "syna_shield_hmac"
    private const val KEY_FILE = "shield_hmac.key"

    private fun keyFile(): Path =
        Paths.get(System.getProperty("user.home") ?: ".", ".syna", KEY_FILE)

    private fun readKey(): ByteArray? {
        return try {
            val f = keyFile()
            if (!Files.exists(f)) null else Files.readAllBytes(f)
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureKey(): ByteArray? {
        readKey()?.let { return it }
        return try {
            val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val f = keyFile()
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

    actual fun sign(payload: String): String {
        val key = ensureKey() ?: return ""
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    actual fun verify(payload: String, signature: String): Boolean {
        if (signature.isEmpty()) return false
        return try {
            sign(payload) == signature
        } catch (e: Exception) {
            false
        }
    }

    actual fun keyExists(): Boolean = readKey() != null
}
