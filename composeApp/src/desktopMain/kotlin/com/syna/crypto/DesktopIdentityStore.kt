package com.syna.crypto

import java.nio.file.Paths

actual fun createIdentityStore(): IdentityStore {
    val home = System.getProperty("user.home") ?: "."
    return JvmIdentityStore(Paths.get(home, ".syna"))
}
