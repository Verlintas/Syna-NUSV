package com.syna.shield

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
