/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.shield

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 会话密钥层：认证绑定 blob 方案。
 *
 * 认证链：authKey（Keystore，认证绑定）→ blob（会话密钥密文）→ 数据密钥。
 * 认证成功（生物识别/系统锁屏）后 300 秒窗口内可解 blob，获得会话密钥并缓存；
 * 锁定/停用即 invalidate，内存中的会话密钥被释放。
 */
actual object SessionKeyStore {

    private const val AUTH_ALIAS = "syna_session_auth"
    private const val AUTH_WINDOW_SECONDS = 300
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12
    private const val SESSION_KEY_LEN = 32

    @Volatile
    private var cachedSessionKey: ByteArray? = null

    private fun blobFile(): File =
        File(com.syna.SynaApp.context.filesDir, "syna_session_blob")

    private fun authKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            var key = ks.getKey(AUTH_ALIAS, null) as? SecretKey
            if (key == null) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(AUTH_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        // 认证绑定：仅用户认证（生物识别/锁屏）后 300 秒窗口内可用
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
                        .build(),
                )
                generator.generateKey()
                key = ks.getKey(AUTH_ALIAS, null) as? SecretKey
            }
            key
        } catch (e: Exception) {
            null
        }
    }

    /** 初始化解密 Cipher（认证窗口内成功；未认证抛异常返回 null） */
    private fun authDecryptCipher(): Cipher? {
        val key = authKey() ?: return null
        return try {
            val f = blobFile()
            if (!f.exists() || f.length() <= NONCE_LEN) return null
            val payload = f.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = payload.copyOfRange(0, NONCE_LEN)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher
        } catch (e: Exception) {
            null
        }
    }

    /** 认证成功回调：立即解 blob 缓存会话密钥（认证窗口刚刷新，必然成功） */
    fun captureAuth() {
        try {
            val cipher = authDecryptCipher() ?: return
            val f = blobFile()
            val payload = f.readBytes()
            val plain = cipher.doFinal(payload.copyOfRange(NONCE_LEN, payload.size))
            if (plain.size == SESSION_KEY_LEN) {
                cachedSessionKey = plain
            }
        } catch (e: Exception) {
        }
    }

    /** 初始化加密 Cipher 用于 BiometricPrompt CryptoObject（认证窗口内） */
    fun authEncryptCipher(): Cipher? {
        val key = authKey() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher
        } catch (e: Exception) {
            null
        }
    }

    /** 首次使用：生成会话密钥并加密为 blob（blob 不存在时） */
    fun ensureBlob() {
        try {
            val f = blobFile()
            if (f.exists()) return
            val cipher = authEncryptCipher() ?: return
            val sessionKey = ByteArray(SESSION_KEY_LEN).also { SecureRandom().nextBytes(it) }
            val ct = cipher.doFinal(sessionKey)
            val nonce = cipher.iv
            f.writeBytes(nonce + ct)
        } catch (e: Exception) {
        }
    }

    actual fun obtainSessionKey(): ByteArray? = cachedSessionKey

    actual fun invalidateSession() {
        cachedSessionKey = null
    }
}
