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

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object SynaCrypto {

    private const val INFO = "syna-e2e-v1"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_LEN = 12

    actual fun generateKeyPair(): IdentityKey {
        val generator = KeyPairGenerator.getInstance("X25519")
        val pair = generator.generateKeyPair()
        return IdentityKey(
            privateBytes = pair.private.encoded,
            publicBytes = pair.public.encoded,
        )
    }

    actual fun publicKeyB64(identity: IdentityKey): String =
        Base64.getEncoder().encodeToString(identity.publicBytes)

    actual fun parsePublicKey(publicKeyB64: String): PublicKeyBytes {
        val bytes = Base64.getDecoder().decode(publicKeyB64)
        return PublicKeyBytes(bytes)
    }

    actual fun deriveSessionKey(privateBytes: ByteArray, peerPublicKeyB64: String, peerId: String): SessionKey {
        val privateKey = KeyFactory.getInstance("X25519")
            .generatePrivate(PKCS8EncodedKeySpec(privateBytes)) as PrivateKey
        val peerPublicKey = KeyFactory.getInstance("X25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(peerPublicKeyB64))) as PublicKey

        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()

        val material = hkdfSha256(
            ikm = sharedSecret,
            salt = peerId.encodeToByteArray(),
            info = INFO.encodeToByteArray(),
            length = 32,
        )
        return SessionKey(material)
    }

    actual fun deriveFromPassword(password: String, salt: String, info: String): SessionKey {
        val material = hkdfSha256(
            ikm = password.encodeToByteArray(),
            salt = salt.encodeToByteArray(),
            info = info.encodeToByteArray(),
            length = 32,
        )
        return SessionKey(material)
    }

    actual fun encrypt(key: SessionKey, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.material, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.getEncoder().encodeToString(nonce + ciphertext)
    }

    actual fun decrypt(key: SessionKey, payload: String): String {
        val data = Base64.getDecoder().decode(payload)
        require(data.size > NONCE_LEN) { "payload too short" }
        val nonce = data.copyOfRange(0, NONCE_LEN)
        val ciphertext = data.copyOfRange(NONCE_LEN, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.material, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        var okm = ByteArray(0)
        var counter = 1
        while (okm.size < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            okm += t
            counter++
        }
        return okm.copyOf(length)
    }
}
