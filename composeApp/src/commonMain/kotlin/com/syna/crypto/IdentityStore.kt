package com.syna.crypto

data class IdentityKey(
    val privateBytes: ByteArray,
    val publicBytes: ByteArray,
)

interface IdentityStore {
    fun loadOrCreate(): IdentityKey
}

expect fun createIdentityStore(): IdentityStore
