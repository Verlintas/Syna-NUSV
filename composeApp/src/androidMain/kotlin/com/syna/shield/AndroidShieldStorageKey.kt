package com.syna.shield

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android：AES-256 密钥存于 Keystore（TEE，不可导出），加密聊天记录静态文件 */
actual object ShieldStorageKey {

    private const val ALIAS = "syna_storage_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12

    private fun keystoreKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            var key = ks.getKey(ALIAS, null) as? SecretKey
            if (key == null) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generator.generateKey()
                key = ks.getKey(ALIAS, null) as? SecretKey
            }
            key
        } catch (e: Exception) {
            null
        }
    }

    actual fun encrypt(data: ByteArray): ByteArray? {
        val key = keystoreKey() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ct = cipher.doFinal(data)
            nonce + ct
        } catch (e: Exception) {
            null
        }
    }

    actual fun decrypt(payload: ByteArray): ByteArray? {
        // Shield 门禁（fail-closed）：心跳停滞（检测线程被暂停/杀死）→ 拒绝解密
        if (!ShieldGate.isFresh()) return null
        val key = keystoreKey() ?: return null
        if (payload.size <= NONCE_LEN) return null
        return try {
            val nonce = payload.copyOfRange(0, NONCE_LEN)
            val ct = payload.copyOfRange(NONCE_LEN, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ct)
        } catch (e: Exception) {
            null
        }
    }
}
