package com.syna.crypto

class SessionKey internal constructor(val material: ByteArray)

expect object SynaCrypto {
    fun generateKeyPair(): IdentityKey
    fun publicKeyB64(identity: IdentityKey): String
    fun parsePublicKey(publicKeyB64: String): PublicKeyBytes
    fun deriveSessionKey(privateBytes: ByteArray, peerPublicKeyB64: String, peerId: String): SessionKey
    fun encrypt(key: SessionKey, plaintext: String): String
    fun decrypt(key: SessionKey, payload: String): String
}

class PublicKeyBytes(val bytes: ByteArray)
