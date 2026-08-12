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

class SessionKey internal constructor(val material: ByteArray)

expect object SynaCrypto {
    fun generateKeyPair(): IdentityKey
    fun publicKeyB64(identity: IdentityKey): String
    fun parsePublicKey(publicKeyB64: String): PublicKeyBytes
    /** 校验字符串是否为可解析的 X25519 公钥（防垃圾公钥入库毒化会话） */
    fun isValidPublicKey(publicKeyB64: String): Boolean
    fun deriveSessionKey(privateBytes: ByteArray, peerPublicKeyB64: String, peerId: String): SessionKey
    fun deriveFromPassword(password: String, salt: String, info: String = "syna-server-channel"): SessionKey
    fun encrypt(key: SessionKey, plaintext: String): String
    fun decrypt(key: SessionKey, payload: String): String
}

class PublicKeyBytes(val bytes: ByteArray)
