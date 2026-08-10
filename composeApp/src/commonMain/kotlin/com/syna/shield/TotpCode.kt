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
package com.syna.shield

/**
 * TOTP（RFC 6238）—— 时间同步一次性密码，用于 ◇Mirtazapine Shield 双重验证解锁。
 *
 * 安全模型：算法与实现完全公开（RFC 标准），安全性仅依赖种子（seed）。
 * 种子由 [TotpSeedStore] 加密存储于本机 Keystore 域，并在设置页以
 * `otpauth://` URI 展示，由用户导入任意 TOTP 应用（Google Authenticator 等）
 * 或另一台设备——解锁时需要"生物识别 + 第二因子"双验证。
 */
object TotpCode {

    private const val STEP_SECONDS = 30L

    /**
     * 生成指定时间点的验证码。
     * @param seed 种子（RFC 4648 base32 解码后；兼容 ASCII 直接使用）
     * @param timeMs 时间戳（毫秒）
     * @param digits 码长（6 或 8）
     */
    fun generate(seed: ByteArray, timeMs: Long, digits: Int = 6): String {
        val counter = timeMs / 1000L / STEP_SECONDS
        // RFC 6238：消息 = 8 字节大端计数器（无前导零填充）
        val buffer = java.nio.ByteBuffer.allocate(8).putLong(counter).array()
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(seed, "HmacSHA1"))
        val hash = mac.doFinal(buffer)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val code = binary % Math.pow(10.0, digits.toDouble()).toInt()
        return code.toString().padStart(digits, '0')
    }

    /** 当前 30 秒窗口 + 前后各 1 窗口内的任一码视为有效（时钟漂移容差） */
    fun verify(seed: ByteArray, code: String, timeMs: Long = System.currentTimeMillis(), digits: Int = 6): Boolean {
        if (code.length != digits || !code.all { it.isDigit() }) return false
        for (shift in -1..1) {
            val t = timeMs + shift * STEP_SECONDS * 1000L
            if (generate(seed, t, digits) == code) return true
        }
        return false
    }

    /** 生成新种子（20 字节随机，兼容 Google Authenticator 的 base32 展示） */
    fun newSeed(): ByteArray = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }

    /** RFC 4648 base32 编码（无填充，TOTP 应用通用格式） */
    fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(alphabet[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) sb.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1f])
        return sb.toString()
    }

    /** RFC 4648 base32 解码（忽略空格/小写/无填充） */
    fun base32Decode(input: String): ByteArray? {
        val s = input.replace(" ", "").replace("-", "").uppercase().trim()
        if (s.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in s) {
            val v = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer shr (bitsLeft - 8)) and 0xff)
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    /** otpauth URI（导入任意 TOTP 应用或扫码器） */
    fun otpauthUri(seed: ByteArray, label: String): String {
        val secret = base32Encode(seed)
        val safeLabel = label.replace(Regex("[^A-Za-z0-9 _\\-]"), "_")
        return "otpauth://totp/Syna:$safeLabel?secret=$secret&issuer=Syna&algorithm=SHA1&digits=6&period=30"
    }
}

/**
 * TOTP 种子存取（expect/actual）：
 * Android 用 ShieldStorageKey（Keystore AES-GCM）加密落盘；桌面同域加密文件。
 */
expect object TotpSeedStore {
    /** 读取种子（未设置返回 null）；path 为空用默认位置（测试可注入隔离路径） */
    fun load(path: String? = null): ByteArray?

    /** 保存种子（加密落盘） */
    fun save(seed: ByteArray, path: String? = null)

    /** 清除种子（关闭双重验证时调用） */
    fun clear(path: String? = null)
}
