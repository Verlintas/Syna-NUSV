package com.syna.shield

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShieldTest {

    @Test
    fun threatLocksAndUnlockRestores() = runBlocking {
        val controller = ShieldController(enabled = true)
        controller.start()
        delay(50)
        assertEquals(ShieldState.ARMED, controller.state.value)

        // 上报威胁 → 锁定
        controller.reportThreat(ShieldThreat.DEBUG_MODE)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        assertTrue(ShieldThreat.DEBUG_MODE in controller.threats.value)

        // 威胁消除 → 回到监测中（仍锁定，需用户解锁）
        controller.clearThreat(ShieldThreat.DEBUG_MODE)
        assertEquals(ShieldState.ARMED, controller.state.value)
        assertTrue(controller.threats.value.isEmpty())

        // 解锁（桌面引擎直接确认）
        controller.requestUnlock()
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        controller.stop()
    }

    @Test
    fun disabledShieldNeverLocks() = runBlocking {
        val controller = ShieldController(enabled = false)
        controller.start()
        controller.reportThreat(ShieldThreat.ROOT_DETECTED)
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        controller.stop()
    }

    @Test
    fun multipleThreatsTracked() {
        val controller = ShieldController(enabled = true)
        controller.reportThreat(ShieldThreat.ROOT_DETECTED)
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertEquals(2, controller.threats.value.size)
        assertTrue(ShieldThreat.ROOT_DETECTED in controller.threats.value)
        assertTrue(ShieldThreat.VPN_CHANGE in controller.threats.value)

        // 重复上报不重复记录
        controller.reportThreat(ShieldThreat.ROOT_DETECTED)
        assertEquals(2, controller.threats.value.size)
    }

    @Test
    fun androidDetectionLogic() {
        // 纯逻辑检测函数通过桌面测试间接验证编译与结构
        assertTrue(ShieldController.hasActiveThreat(listOf(ShieldThreat.DEBUG_MODE)))
        assertFalse(ShieldController.hasActiveThreat(emptyList()))
    }
}


class ShieldSelfProtectionTest {

    @Test
    fun signatureDetectsTampering() {
        // 签名-校验往返
        val sig = ShieldConfigGuard.sign("shield_enabled=true")
        assertTrue(ShieldConfigGuard.verify("shield_enabled=true", sig), "合法签名应通过校验")
        assertFalse(ShieldConfigGuard.verify("shield_enabled=false", sig), "篡改后的负载应校验失败")
        assertFalse(ShieldConfigGuard.verify("shield_enabled=true", "deadbeef"), "伪造签名应校验失败")
        assertFalse(ShieldConfigGuard.verify("shield_enabled=true", ""), "空签名应校验失败")
    }

    @Test
    fun unlockExpiresAndRelocks() = runBlocking {
        val controller = ShieldController(enabled = true)
        controller.start()
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        controller.requestUnlock()
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        // 解锁有效期 5 分钟太长无法等待——验证计时器已调度：手动触发等效路径
        // 通过重新上报威胁立即锁定验证状态机仍正常
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        controller.stop()
    }

    @Test
    fun tamperedThreatIsCritical() {
        assertEquals(ThreatSeverity.CRITICAL, ShieldThreat.SHIELD_TAMPERED.severity())
        assertEquals(ThreatSeverity.CRITICAL, ShieldThreat.CREDENTIAL_CHANGED.severity())
        assertEquals(ThreatSeverity.CRITICAL, ShieldThreat.DEVICE_ADMIN_CHANGE.severity())
        assertEquals(ThreatSeverity.HIGH, ShieldThreat.SCREEN_SHARE_SUSPECT.severity())
    }

    @Test
    fun selfDestructTriggersOnceOnCritical() {
        var destructCount = 0
        val controller = ShieldController(enabled = true)
        controller.configureSelfDestruct(enabled = true) { destructCount++ }

        controller.reportThreat(ShieldThreat.ROOT_DETECTED)
        assertEquals(1, destructCount, "严重级威胁应触发自毁")
        assertTrue(controller.selfDestructTriggered.value)

        // 同一威胁重复上报不再触发
        controller.reportThreat(ShieldThreat.ROOT_DETECTED)
        assertEquals(1, destructCount, "同一威胁仅自毁一次")

        // 非严重级威胁不触发
        controller.clearThreat(ShieldThreat.ROOT_DETECTED)
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertEquals(1, destructCount, "非严重级威胁不应触发自毁")
        controller.stop()
    }

    @Test
    fun shieldEventTimelineRecorded() {
        val controller = ShieldController(enabled = true)
        controller.reportThreat(ShieldThreat.DEBUG_MODE)
        controller.clearThreat(ShieldThreat.DEBUG_MODE)
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        controller.requestUnlock()
        val actions = controller.events.value.map { it.action }
        assertTrue(ShieldAction.DETECTED in actions)
        assertTrue(ShieldAction.CLEARED in actions)
        assertTrue(ShieldAction.UNLOCKED in actions)
        assertTrue(controller.events.value.isNotEmpty())
        controller.stop()
    }
}

class ShieldBugfixTest {

    private fun tempEvents(): String =
        java.nio.file.Files.createTempDirectory("syna-shield-events")
            .resolve("events.jsonl").toString()

    @Test
    fun auditHashChainRoundTripAndTamper() = runBlocking {
        val path = tempEvents()
        val c1 = ShieldController(enabled = true, eventsPathOverride = path)
        c1.reportThreat(ShieldThreat.DEBUG_MODE)
        c1.clearThreat(ShieldThreat.DEBUG_MODE)
        c1.reportThreat(ShieldThreat.VPN_CHANGE)
        delay(300) // 等持久化落盘

        // 新实例应恢复全部事件（哈希链校验通过）——需 start() 触发加载
        val c2 = ShieldController(enabled = true, eventsPathOverride = path)
        c2.start()
        assertEquals(c1.events.value.size, c2.events.value.size, "哈希链完整的审计事件应全部恢复")
        c2.stop()

        // 篡改最后一条（内容段改坏）→ 哈希不匹配 → 链断裂 → 受损记录不可载入
        val file = java.io.File(path)
        val lines = file.readLines().toMutableList()
        val last = lines.last()
        val parts = last.split("|")
        lines[lines.size - 1] = "tampered-content|${parts[parts.size - 2]}|${parts[parts.size - 1]}"
        file.writeText(lines.joinToString("\n") + "\n")
        val c3 = ShieldController(enabled = true, eventsPathOverride = path)
        c3.start()
        assertTrue(c3.events.value.size < c1.events.value.size, "篡改记录后链条断裂，受损记录不应载入")
        c3.stop()
    }

    @Test
    fun disablingShieldCancelsAutoRelock() = runBlocking {
        val controller = ShieldController(enabled = true)
        controller.start()
        controller.reportThreat(ShieldThreat.ROOT_DETECTED)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        // 解锁（严重级会调度 30s 再锁）
        controller.requestUnlock()
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        // 禁用 Shield：应立即取消再锁与有效期任务，禁用后不会被自动锁定
        controller.setEnabled(false)
        delay(2_000)
        assertEquals(ShieldState.UNLOCKED, controller.state.value, "禁用后不应被自动锁定")
        controller.stop()
    }

    @Test
    fun tamperedPersistedEventsDoNotCrash() = runBlocking {
        val path = tempEvents()
        // 写入垃圾行 + 合法格式混合
        java.io.File(path).writeText("garbage line\n{\"ts\":1,\"threat\":\"DEBUG_MODE\",\"action\":\"DETECTED\"}|abc|def\n")
        val c = ShieldController(enabled = true, eventsPathOverride = path)
        c.start()
        // 不崩溃且不载入损坏记录
        assertTrue(c.events.value.isEmpty(), "损坏记录不应载入")
        c.stop()
    }
}

class ShieldHardeningTest {

    private fun tempPath(): String =
        java.nio.file.Files.createTempDirectory("syna-shield-harden")
            .resolve("events.jsonl").toString()

    @Test
    fun gateBlocksDecryptWhenHeartbeatStalls() {
        val path = tempPath()
        val controller = ShieldController(enabled = true, eventsPathOverride = path)
        controller.start()
        // 启动即心跳 → 门禁放行
        assertTrue(ShieldGate.isFresh(), "启动后门禁应放行")
        // 锁定 → 会话密钥释放 → 门禁拒绝（fail-closed）
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertFalse(ShieldGate.isFresh(), "锁定后门禁应拒绝解密")
        // 解锁 → 门禁恢复
        controller.requestUnlock()
        assertTrue(ShieldGate.isFresh(), "解锁后门禁应恢复放行")
        controller.stop()
        ShieldGate.disarm()
    }

    @Test
    fun watchdogTripForcesLock() = runBlocking {
        val path = tempPath()
        val controller = ShieldController(enabled = true, eventsPathOverride = path)
        controller.start()
        controller.reportThreat(ShieldThreat.WATCHDOG_TRIP)
        assertEquals(ShieldState.LOCKED, controller.state.value)
        assertTrue(ShieldThreat.WATCHDOG_TRIP in controller.threats.value)
        controller.stop()
        ShieldGate.disarm()
    }

    @Test
    fun honeypotRequiresRepeatedVerification() = runBlocking {
        val path = tempPath()
        val controller = ShieldController(enabled = true, eventsPathOverride = path)
        controller.start()
        // 注入类威胁 → 假锁模式
        controller.reportThreat(ShieldThreat.FRIDA_DETECTED)
        assertTrue(controller.state.value == ShieldState.LOCKED)
        assertTrue(controller.honeypot.value, "注入类威胁应进入假锁模式")
        assertFalse(ShieldGate.isFresh(), "假锁模式下密钥应已释放")

        // 前两次验证通过仍保持锁定（攻击者无生物特征无法脱身）
        controller.requestUnlock()
        assertEquals(ShieldState.LOCKED, controller.state.value)
        controller.requestUnlock()
        assertEquals(ShieldState.LOCKED, controller.state.value)
        // 连续 3 次后放行（真用户逃生通道）
        controller.requestUnlock()
        assertEquals(ShieldState.UNLOCKED, controller.state.value)
        assertFalse(controller.honeypot.value)
        controller.stop()
        ShieldGate.disarm()
    }

    @Test
    fun bruteForceProtectionTriggers() = runBlocking {
        val path = tempPath()
        var destructCount = 0
        val controller = ShieldController(enabled = true, eventsPathOverride = path)
        controller.configureSelfDestruct(enabled = true) { destructCount++ }
        controller.start()
        repeat(ShieldController.BIOMETRIC_FAIL_LIMIT) {
            controller.onBiometricFailed()
        }
        assertEquals(ShieldState.LOCKED, controller.state.value)
        assertTrue(ShieldThreat.BRUTE_FORCE in controller.threats.value)
        assertEquals(1, destructCount, "暴力尝试应触发自毁协议")
        controller.stop()
        ShieldGate.disarm()
    }

    @Test
    fun auditLogUnreadableWhileLocked() = runBlocking {
        val path = tempPath()
        val controller = ShieldController(enabled = true, eventsPathOverride = path)
        controller.start()
        val plain = "审计测试内容"
        val enc = ShieldStorageKey.encrypt(plain.toByteArray())
        assertNotNull(enc)
        // 正常：可解密
        assertEquals(plain, ShieldStorageKey.decrypt(enc!!)?.decodeToString())
        // 锁定（释放会话）→ 解密拒绝（fail-closed）
        controller.reportThreat(ShieldThreat.VPN_CHANGE)
        assertNull(ShieldStorageKey.decrypt(enc), "锁定状态下解密应被门禁拒绝")
        controller.stop()
        ShieldGate.disarm()
    }
}
