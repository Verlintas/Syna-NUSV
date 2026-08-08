package com.syna.shield

import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

actual fun createShieldEngine(onThreat: (ShieldThreat) -> Unit): ShieldEngine =
    DesktopShieldEngine(onThreat)

/**
 * 桌面 Shield：闲置自动锁定。
 * 桌面无系统级生物识别接口，解锁由用户主动确认；
 * 检测源仅包括闲置（应用层无法检测系统级监控，如实标注）。
 */
class DesktopShieldEngine(
    private val onThreat: (ShieldThreat) -> Unit,
) : ShieldEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastActivity = 0L
    private var idleTimer: Timer? = null
    private var lastMouseEvent: MouseEvent? = null
    private var lastScreenCount: Int? = null

    override fun start() {
        lastActivity = System.currentTimeMillis()
        // 全局鼠标移动监听（桌面无 touch 事件源，以鼠标活动为准）
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                { event ->
                    if (event is MouseEvent && event.id == MouseEvent.MOUSE_MOVED) {
                        lastActivity = System.currentTimeMillis()
                    }
                },
                java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK,
            )
        } catch (e: Exception) {
        }
        lastScreenCount = screenCount()
        idleTimer = Timer(IDLE_CHECK_MS.toInt()) {
            val now = System.currentTimeMillis()
            if (now - lastActivity > IDLE_LOCK_MS) {
                onThreat(ShieldThreat.INACTIVE)
            }
            // 屏幕数量变化：外接显示器/投屏（投影、屏幕共享的常见前置）
            val current = screenCount()
            if (lastScreenCount != null && current != lastScreenCount) {
                onThreat(ShieldThreat.SCREEN_SHARE_SUSPECT)
            }
            lastScreenCount = current
        }.apply { start() }
    }

    override fun stop() {
        idleTimer?.stop()
        idleTimer = null
    }

    override fun onForeground() {
        lastActivity = System.currentTimeMillis()
    }

    private fun screenCount(): Int = try {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size
    } catch (e: Exception) {
        0
    }

    override fun onBackground() {
    }

    override fun requestBiometricUnlock(onResult: (Boolean) -> Unit) {
        // 桌面端无生物识别：由锁定页的用户确认按钮直接解锁
        onResult(true)
    }

    override fun setSecureScreen(secure: Boolean) {
        // 桌面端无系统级防截屏接口
    }

    companion object {
        const val IDLE_CHECK_MS = 10_000L
        const val IDLE_LOCK_MS = 10 * 60_000L // 10 分钟无操作锁定
    }
}
