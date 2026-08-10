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

import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TotpTest {

    // RFC 6238 附录 B 官方测试向量（SHA-1，8 位）
    private val seed = "12345678901234567890".toByteArray()

    @Test
    fun rfc6238TestVectors() {
        assertEquals("94287082", TotpCode.generate(seed, 59L * 1000, 8))
        assertEquals("07081804", TotpCode.generate(seed, 1_111_111_109L * 1000, 8))
        assertEquals("14050471", TotpCode.generate(seed, 1_111_111_111L * 1000, 8))
        assertEquals("89005924", TotpCode.generate(seed, 1_234_567_890L * 1000, 8))
        assertEquals("69279037", TotpCode.generate(seed, 2_000_000_000L * 1000, 8))
        assertEquals("65353130", TotpCode.generate(seed, 20_000_000_000L * 1000, 8))
    }

    @Test
    fun sixDigitDefault() {
        val code = TotpCode.generate(seed, 59L * 1000)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun verifyWithinDriftWindow() {
        val t = 1_600_000_000_000L
        // 当前窗口
        assertTrue(TotpCode.verify(seed, TotpCode.generate(seed, t), t))
        // 前后 ±1 窗口（30s）
        assertTrue(TotpCode.verify(seed, TotpCode.generate(seed, t - 30_000), t))
        assertTrue(TotpCode.verify(seed, TotpCode.generate(seed, t + 30_000), t))
        // 超出容差（±90s）拒绝
        assertFalse(TotpCode.verify(seed, TotpCode.generate(seed, t - 90_000), t))
        // 格式错误拒绝
        assertFalse(TotpCode.verify(seed, "abc123", t))
        assertFalse(TotpCode.verify(seed, "12345", t))
        assertFalse(TotpCode.verify(seed, "", t))
    }

    @Test
    fun base32RoundTrip() {
        val seed = TotpCode.newSeed()
        val encoded = TotpCode.base32Encode(seed)
        assertEquals(encoded, encoded.uppercase())
        assertNotNull(TotpCode.base32Decode(encoded))
        val decoded = TotpCode.base32Decode(encoded) ?: error("解码失败")
        assertEquals(seed.toList(), decoded.toList())
        // 小写与空格兼容
        val decodedLower = TotpCode.base32Decode(" " + encoded.lowercase()) ?: error("解码失败")
        assertEquals(seed.toList(), decodedLower.toList())
        // 非法字符
        assertEquals(null, TotpCode.base32Decode("12345!"))
    }

    @Test
    fun otpauthUriFormat() {
        val uri = TotpCode.otpauthUri(seed, "test device")
        assertTrue(uri.startsWith("otpauth://totp/Syna:"))
        assertTrue(uri.contains("secret="))
        assertTrue(uri.contains("issuer=Syna"))
        assertTrue(uri.contains("digits=6"))
        assertTrue(uri.contains("period=30"))
        val secret = uri.substringAfter("secret=").substringBefore("&")
        assertNotNull(TotpCode.base32Decode(secret))
    }

    @Test
    fun totp2faFlow() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("syna-totp")
        val path = dir.resolve("events.jsonl").toString()
        val seedPath = dir.resolve("seed.bin").toString()
        val controller = ShieldController(enabled = true, eventsPathOverride = path, totpSeedPathOverride = seedPath)
        controller.start()
        // 未开启 2FA：生物识别直接解锁
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        controller.requestUnlock()
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        // 开启 2FA：生成种子
        val uri = controller.enableTotp()
        assertNotNull(uri)
        assertTrue(controller.totpEnabled.value)
        // 锁定 → 生物识别通过 → 等待 TOTP
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        controller.requestUnlock()
        assertEquals(ShieldState.AWAITING_TOTP, controller.state.value)
        // 错误码：回到锁定并计入失败
        controller.verifyTotp("000000")
        assertEquals(ShieldState.LOCKED, controller.state.value)
        assertEquals(1, controller.biometricFails.value)
        // 重新验证 → 正确码解锁（等待错误码冷却期结束：1s 指数退避）
        delay(1_200)
        controller.requestUnlock()
        assertEquals(ShieldState.AWAITING_TOTP, controller.state.value)
        val seed = TotpSeedStore.load(seedPath) ?: error("种子应存在")
        val code = TotpCode.generate(seed, System.currentTimeMillis())
        controller.verifyTotp(code)
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        assertEquals(0, controller.biometricFails.value)
        // 关闭 2FA
        controller.disableTotp()
        assertFalse(controller.totpEnabled.value)
        assertEquals(null, TotpSeedStore.load(seedPath))
        controller.stop()
        ShieldGate.disarm()
    }

    @Test
    fun dualFactorDisableRequiresTotp() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("syna-totp")
        val path = dir.resolve("events.jsonl").toString()
        val seedPath = dir.resolve("seed.bin").toString()
        val controller = ShieldController(enabled = true, eventsPathOverride = path, totpSeedPathOverride = seedPath)
        controller.start()
        controller.enableTotp()
        var disabled = 0

        // 双因子关闭：生物识别（桌面直接通过）→ 等待 TOTP
        controller.requestDisableWithVerification { disabled++ }
        assertEquals(ShieldState.AWAITING_TOTP, controller.state.value)
        assertTrue(controller.disabling.value)
        assertTrue(controller.enabled.value)

        // 错误码：不关闭，计入暴力防护
        controller.verifyTotp("000000")
        assertEquals(ShieldState.LOCKED, controller.state.value)
        assertTrue(controller.enabled.value)
        assertEquals(1, controller.biometricFails.value)
        assertEquals(0, disabled)

        // 冷却后重新走流程 → 正确码 → 真正关闭
        delay(1_200)
        controller.requestDisableWithVerification { disabled++ }
        assertEquals(ShieldState.AWAITING_TOTP, controller.state.value)
        val seed = TotpSeedStore.load(seedPath) ?: error("种子应存在")
        controller.verifyTotp(TotpCode.generate(seed, System.currentTimeMillis()))
        assertFalse(controller.enabled.value, "第二因子通过后护盾应关闭")
        assertFalse(controller.disabling.value)
        assertEquals(1, disabled)
        controller.stop()
        ShieldGate.disarm()
    }

    @Test
    fun disableWhileLockedStillRequiresTotp() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("syna-totp")
        val path = dir.resolve("events.jsonl").toString()
        val seedPath = dir.resolve("seed.bin").toString()
        val controller = ShieldController(enabled = true, eventsPathOverride = path, totpSeedPathOverride = seedPath)
        controller.start()
        controller.enableTotp()
        // 锁定中尝试关闭：进入 TOTP 等待（攻击者无第二因子 → 无法关闭，失败计入暴力防护）
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        var disabled = 0
        controller.requestDisableWithVerification { disabled++ }
        assertEquals(ShieldState.AWAITING_TOTP, controller.state.value, "锁定中关闭同样需要第二因子")
        assertTrue(controller.disabling.value)
        // 错误码：不关闭 + 计数
        controller.verifyTotp("000000")
        assertTrue(controller.enabled.value)
        assertEquals(1, controller.biometricFails.value)
        assertEquals(0, disabled)
        controller.stop()
        ShieldGate.disarm()
    }

    @Test
    fun unlockTriggersSessionRotation() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("syna-totp")
        val path = dir.resolve("events.jsonl").toString()
        val seedPath = dir.resolve("seed.bin").toString()
        val controller = ShieldController(enabled = true, eventsPathOverride = path, totpSeedPathOverride = seedPath)
        var rotations = 0
        controller.setSessionRotateCallback { rotations++ }
        controller.start()
        // 2FA 开启时：TOTP 正确码解锁 → 轮换回调触发
        controller.enableTotp()
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        controller.requestUnlock()
        assertEquals(ShieldState.AWAITING_TOTP, controller.state.value)
        val seed = TotpSeedStore.load(seedPath) ?: error("种子应存在")
        controller.verifyTotp(TotpCode.generate(seed, System.currentTimeMillis()))
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        assertEquals(1, rotations, "解锁成功应触发会话密钥轮换")
        controller.stop()
        ShieldGate.disarm()
    }
}
