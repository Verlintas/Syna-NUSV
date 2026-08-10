/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.shield

import java.io.File

/** Android：TOTP 种子经 ShieldStorageKey（Keystore AES-GCM）加密落盘 */
actual object TotpSeedStore {

    private fun seedFile(path: String?): File =
        if (path != null) File(path)
        else File(com.syna.SynaApp.context.filesDir, "syna_totp_seed")

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
            seedFile(path).writeBytes(enc)
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
