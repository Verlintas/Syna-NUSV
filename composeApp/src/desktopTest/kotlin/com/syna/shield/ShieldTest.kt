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
