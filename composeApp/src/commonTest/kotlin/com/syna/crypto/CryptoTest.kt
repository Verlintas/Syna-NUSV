package com.syna.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun keyPairGenerated() {
        val key = SynaCrypto.generateKeyPair()
        assertTrue(key.privateBytes.isNotEmpty())
        assertTrue(key.publicBytes.isNotEmpty())
        assertTrue(SynaCrypto.publicKeyB64(key).isNotEmpty())
    }

    @Test
    fun bothSidesDeriveSameSessionKey() {
        val alice = SynaCrypto.generateKeyPair()
        val bob = SynaCrypto.generateKeyPair()

        val aliceSession = SynaCrypto.deriveSessionKey(alice.privateBytes, SynaCrypto.publicKeyB64(bob), "bob-id")
        val bobSession = SynaCrypto.deriveSessionKey(bob.privateBytes, SynaCrypto.publicKeyB64(alice), "bob-id")

        assertTrue(aliceSession.material.contentEquals(bobSession.material))
    }

    @Test
    fun differentPeerIdYieldsDifferentKey() {
        val alice = SynaCrypto.generateKeyPair()
        val bob = SynaCrypto.generateKeyPair()

        val key1 = SynaCrypto.deriveSessionKey(alice.privateBytes, SynaCrypto.publicKeyB64(bob), "peer-x")
        val key2 = SynaCrypto.deriveSessionKey(alice.privateBytes, SynaCrypto.publicKeyB64(bob), "peer-y")

        assertTrue(!key1.material.contentEquals(key2.material))
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val alice = SynaCrypto.generateKeyPair()
        val bob = SynaCrypto.generateKeyPair()
        val session = SynaCrypto.deriveSessionKey(alice.privateBytes, SynaCrypto.publicKeyB64(bob), "peer-id")

        val plain = "你好，这是端到端加密的测试消息 🛡"
        val cipher = SynaCrypto.encrypt(session, plain)

        assertNotEquals(plain, cipher)
        assertEquals(plain, SynaCrypto.decrypt(session, cipher))
    }

    @Test
    fun eachEncryptionProducesUniqueCiphertext() {
        val alice = SynaCrypto.generateKeyPair()
        val bob = SynaCrypto.generateKeyPair()
        val session = SynaCrypto.deriveSessionKey(alice.privateBytes, SynaCrypto.publicKeyB64(bob), "peer-id")

        val c1 = SynaCrypto.encrypt(session, "same message")
        val c2 = SynaCrypto.encrypt(session, "same message")

        assertNotEquals(c1, c2)
    }

    @Test
    fun wrongKeyCannotDecrypt() {
        val alice = SynaCrypto.generateKeyPair()
        val bob = SynaCrypto.generateKeyPair()
        val eve = SynaCrypto.generateKeyPair()

        val aliceSession = SynaCrypto.deriveSessionKey(alice.privateBytes, SynaCrypto.publicKeyB64(bob), "peer-id")
        val eveSession = SynaCrypto.deriveSessionKey(eve.privateBytes, SynaCrypto.publicKeyB64(bob), "peer-id")

        val cipher = SynaCrypto.encrypt(aliceSession, "机密内容")
        assertFails {
            SynaCrypto.decrypt(eveSession, cipher)
        }
    }

    @Test
    fun tamperedCiphertextFailsAuth() {
        val alice = SynaCrypto.generateKeyPair()
        val bob = SynaCrypto.generateKeyPair()
        val session = SynaCrypto.deriveSessionKey(alice.privateBytes, SynaCrypto.publicKeyB64(bob), "peer-id")

        val cipher = SynaCrypto.encrypt(session, "原始消息")
        val bytes = java.util.Base64.getDecoder().decode(cipher)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)

        assertFails {
            SynaCrypto.decrypt(session, tampered)
        }
    }
}
