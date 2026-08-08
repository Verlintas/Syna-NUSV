package com.syna.shield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirtazapine Shield — 实时安全监测与应用锁。
 *
 * 能力范围（如实声明）：
 * - 应用层可检测的威胁：Root、模拟器、USB 调试/ADB、VPN 变更、后台快速切换、
 *   无障碍服务滥用、已知监控类应用、屏幕截取风险、闲置。
 * - 应用层不可检测的威胁（设备所有者权限）：系统级预装监控、企业 MDM、
 *   root 级进程注入。本模块不承诺也不假装能"隔绝"这类监测。
 * - 反制：检测到威胁 → 全屏锁定页（仅展示威胁类型与解锁入口），解锁需生物识别。
 */
enum class ShieldThreat(
    val id: String,
    val title: String,
    val detail: String,
) {
    ROOT_DETECTED(
        "root",
        "检测到 Root 环境",
        "设备已取得 Root 权限，其他程序可能以更高权限访问数据",
    ),
    EMULATOR_DETECTED(
        "emulator",
        "检测到模拟器环境",
        "运行环境疑似模拟器，可能被用于流量监控或数据截取",
    ),
    DEBUG_MODE(
        "debug",
        "检测到调试模式",
        "USB 调试或调试器已连接，应用数据可能被外部读取",
    ),
    VPN_CHANGE(
        "vpn",
        "检测到网络代理变更",
        "VPN/代理状态发生变化，网络流量可能被第三方中转",
    ),
    BACKGROUND_SWITCH(
        "bgswitch",
        "检测到异常后台切换",
        "应用在极短时间内被切到后台再恢复，疑似被监控或屏幕共享",
    ),
    MONITORING_APP(
        "monitor",
        "检测到监控类应用",
        "设备上发现已知的录屏/远程控制/家长监控类应用",
    ),
    ACCESSIBILITY_ABUSE(
        "a11y",
        "检测到无障碍服务滥用",
        "有无障碍服务可能被用于读取屏幕内容",
    ),
    INACTIVE(
        "inactive",
        "设备闲置",
        "设备长时间无操作，应用已自动锁定",
    ),
}

enum class ShieldState {
    /** 监测中，未锁定 */
    ARMED,

    /** 检测到威胁，已锁定（全屏锁定页） */
    LOCKED,

    /** 用户通过验证解锁 */
    UNLOCKED,
}

/** 威胁检测引擎（平台实现：Android 多检测源 / 桌面闲置锁定） */
interface ShieldEngine {
    fun start()
    fun stop()
    fun onForeground()
    fun onBackground()

    /** 生物识别解锁（Android 指纹/人脸；桌面返回 false） */
    fun requestBiometricUnlock(onResult: (Boolean) -> Unit)

    /** 设置防截屏（仅 Android 生效；桌面 no-op） */
    fun setSecureScreen(secure: Boolean)
}

expect fun createShieldEngine(onThreat: (ShieldThreat) -> Unit): ShieldEngine

/**
 * Shield 控制器：收集威胁、管理状态机。
 * 策略：任一威胁上报即锁定；全部威胁消除后由用户主动解锁（生物识别）。
 */
class ShieldController(
    enabled: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val engine: ShieldEngine = createShieldEngine { threat -> reportThreat(threat) }
    private val stateM = MutableStateFlow(if (enabled) ShieldState.ARMED else ShieldState.UNLOCKED)
    val state: StateFlow<ShieldState> = stateM.asStateFlow()

    private val threatsM = MutableStateFlow<List<ShieldThreat>>(emptyList())
    val threats: StateFlow<List<ShieldThreat>> = threatsM.asStateFlow()

    private val enabledM = MutableStateFlow(enabled)
    val enabled: StateFlow<Boolean> = enabledM.asStateFlow()

    fun start() {
        if (!enabledM.value) return
        engine.start()
    }

    fun stop() = engine.stop()

    fun setEnabled(on: Boolean) {
        enabledM.value = on
        if (on) {
            stateM.value = ShieldState.ARMED
            threatsM.value = emptyList()
            engine.start()
        } else {
            engine.stop()
            stateM.value = ShieldState.UNLOCKED
        }
    }

    fun onForeground() = engine.onForeground()

    fun onBackground() = engine.onBackground()

    fun setSecureScreen(secure: Boolean) = engine.setSecureScreen(secure)

    internal fun reportThreat(threat: ShieldThreat) {
        if (!enabledM.value) return
        val current = threatsM.value
        if (threat !in current) {
            threatsM.value = current + threat
        }
        if (threat != ShieldThreat.INACTIVE) {
            stateM.value = ShieldState.LOCKED
        } else if (current.isEmpty() || (current.size == 1 && current.first() == ShieldThreat.INACTIVE)) {
            stateM.value = ShieldState.LOCKED
        }
    }

    fun clearThreat(threat: ShieldThreat) {
        val current = threatsM.value.filterNot { it == threat }
        threatsM.value = current
        // 威胁全部消除后回到监测中（仍需用户解锁才能使用）
        if (current.isEmpty()) {
            stateM.value = ShieldState.ARMED
        }
    }

    /** 用户请求解锁（触发平台生物识别） */
    fun requestUnlock() {
        if (!enabledM.value) {
            stateM.value = ShieldState.UNLOCKED
            return
        }
        engine.requestBiometricUnlock { granted ->
            if (granted) {
                stateM.value = ShieldState.UNLOCKED
            }
        }
    }

    fun lock() {
        if (enabledM.value) {
            stateM.value = ShieldState.LOCKED
        }
    }

    companion object {
        /** 检测是否应锁定：当前威胁列表非空（含闲置） */
        fun hasActiveThreat(threats: List<ShieldThreat>): Boolean = threats.isNotEmpty()
    }
}
