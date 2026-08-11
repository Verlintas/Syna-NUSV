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
        // 数据级门禁：优先用会话密钥（认证获得）加密——无认证时新数据不可解
        SessionKeyStore.obtainSessionKey()?.let { session ->
            return aesGcmEncrypt(javax.crypto.spec.SecretKeySpec(session, "AES"), data)
        }
        val key = keystoreKey() ?: return null
        return aesGcmEncrypt(key, data)
    }

    private fun aesGcmEncrypt(key: javax.crypto.SecretKey, data: ByteArray): ByteArray? {
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

    private val integrityProbeCounter = java.util.concurrent.atomic.AtomicInteger(0)

    actual fun decrypt(payload: ByteArray): ByteArray? {
        // Shield 门禁（fail-closed）：心跳停滞（检测线程被暂停/杀死）→ 拒绝解密
        if (!ShieldGate.isFresh()) return null
        // native 心跳校验（JVM hook 免疫）：攻击者 hook JVM beat 伪造新鲜度，
        // 但 native 槽独立停滞 → 解密仍拒绝
        if (NativeShield.loaded && NativeShield.gateFresh() == 0) return null
        // 主动对抗：解密路径概率性完整性抽查（约每 8 次解密一次）——
        // 攻击者 hook 检测线程后周期扫描可能失效，但解密是攻击者必经之路，
        // 每次解密都是一个检测窗口（native 毫秒级开销）
        if (integrityProbeCounter.incrementAndGet() % 8 == 0) {
            try {
                if (NativeShield.loaded) {
                    val integrity = NativeShield.integrity()
                    if (integrity and 1 != 0 || integrity and 2 != 0) {
                        // 主动对抗：解密路径发现代码被 hook → 直接崩溃（防慢慢调试）
                        ShieldController.current?.reportThreat(ShieldThreat.SHIELD_TAMPERED)
                        NativeShield.crash()
                    }
                }
            } catch (e: Throwable) {
            }
        }
        if (payload.size <= NONCE_LEN) return null
        // 先试会话密钥（数据级门禁），再试迁移中的上一代密钥（轮换窗口），
        // 最后回退主密钥（历史数据/未启用场景）
        SessionKeyStore.obtainSessionKey()?.let { session ->
            aesGcmDecrypt(javax.crypto.spec.SecretKeySpec(session, "AES"), payload)?.let { return it }
        }
        SessionKeyStore.previousSessionKey()?.let { prev ->
            aesGcmDecrypt(javax.crypto.spec.SecretKeySpec(prev, "AES"), payload)?.let { return it }
        }
        val key = keystoreKey() ?: return null
        return aesGcmDecrypt(key, payload)
    }

    private fun aesGcmDecrypt(key: javax.crypto.SecretKey, payload: ByteArray): ByteArray? {
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

    /** 主密钥直用加密（元数据：种子/固定/审计/失败计数——锁定态也必须可解） */
    actual fun encryptWithMaster(data: ByteArray): ByteArray? {
        val key = keystoreKey() ?: return null
        return aesGcmEncrypt(key, data)
    }

    /** 主密钥直用解密 */
    actual fun decryptWithMaster(payload: ByteArray): ByteArray? {
        if (payload.size <= NONCE_LEN) return null
        val key = keystoreKey() ?: return null
        return aesGcmDecrypt(key, payload)
    }

    /**
     * 销毁存储密钥：删除 Keystore 条目（TEE 内销毁，不可恢复）——
     * 自毁后即使文件被取证恢复也永久不可解（数据恢复的最强防线）。
     */
    actual fun wipe() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            ks.deleteEntry(ALIAS)
            ks.deleteEntry("syna_session_auth")
        } catch (e: Exception) {
        }
        // 会话密钥 blob 一并覆写删除
        try {
            val blob = java.io.File(com.syna.SynaApp.context.filesDir, "syna_session_blob")
            com.syna.util.SecureWipe.wipeFile(blob.absolutePath)
        } catch (e: Exception) {
        }
    }
}
