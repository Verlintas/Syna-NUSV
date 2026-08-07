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
package com.syna.crypto

import android.util.Base64
import com.syna.SynaApp

actual fun createIdentityStore(): IdentityStore = AndroidIdentityStore()

class AndroidIdentityStore : IdentityStore {

    private val prefs = SynaApp.context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    override fun loadOrCreate(): IdentityKey {
        val priv = prefs.getString(KEY_PRIVATE, null)
        val pub = prefs.getString(KEY_PUBLIC, null)
        if (priv != null && pub != null) {
            return IdentityKey(
                privateBytes = Base64.decode(priv, Base64.DEFAULT),
                publicBytes = Base64.decode(pub, Base64.DEFAULT),
            )
        }
        val key = SynaCrypto.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(key.privateBytes, Base64.DEFAULT))
            .putString(KEY_PUBLIC, Base64.encodeToString(key.publicBytes, Base64.DEFAULT))
            .apply()
        return key
    }

    private companion object {
        const val PREFS_NAME = "syna_identity"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC = "public_key"
    }
}
