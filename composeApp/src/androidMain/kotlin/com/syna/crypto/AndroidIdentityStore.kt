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
