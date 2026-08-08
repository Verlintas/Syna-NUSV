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
    private var beatTimer: Timer? = null
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
        // 心跳节拍：驱动 ShieldGate 门禁（桌面检测线程被暂停 → 解密 fail-closed 拒绝）
        beatTimer = Timer(2_000) {
            ShieldGate.beat()
        }.apply { start() }
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
            // JVM agent 注入与远程控制进程检测
            if (hasJavaAgent()) {
                onThreat(ShieldThreat.FRIDA_DETECTED)
            }
            val remote = remoteControlProcesses()
            if (remote.isNotEmpty()) {
                onThreat(ShieldThreat.MONITORING_APP)
            }
        }.apply { start() }
    }

    override fun stop() {
        idleTimer?.stop()
        idleTimer = null
        beatTimer?.stop()
        beatTimer = null
    }

    override fun onForeground() {
        lastActivity = System.currentTimeMillis()
    }

    /** JVM 注入检测：-javaagent/-agentlib 附加（字节码 hook 的常见入口） */
    private fun hasJavaAgent(): Boolean {
        return try {
            val args = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
            args.any { it.startsWith("-javaagent") || it.startsWith("-agentlib") || it.startsWith("-agentpath") }
        } catch (e: Exception) {
            false
        }
    }

    /** 远程控制 / 录屏 / 投屏进程检测（TeamViewer / AnyDesk / OBS / VNC / scrcpy） */
    private fun remoteControlProcesses(): List<String> {
        val keywords = listOf(
            "teamviewer", "anydesk", "obs", "obs64", "vnc", "x11vnc",
            "scrcpy", "rustdesk", "todesk", "sunloginclient", "向日葵",
        )
        return try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("win") -> listOf("tasklist")
                else -> listOf("ps", "-e", "-o", "comm=")
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            keywords.filter { keyword -> output.lowercase().contains(keyword) }
        } catch (e: Exception) {
            emptyList()
        }
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
