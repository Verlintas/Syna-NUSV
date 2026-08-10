/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.shield

import kotlinx.serialization.Serializable

/**
 * 密钥固定（TOFU：首次使用即信任）+ 指纹：
 * - 首次收到的对端公钥被固定为基准；
 * - 后续公钥与基准一致 → 正常；不一致 → 不自动接受并上报 KEY_CHANGED
 *   （P2P 通道的中间人防线：攻击者伪造 HELLO/KEY 无法毒化密钥表）。
 * - 固定数据经 ShieldStorageKey 加密落盘（防篡改/防拷贝）。
 */
object KeyPinning {

    @Serializable
    private data class PinStore(val pins: Map<String, String> = emptyMap())

    @Volatile
    private var pins: Map<String, String> = load()

    /** 测试路径注入（桌面测试隔离；生产保持 null） */
    @Volatile
    internal var pathOverride: String? = null

    private fun pinFile(): java.io.File = java.io.File(pathOverride ?: keyPinsPath())

    private fun load(): Map<String, String> {
        return try {
            val f = pinFile()
            if (!f.exists()) return emptyMap()
            val bytes = ShieldStorageKey.decryptWithMaster(f.readBytes()) ?: return emptyMap()
            com.syna.net.synaJson.decodeFromString(PinStore.serializer(), bytes.decodeToString()).pins
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun save() {
        try {
            val bytes = com.syna.net.synaJson.encodeToString(PinStore.serializer(), PinStore(pins)).toByteArray()
            val enc = ShieldStorageKey.encryptWithMaster(bytes) ?: return
            pinFile().parentFile?.mkdirs()
            pinFile().writeBytes(enc)
        } catch (e: Exception) {
        }
    }

    /** 校验并固定：返回结果（首次/一致/变更） */
    fun checkAndPin(peerId: String, publicKeyB64: String): PinResult {
        synchronized(this) {
            val existing = pins[peerId]
            return when {
                existing == null -> {
                    pins = pins + (peerId to publicKeyB64)
                    save()
                    PinResult.PINNED_FIRST
                }
                existing == publicKeyB64 -> PinResult.PINNED_MATCH
                else -> PinResult.PINNED_CHANGED
            }
        }
    }

    /** 获取已固定公钥（未固定返回 null） */
    fun pinnedKey(peerId: String): String? = pins[peerId]

    /** 指纹：SHA-256 前 4 字节 → 8 位十六进制（分组显示用） */
    fun fingerprint(publicKeyB64: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(publicKeyB64.toByteArray())
            digest.take(4).joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            "????????"
        }
    }

    /** 完整指纹：SHA-256 前 8 字节，4+4 分组 */
    fun fingerprintFull(publicKeyB64: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(publicKeyB64.toByteArray())
            val hex = digest.take(8).joinToString("") { "%02X".format(it) }
            hex.chunked(4).joinToString("-")
        } catch (e: Exception) {
            "????????-????????"
        }
    }

    /** 用户主动重新固定（重装/确认新密钥后） */
    fun rePin(peerId: String, publicKeyB64: String) {
        synchronized(this) {
            pins = pins + (peerId to publicKeyB64)
            save()
        }
    }

    enum class PinResult {
        /** 首次见到该对端：已固定 */
        PINNED_FIRST,

        /** 与已固定公钥一致 */
        PINNED_MATCH,

        /** 公钥变更：不自动接受（潜在中间人） */
        PINNED_CHANGED,
    }
}

/** 密钥固定文件路径（平台特定） */
expect fun keyPinsPath(): String
