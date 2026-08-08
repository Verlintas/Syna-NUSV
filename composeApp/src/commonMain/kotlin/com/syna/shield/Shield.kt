package com.syna.shield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    DEVICE_ADMIN_CHANGE(
        "admin",
        "检测到设备管理状态变更",
        "新的设备管理应用被激活或设备所有者状态变化，可能被企业 MDM 接管",
    ),
    CREDENTIAL_CHANGED(
        "credential",
        "检测到凭据变更",
        "系统生物识别或锁屏凭据被修改/移除，设备可能已易主",
    ),
    SCREEN_SHARE_SUSPECT(
        "screenshare",
        "检测到屏幕共享可疑",
        "屏幕数量/投屏状态发生变化，会话内容可能被投射到其他屏幕",
    ),
    FRIDA_DETECTED(
        "frida",
        "检测到注入框架",
        "检测到 Frida/调试注入框架，应用进程可能被动态挂钩与流量读取",
    ),
    CLOCK_CHANGED(
        "clock",
        "检测到系统时间异常变更",
        "系统时钟被大幅调整，可能影响安全审计与消息时效",
    ),
    WEAK_LOCK(
        "weaklock",
        "设备未启用锁屏",
        "设备未设置锁屏，生物识别保护不可用，会话安全等级降低",
    ),
    SHIELD_TAMPERED(
        "tampered",
        "检测到安全设置被篡改",
        "Shield 安全设置或数据存储疑似被外部修改/清除，已强制恢复保护",
    ),
    INACTIVE(
        "inactive",
        "设备闲置",
        "设备长时间无操作，应用已自动锁定",
    ),
}

/** 威胁严重级别（用于分级展示与响应） */
enum class ThreatSeverity(val label: String) {
    CRITICAL("严重"),
    HIGH("高"),
    MEDIUM("中"),
    LOW("低"),
}

/** 威胁事件（审计时间线） */
@kotlinx.serialization.Serializable
data class ShieldEvent(
    val ts: Long,
    val threat: ShieldThreat,
    val action: ShieldAction,
)

enum class ShieldAction(val label: String) {
    DETECTED("检测到威胁"),
    CLEARED("威胁消除"),
    LOCKED("应用锁定"),
    UNLOCKED("解锁"),
    SELF_DESTRUCT("本地数据已销毁"),
    DISABLED("Shield 已停用"),
}

/** 威胁分级：严重级威胁（root/凭据/设备管理/调试）解锁后若未消除会自动再锁 */
fun ShieldThreat.severity(): ThreatSeverity = when (this) {
    ShieldThreat.ROOT_DETECTED,
    ShieldThreat.DEBUG_MODE,
    ShieldThreat.CREDENTIAL_CHANGED,
    ShieldThreat.DEVICE_ADMIN_CHANGE,
    ShieldThreat.SHIELD_TAMPERED,
    ShieldThreat.FRIDA_DETECTED,
    -> ThreatSeverity.CRITICAL

    ShieldThreat.EMULATOR_DETECTED,
    ShieldThreat.MONITORING_APP,
    ShieldThreat.ACCESSIBILITY_ABUSE,
    ShieldThreat.BACKGROUND_SWITCH,
    ShieldThreat.SCREEN_SHARE_SUSPECT,
    -> ThreatSeverity.HIGH

    ShieldThreat.VPN_CHANGE -> ThreatSeverity.MEDIUM
    ShieldThreat.INACTIVE,
    ShieldThreat.CLOCK_CHANGED,
    ShieldThreat.WEAK_LOCK,
    -> ThreatSeverity.LOW
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

/** Shield 审计日志持久化路径（多端） */
expect fun shieldEventsPath(): String

/** 清除本应用写入的剪贴板内容（锁定/切后台时调用，不影响其他应用内容） */
expect fun clearOwnClipboard()

/** 清除本应用的全部通知（自毁时调用，不留痕迹） */
expect fun clearNotifications()

/** 是否已授予使用情况访问权限（增强前台应用检测） */
expect fun shieldUsageAccessGranted(): Boolean

/** 引导用户授予使用情况访问权限（跳系统设置；桌面端 no-op） */
expect fun requestUsageAccessPermission()

/**
 * Shield 控制器：收集威胁、管理状态机、审计时间线。
 * 策略：任一威胁上报即锁定；解锁后若存在严重级威胁，30 秒内未消除将自动重新锁定。
 */
class ShieldController(
    enabled: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    eventsPathOverride: String? = null,
) {
    private val engine: ShieldEngine = createShieldEngine { threat -> reportThreat(threat) }
    private val stateM = MutableStateFlow(if (enabled) ShieldState.ARMED else ShieldState.UNLOCKED)
    val state: StateFlow<ShieldState> = stateM.asStateFlow()

    // 状态内存防篡改：每次变更同步 HMAC 签名值，关键路径校验一致性，
    // 防止攻击者直接改写内存中的状态绕过锁定。
    private val ShieldConfigGuardHmacKey: ByteArray = ByteArray(32).also {
        java.security.SecureRandom().nextBytes(it)
    }

    private var stateHmac = hmacOf(stateM.value.name)

    private fun setState(newState: ShieldState) {
        stateM.value = newState
        stateHmac = hmacOf(newState.name)
    }

    private fun hmacOf(value: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(ShieldConfigGuardHmacKey, "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 校验内存状态未被篡改；被篡改 → 强制锁定 */
    private fun stateIntact(): Boolean {
        val ok = hmacOf(stateM.value.name) == stateHmac
        if (!ok) {
            // 状态被内存篡改：强制锁定并记录
            setState(ShieldState.LOCKED)
            stateHmac = hmacOf(ShieldState.LOCKED.name)
            recordEvent(ShieldThreat.SHIELD_TAMPERED, ShieldAction.DETECTED)
            recordEvent(ShieldThreat.SHIELD_TAMPERED, ShieldAction.LOCKED)
        }
        return ok
    }

    private val threatsM = MutableStateFlow<List<ShieldThreat>>(emptyList())
    val threats: StateFlow<List<ShieldThreat>> = threatsM.asStateFlow()

    private val enabledM = MutableStateFlow(enabled)
    val enabled: StateFlow<Boolean> = enabledM.asStateFlow()

    private val eventsM = MutableStateFlow<List<ShieldEvent>>(emptyList())
    val events: StateFlow<List<ShieldEvent>> = eventsM.asStateFlow()

    private val eventsFile: String = eventsPathOverride ?: shieldEventsPath()

    private val selfDestructTriggeredM = MutableStateFlow(false)
    val selfDestructTriggered: StateFlow<Boolean> = selfDestructTriggeredM.asStateFlow()

    private var relockJob: Job? = null
    private var unlockExpiryJob: Job? = null
    private var selfDestructEnabled = false
    private var selfDestructCallback: (() -> Unit)? = null
    private var destructedFor: String? = null

    fun start() {
        if (!enabledM.value) return
        current = this
        loadPersistedEvents()
        engine.start()
    }

    fun stop() {
        engine.stop()
        if (current === this) current = null
    }

    fun setEnabled(on: Boolean) {
        enabledM.value = on
        // 停用时取消解锁有效期与严重级再锁任务，防止禁用后仍被自动锁定
        if (!on) {
            unlockExpiryJob?.cancel()
            relockJob?.cancel()
        }
        if (on) {
            setState(ShieldState.ARMED)
            threatsM.value = emptyList()
            current = this
            engine.start()
        } else {
            engine.stop()
            setState(ShieldState.UNLOCKED)
            if (current === this) current = null
        }
    }

    fun onForeground() = engine.onForeground()

    fun onBackground() {
        engine.onBackground()
        clearOwnClipboard()
    }

    fun setSecureScreen(secure: Boolean) = engine.setSecureScreen(secure)

    /** 配置自毁协议：疑似破解（严重级威胁）时自动销毁本地聊天记录 */
    fun configureSelfDestruct(enabled: Boolean, callback: () -> Unit) {
        selfDestructEnabled = enabled
        selfDestructCallback = callback
        if (!enabled) {
            selfDestructTriggeredM.value = false
            destructedFor = null
        }
    }

    /**
     * 自毁协议：检测到严重级破解迹象（Root/调试/凭据变更/设备管理接管）时，
     * 自动销毁本地全部聊天记录与接收文件，并记录事件。
     * 同一威胁仅销毁一次，防止重复执行。
     */
    private fun maybeSelfDestruct(threat: ShieldThreat) {
        if (!selfDestructEnabled) return
        if (threat.severity() != ThreatSeverity.CRITICAL) return
        if (destructedFor == threat.id) return
        destructedFor = threat.id
        selfDestructTriggeredM.value = true
        try {
            selfDestructCallback?.invoke()
        } catch (e: Exception) {
        }
        recordEvent(threat, ShieldAction.SELF_DESTRUCT)
    }

    internal fun reportThreat(threat: ShieldThreat) {
        stateIntact()
        if (!enabledM.value) return
        val current = threatsM.value
        if (threat !in current) {
            threatsM.value = current + threat
        }
        recordEvent(threat, ShieldAction.DETECTED)
        maybeSelfDestruct(threat)
        // 低危提示类威胁（时钟跳变/未启用锁屏）仅记录审计，不强制锁定，避免误锁
        val advisoryOnly = threat == ShieldThreat.CLOCK_CHANGED || threat == ShieldThreat.WEAK_LOCK
        if (!advisoryOnly) {
            if (threat != ShieldThreat.INACTIVE) {
                setState(ShieldState.LOCKED)
                recordEvent(threat, ShieldAction.LOCKED)
            } else if (current.isEmpty() || (current.size == 1 && current.first() == ShieldThreat.INACTIVE)) {
                setState(ShieldState.LOCKED)
                recordEvent(threat, ShieldAction.LOCKED)
            }
        }
    }

    fun clearThreat(threat: ShieldThreat) {
        val current = threatsM.value.filterNot { it == threat }
        threatsM.value = current
        recordEvent(threat, ShieldAction.CLEARED)
        // 威胁全部消除后回到监测中（仍需用户解锁才能使用）
        if (current.isEmpty()) {
            setState(ShieldState.ARMED)
        }
    }

    /** 用户请求解锁（触发平台生物识别） */
    fun requestUnlock() {
        stateIntact()
        if (!enabledM.value) {
            setState(ShieldState.UNLOCKED)
            return
        }
        engine.requestBiometricUnlock { granted ->
            if (granted) {
                setState(ShieldState.UNLOCKED)
                recordEvent(ShieldThreat.INACTIVE, ShieldAction.UNLOCKED)
                scheduleRelockIfCritical()
                scheduleUnlockExpiry()
            }
        }
    }

    /**
     * 解锁有效期：解锁后 [UNLOCK_TTL_MS]（默认 5 分钟）自动重新锁定，
     * 防止一次解锁后长期免验证。
     */
    private fun scheduleUnlockExpiry() {
        unlockExpiryJob?.cancel()
        if (!enabledM.value) return
        unlockExpiryJob = scope.launch {
            delay(UNLOCK_TTL_MS)
            if (stateM.value == ShieldState.UNLOCKED) {
                setState(ShieldState.LOCKED)
                recordEvent(ShieldThreat.INACTIVE, ShieldAction.LOCKED)
            }
        }
    }

    /**
     * 关闭 Shield 需生物识别验证：验证通过才真正停用（防被误关/被绕过）。
     * 验证失败或用户取消则保持启用。
     */
    fun disableWithVerification(onDisabled: () -> Unit = {}) {
        if (!enabledM.value) return
        engine.requestBiometricUnlock { granted ->
            if (granted) {
                setEnabled(false)
                recordEvent(ShieldThreat.INACTIVE, ShieldAction.DISABLED)
                onDisabled()
            }
        }
    }

    fun lock() {
        stateIntact()
        if (enabledM.value) {
            setState(ShieldState.LOCKED)
            recordEvent(ShieldThreat.INACTIVE, ShieldAction.LOCKED)
            clearOwnClipboard()
        }
    }

    fun onAppBackgrounded() {
        clearOwnClipboard()
    }

    /**
     * 解锁防伪：若仍存在严重级威胁（root/凭据/设备管理/调试），
     * 30 秒后自动重新锁定，防止绕过。
     */
    private fun scheduleRelockIfCritical() {
        relockJob?.cancel()
        if (threatsM.value.any { it.severity() == ThreatSeverity.CRITICAL }) {
            relockJob = scope.launch {
                delay(RELOCK_AFTER_UNLOCK_MS)
                if (threatsM.value.any { it.severity() == ThreatSeverity.CRITICAL }) {
                    setState(ShieldState.LOCKED)
                    recordEvent(ShieldThreat.INACTIVE, ShieldAction.LOCKED)
                }
            }
        }
    }

    private fun recordEvent(threat: ShieldThreat, action: ShieldAction) {
        val event = ShieldEvent(System.currentTimeMillis(), threat, action)
        eventsM.value = (eventsM.value + event).takeLast(MAX_EVENTS)
        persistEvent(event)
    }

    /** 审计事件追加落盘（重启保留）；哈希链防篡改：每条记录基于上条哈希 */
    private fun persistEvent(event: ShieldEvent) {
        synchronized(this) {
            try {
                val file = java.io.File(eventsFile)
                file.parentFile?.mkdirs()
                val prevHash = try {
                    if (file.exists() && file.length() > 0) {
                        file.readLines().lastOrNull()?.let { last ->
                            last.substringAfterLast('|')
                        } ?: GENESIS_HASH
                    } else GENESIS_HASH
                } catch (e: Exception) {
                    GENESIS_HASH
                }
                val plain = com.syna.net.synaJson.encodeToString(ShieldEvent.serializer(), event)
                // 审计内容加密存储（密钥同聊天记录密钥），哈希链基于密文
                val content = ShieldStorageKey.encrypt(plain.toByteArray())
                    ?.let { java.util.Base64.getEncoder().encodeToString(it) }
                    ?: plain
                val hash = sha256("$prevHash|$content")
                file.appendText("$content|$prevHash|$hash\n")
            } catch (e: Exception) {
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadPersistedEvents() {
        try {
            val file = java.io.File(eventsFile)
            if (!file.exists()) return
            val persisted = mutableListOf<ShieldEvent>()
            var expectedPrev = GENESIS_HASH
            file.readLines().forEach { line ->
                try {
                    // 行格式: content|prevHash|hash
                    val withoutHash = line.substringBeforeLast('|')
                    val hash = line.substringAfterLast('|')
                    val prevHash = withoutHash.substringAfterLast('|')
                    val content = withoutHash.substringBeforeLast('|')
                    // 校验链完整性：任一记录被篡改 → 链条断裂 → 停止载入（防伪造审计）
                    if (prevHash != expectedPrev) return@forEach
                    val recomputed = sha256("$prevHash|$content")
                    if (recomputed != hash) return@forEach
                    val plainText = try {
                        val bytes = java.util.Base64.getDecoder().decode(content)
                        ShieldStorageKey.decrypt(bytes)?.decodeToString()
                    } catch (e: Exception) {
                        null
                    } ?: content
                    val event = com.syna.net.synaJson.decodeFromString(ShieldEvent.serializer(), plainText)
                    persisted.add(event)
                    expectedPrev = hash
                } catch (e: Exception) {
                }
            }
            if (persisted.isNotEmpty()) {
                eventsM.value = (persisted + eventsM.value).takeLast(MAX_EVENTS)
            }
        } catch (e: Exception) {
        }
    }

    companion object {
        const val RELOCK_AFTER_UNLOCK_MS = 30_000L
        const val UNLOCK_TTL_MS = 5 * 60_000L // 解锁有效期：5 分钟自动再锁
        const val MAX_EVENTS = 100
        const val GENESIS_HASH = "genesis" // 哈希链起点

        /** 进程级当前实例（供通知隐藏等模块读取锁定状态） */
        @Volatile
        var current: ShieldController? = null

        val isLocked: Boolean
            get() = current?.state?.value == ShieldState.LOCKED

        /** 检测是否应锁定：当前威胁列表非空（含闲置） */
        fun hasActiveThreat(threats: List<ShieldThreat>): Boolean = threats.isNotEmpty()
    }
}
