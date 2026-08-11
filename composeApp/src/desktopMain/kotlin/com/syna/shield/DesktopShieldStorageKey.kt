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
                // 尺寸异常：隔离旧文件（不静默覆盖——否则旧密文永久不可解），重建新密钥
                Files.move(f, Paths.get(f.toString() + ".corrupt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
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
        // Shield 门禁（fail-closed）：心跳停滞（检测线程被暂停/杀死）→ 拒绝解密
        if (!ShieldGate.isFresh()) return null
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

    /** 主密钥直用加密（元数据：种子/固定/审计/失败计数——锁定态也必须可解） */
    actual fun encryptWithMaster(data: ByteArray): ByteArray? {
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

    /** 主密钥直用解密 */
    actual fun decryptWithMaster(payload: ByteArray): ByteArray? {
        if (payload.size <= NONCE_LEN) return null
        val key = keyBytes() ?: return null
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

    /** 销毁存储密钥：覆写删除密钥文件（自毁后旧密文不可解） */
    actual fun wipe() {
        com.syna.util.SecureWipe.wipeFile(keyFile().toString())
    }
}
