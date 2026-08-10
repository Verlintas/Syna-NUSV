/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.shield

import java.io.File

/** 桌面：TOTP 种子经 ShieldStorageKey（0600 权限密钥文件）加密落盘 */
actual object TotpSeedStore {

    private fun seedFile(path: String?): File =
        if (path != null) File(path)
        else File(System.getProperty("user.home") ?: ".", ".syna/syna_totp_seed")

    actual fun load(path: String?): ByteArray? {
        return try {
            val f = seedFile(path)
            if (!f.exists()) return null
            ShieldStorageKey.decrypt(f.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    actual fun save(seed: ByteArray, path: String?) {
        try {
            val enc = ShieldStorageKey.encrypt(seed) ?: return
            val f = seedFile(path)
            f.parentFile?.mkdirs()
            f.writeBytes(enc)
        } catch (e: Exception) {
        }
    }

    actual fun clear(path: String?) {
        try {
            seedFile(path).delete()
        } catch (e: Exception) {
        }
    }
}
