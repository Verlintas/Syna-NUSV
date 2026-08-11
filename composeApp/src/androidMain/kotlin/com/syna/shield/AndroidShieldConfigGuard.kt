/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
