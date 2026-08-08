package com.syna.shield

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Android：HMAC 密钥存于 Android Keystore（TEE 保护，不可导出）。
 * 应用数据被清除时 Keystore 条目一并删除 → keyExists() = false，
 * 配合设置文件存在性即可识别"存储被清除"。
 */
actual object ShieldConfigGuard {

    private const val ALIAS = "syna_shield_hmac"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun secretKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            (ks.getKey(ALIAS, null) as? SecretKey)
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureKey(): SecretKey? {
        secretKey()?.let { return it }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generator.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    actual fun sign(payload: String): String {
        val key = ensureKey() ?: return ""
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
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

    actual fun keyExists(): Boolean = secretKey() != null
}
