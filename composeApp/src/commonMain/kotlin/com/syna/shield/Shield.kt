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
    SCREEN_RECORDING(
        "recording",
        "检测到屏幕录制/截屏",
        "系统检测到屏幕被录制或截取，会话内容可能被留存",
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
    IME_CHANGED(
        "ime",
        "检测到输入法变更",
        "系统输入法发生变化，若被替换为恶意输入法可能记录按键",
    ),
    USB_CHANGED(
        "usb",
        "检测到 USB 连接变化",
        "USB 设备接入/移除，可能被用于调试或数据提取",
    ),
    SUSPICIOUS_MODULE(
        "module",
        "检测到可疑可执行模块",
        "进程加载了非系统目录的可执行模块，疑似注入",
    ),
    KEY_CHANGED(
        "keychanged",
        "检测到对端密钥变更",
        "对方的加密密钥发生变化，可能是重装应用或中间人攻击，请核对密钥指纹",
    ),
    PROXY_SET(
        "proxy",
        "检测到系统代理配置",
        "系统设置了全局 HTTP 代理，网络流量可能经第三方中转",
    ),
    DEVICE_CHANGED(
        "device",
        "检测到设备身份变化",
        "设备标识与上次运行不一致，可能是重新安装或恢复了备份",
    ),
    SHIELD_TAMPERED(
        "tampered",
        "检测到安全设置被篡改",
        "Shield 安全设置或数据存储疑似被外部修改/清除，已强制恢复保护",
    ),
    NETWORK_MITM(
        "mitm",
        "检测到网络欺骗风险",
        "系统 CA 证书新增或网关地址发生变化，网络流量可能被中间人截获",
    ),
    NETWORK_CHANGED(
        "netchange",
        "检测到网络环境变化",
        "已切换到新的 Wi-Fi 网络，请确认当前网络可信",
    ),
    SELINUX_DISABLED(
        "selinux",
        "SELinux 未处于强制模式",
        "SELinux 未启用强制模式，系统防护等级降低，注入风险升高",
    ),
    DOWNGRADE_ATTEMPT(
        "downgrade",
        "检测到降级安装尝试",
        "应用版本低于上次运行版本，疑似通过降级绕过防护",
    ),
    WATCHDOG_TRIP(
        "watchdog",
        "检测到监测循环停滞",
        "Shield 监测心跳停滞或看门狗触发，疑似检测线程被外部暂停/挂钩，已强制锁定",
    ),
    BRUTE_FORCE(
        "bruteforce",
        "检测到暴力解锁尝试",
        "生物识别连续失败多次，疑似非本人尝试解锁，已释放会话密钥并进入高度警戒",
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
    KEY_RELEASED("会话密钥已释放"),
    HONEYPOT("假锁模式已启用"),
    WATCHDOG("看门狗触发"),
}

/** 威胁分级：严重级威胁（root/凭据/设备管理/调试）解锁后若未消除会自动再锁 */
fun ShieldThreat.severity(): ThreatSeverity = when (this) {
    ShieldThreat.ROOT_DETECTED,
    ShieldThreat.CREDENTIAL_CHANGED,
    ShieldThreat.DEVICE_ADMIN_CHANGE,
    ShieldThreat.SHIELD_TAMPERED,
    ShieldThreat.FRIDA_DETECTED,
    ShieldThreat.WATCHDOG_TRIP,
    ShieldThreat.BRUTE_FORCE,
    ShieldThreat.DOWNGRADE_ATTEMPT,
    -> ThreatSeverity.CRITICAL

    ShieldThreat.EMULATOR_DETECTED,
    ShieldThreat.DEBUG_MODE,
    ShieldThreat.MONITORING_APP,
    ShieldThreat.ACCESSIBILITY_ABUSE,
    ShieldThreat.BACKGROUND_SWITCH,
    ShieldThreat.SCREEN_SHARE_SUSPECT,
    -> ThreatSeverity.HIGH

    ShieldThreat.VPN_CHANGE,
    ShieldThreat.NETWORK_MITM,
    -> ThreatSeverity.MEDIUM

    ShieldThreat.SCREEN_RECORDING,
    ShieldThreat.KEY_CHANGED,
    -> ThreatSeverity.HIGH
    ShieldThreat.INACTIVE,
    ShieldThreat.CLOCK_CHANGED,
    ShieldThreat.WEAK_LOCK,
    ShieldThreat.NETWORK_CHANGED,
    ShieldThreat.SELINUX_DISABLED,
    ShieldThreat.IME_CHANGED,
    ShieldThreat.USB_CHANGED,
    ShieldThreat.SUSPICIOUS_MODULE,
    ShieldThreat.PROXY_SET,
    ShieldThreat.DEVICE_CHANGED,
    -> ThreatSeverity.LOW
}

enum class ShieldState {
    /** 监测中，未锁定 */
    ARMED,

    /** 检测到威胁，已锁定（全屏锁定页） */
    LOCKED,

    /** 用户通过验证解锁 */
    UNLOCKED,

    /** 生物识别已通过，等待 TOTP 第二因子（双重验证） */
    AWAITING_TOTP,
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
 * Shield 心跳门禁（fail-closed 核心，源码公开亦有效）：
 * - 检测引擎每轮更新心跳（时间戳 + HMAC 指纹，随机化节拍）。
 * - 解密路径（聊天记录/审计日志）在执行前检查门禁新鲜度：
 *   心跳停滞（检测线程被暂停/杀死/hook）→ 拒绝解密，等效锁定。
 * - 锁定即释放：LOCKED 状态下门禁失效且内存会话能力被释放；
 *   解锁后由引擎重新驱动心跳恢复。
 * 攻击者即便掌握全部源码，也必须让"解密"与"心跳"同时伪装成功，
 * 任何伪装都是注入行为本身，会落入注入检测/看门狗覆盖面。
 */
object ShieldGate {
    const val STALE_MS = 12_000L

    private val hmacKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    @Volatile
    private var armed = false

    @Volatile
    private var lockedOut = false

    @Volatile
    private var lastBeat = 0L

    @Volatile
    private var lastFingerprint = ""

    /** 启用门禁（Shield 启动时调用，同时给出首个有效心跳） */
    fun arm() {
        armed = true
        lockedOut = false
        beat()
    }

    /** 关闭门禁（Shield 停用时调用，解密完全放行） */
    fun disarm() {
        armed = false
        lockedOut = false
        lastBeat = 0L
        lastFingerprint = ""
    }

    /** 检测引擎节拍：每轮扫描调用一次（时间戳与指纹同步写入，防读侧撕裂） */
    fun beat() {
        if (!armed || lockedOut) return
        synchronized(this) {
            val t = System.currentTimeMillis()
            lastBeat = t
            lastFingerprint = hmacOf(t)
        }
    }

    /** 锁定即释放：时间戳清零 + 指纹置乱，门禁立即失效（会话密钥能力释放） */
    fun releaseSession() {
        lockedOut = true
        lastBeat = 0L
        lastFingerprint = ""
    }

    /** 解锁恢复：门禁回到"等待引擎心跳"状态 */
    fun restoreSession() {
        lockedOut = false
        if (armed) beat()
    }

    /** 门禁是否放行解密（fail-closed：任何异常均拒绝） */
    fun isFresh(): Boolean {
        if (!armed) return true
        if (lockedOut) return false
        if (lastBeat == 0L) return false
        if (System.currentTimeMillis() - lastBeat > STALE_MS) return false
        // 指纹校验：防止攻击者只冻结时间戳
        if (hmacOf(lastBeat) != lastFingerprint) return false
        return true
    }

    /** 上次有效心跳时间（面板展示） */
    fun lastBeatAt(): Long = lastBeat

    private fun hmacOf(value: Long): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"))
        return mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(value).array())
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 看门狗环形哨兵（Watchdog Ring）：
 * 3 个独立守护线程环形互盯（W1 盯 W2 的槽、W2 盯 W3、W3 盯 W1），
 * 随机间隔节拍。任一被盯槽位心跳停滞/指纹失效 → 触发锁定回调。
 * 检测线程被杀 → 其槽位停滞被相邻看门狗发现；
 * 看门狗被逐个暂停 → 其槽位同样停滞被相邻看门狗发现（环形闭合）；
 * 全部被杀 → 引擎心跳也随之停滞 → ShieldGate 门禁 fail-closed。
 */
internal class WatchdogRing(
    private val onTrip: () -> Unit,
) {
    private class Slot {
        @Volatile
        var ts = 0L

        @Volatile
        var fp = ""

        @Volatile
        var tripped = false

        private val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        fun beat() {
            val t = System.currentTimeMillis()
            ts = t
            fp = hmacOf(t)
        }

        fun reset() {
            ts = 0L
            fp = ""
            tripped = false
        }

        fun isStale(): Boolean {
            if (ts == 0L) return false
            if (System.currentTimeMillis() - ts > ShieldGate.STALE_MS) return true
            return hmacOf(ts) != fp
        }

        private fun hmacOf(value: Long): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(value).array())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private val slots = Array(3) { Slot() }
    private val threads = mutableListOf<Thread>()
    private val rand = java.security.SecureRandom()

    @Volatile
    var trips = 0
        private set

    /** 环是否存活：任何线程正在运行即为存活（trips 是历史计数，不等于线程死亡） */
    val isRingAlive: Boolean
        get() = threads.any { it.isAlive }

    fun start() {
        if (threads.isNotEmpty()) return
        // 重启时复位所有槽位（防止旧时间戳立即 stale 误 trip）
        slots.forEach { it.reset() }
        for (i in 0 until 3) {
            val idx = i
            val t = Thread({ loop(idx) }, "syna-watchdog-$idx")
            t.isDaemon = true
            t.start()
            threads += t
        }
    }

    fun stop() {
        threads.forEach { it.interrupt() }
        threads.clear()
        slots.forEach { it.reset() }
    }

    private fun loop(idx: Int) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(4_000L + rand.nextInt(3_001).toLong())
            } catch (e: InterruptedException) {
                return
            }
            val target = (idx + 1) % 3
            if (slots[target].isStale()) {
                // 槽位停滞：同一槽位只触发一次（复位后由相邻线程重新启动心跳）
                if (!slots[target].tripped) {
                    slots[target].tripped = true
                    trips++
                    try {
                        onTrip()
                    } catch (e: Exception) {
                    }
                }
                // 即使被盯槽位停滞也更新自己的槽位（防级联误 trip：
                // 健康线程若停跳自己的槽位，12s 后自身也被判 stale 二次报警）
                slots[idx].beat()
            } else {
                slots[target].tripped = false
                slots[idx].beat()
            }
        }
    }
}

/** Shield 运行健康状态（设置页实时面板数据） */
data class ShieldHealth(
    val gateArmed: Boolean,
    val gateFresh: Boolean,
    val lastBeatAt: Long,
    val watchdogTrips: Int,
    val watchdogAlive: Boolean,
    val biometricFailCount: Int,
    val lastCheckAt: Long,
)

/**
 * Shield 控制器：收集威胁、管理状态机、审计时间线。
 * 策略：任一威胁上报即锁定；解锁后若存在严重级威胁，30 秒内未消除将自动重新锁定。
 * 加固（开源公开仍有效）：
 * - ShieldGate 心跳门禁：解密路径 fail-closed，检测线程被暂停即等效锁定；
 * - WatchdogRing：环形哨兵互盯，任何线程停滞立即锁定；
 * - 注入类威胁（Frida/篡改）进入假锁模式：密钥真实释放，解锁需连续多次验证；
 * - 生物识别连续失败触发暴力防护与自毁。
 */
class ShieldController(
    enabled: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    eventsPathOverride: String? = null,
    memoryWipeDelayMs: Long = MEMORY_WIPE_DELAY_MS,
    totpSeedPathOverride: String? = null,
) {
    private val engine: ShieldEngine = createShieldEngine { threat -> reportThreat(threat) }
    private val stateM = MutableStateFlow(if (enabled) ShieldState.ARMED else ShieldState.UNLOCKED)
    val state: StateFlow<ShieldState> = stateM.asStateFlow()

    private val watchdog = WatchdogRing { watchdogTrip() }

    // 假锁模式（Honeypot）：注入类威胁激活；外观与真锁一致，但密钥已真实释放，
    // 且解锁需连续多次生物识别验证（攻击者无生物特征 → 无法脱身，审计留痕）
    private val honeypotM = MutableStateFlow(false)
    val honeypot: StateFlow<Boolean> = honeypotM.asStateFlow()
    private var honeypotStreak = 0
    private val HONEYPOT_REQUIRED_STREAK = 3

    // 双重验证（TOTP 2FA）：生物识别通过后还需第二因子动态码
    private val totpSeedPath: String? = totpSeedPathOverride
    private val totpEnabledM = MutableStateFlow(TotpSeedStore.load(totpSeedPath) != null)
    val totpEnabled: StateFlow<Boolean> = totpEnabledM.asStateFlow()

    // 状态内存防篡改：每次变更同步 HMAC 签名值，关键路径校验一致性，
    // 防止攻击者直接改写内存中的状态绕过锁定。
    private val ShieldConfigGuardHmacKey: ByteArray = ByteArray(32).also {
        java.security.SecureRandom().nextBytes(it)
    }

    private var stateHmac = hmacOf(stateM.value.name)

    private fun setState(newState: ShieldState) {
        val prev = stateM.value
        stateM.value = newState
        stateHmac = hmacOf(newState.name)
        // 锁定即释放会话密钥；解除锁定恢复门禁（等待引擎心跳）
        if (newState == ShieldState.LOCKED && prev != ShieldState.LOCKED) {
            ShieldGate.releaseSession()
            SessionKeyStore.invalidateSession()
            // 新威胁打断关闭流程：清除关闭待定状态（防解锁时误关护盾）
            disablePending = false
            disableCallback = null
            disablingM.value = false
            recordEvent(ShieldThreat.INACTIVE, ShieldAction.KEY_RELEASED)
        } else if (newState != ShieldState.LOCKED && prev == ShieldState.LOCKED) {
            ShieldGate.restoreSession()
        }
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

    // 威胁明细（如具体可疑模块路径）：threat → 描述，锁定页/审计展示
    private val threatDetailsM = MutableStateFlow<Map<ShieldThreat, String>>(emptyMap())
    val threatDetails: StateFlow<Map<ShieldThreat, String>> = threatDetailsM.asStateFlow()

    /** 设置威胁明细（检测器上报具体对象：模块路径/进程名等） */
    fun setThreatDetail(threat: ShieldThreat, detail: String) {
        threatDetailsM.value = threatDetailsM.value + (threat to detail)
    }

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

    // 生物识别失败计数（暴力防护；持久化防重启清零）
    private val biometricFailsM = MutableStateFlow(loadBiometricFails())
    val biometricFails: StateFlow<Int> = biometricFailsM.asStateFlow()

    // 解锁冷却（指数退避）：失败后 1s/2s/4s...封顶 64s，成功清零
    private var nextUnlockAt = 0L
    private var cooldownMs = 0L

    // 会话密钥轮换：解锁成功后触发（前向安全——旧密钥全量迁移后失效）
    private var sessionRotateCallback: (() -> Unit)? = null
    private var honeypotCallback: (() -> Unit)? = null

    // 双因子关闭：生物识别 + TOTP 通过后才真正关闭护盾（攻击者无法关闭已开启的护盾）
    private val disablingM = MutableStateFlow(false)
    val disabling: StateFlow<Boolean> = disablingM.asStateFlow()
    private var disablePending = false
    private var disableCallback: (() -> Unit)? = null

    // 后台内存擦除：切后台延迟擦除明文缓存，回前台恢复
    private var memoryWipeCallback: (() -> Unit)? = null
    private var memoryRestoreCallback: (() -> Unit)? = null
    private var wipeJob: Job? = null
    private var memoryWiped = false
    private val memoryWipeDelayMs: Long = memoryWipeDelayMs

    // 健康状态（实时面板）
    private val healthM = MutableStateFlow(
        ShieldHealth(false, false, 0L, 0, false, 0, System.currentTimeMillis()),
    )
    val health: StateFlow<ShieldHealth> = healthM.asStateFlow()
    private var healthJob: Job? = null

    fun start() {
        if (!enabledM.value) return
        current = this
        ShieldGate.arm()
        watchdog.start()
        loadPersistedEvents()
        engine.start()
        startHealthLoop()
    }

    fun stop() {
        engine.stop()
        watchdog.stop()
        healthJob?.cancel()
        unlockExpiryJob?.cancel()
        relockJob?.cancel()
        wipeJob?.cancel()
        ShieldGate.disarm()
        if (current === this) current = null
    }

    fun setEnabled(on: Boolean) {
        enabledM.value = on
        // 停用时取消解锁有效期与严重级再锁任务，防止禁用后仍被自动锁定
        if (!on) {
            unlockExpiryJob?.cancel()
            relockJob?.cancel()
            healthJob?.cancel()
        }
        if (on) {
            disablingM.value = false
            disablePending = false
            disableCallback = null
            honeypotM.value = false
            honeypotStreak = 0
            setState(ShieldState.ARMED)
            threatsM.value = emptyList()
            current = this
            ShieldGate.arm()
            watchdog.start()
            engine.start()
            startHealthLoop()
        } else {
            engine.stop()
            watchdog.stop()
            ShieldGate.disarm()
            SessionKeyStore.invalidateSession()
            setState(ShieldState.UNLOCKED)
            if (current === this) current = null
        }
    }

    /** 实时健康循环（3s 刷新面板数据） */
    private fun startHealthLoop() {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                healthM.value = ShieldHealth(
                    gateArmed = true,
                    gateFresh = ShieldGate.isFresh(),
                    lastBeatAt = ShieldGate.lastBeatAt(),
                    watchdogTrips = watchdog.trips,
                    // 存活 = 环未断裂（trips 只是历史计数，不代表线程死亡）
                    watchdogAlive = watchdog.isRingAlive,
                    biometricFailCount = biometricFailsM.value,
                    lastCheckAt = now,
                )
                delay(3_000L)
            }
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
            recordEvent(threat, ShieldAction.DETECTED)
        }
        maybeSelfDestruct(threat)
        // 低危提示类威胁（时钟跳变/未启用锁屏）仅记录审计，不强制锁定，避免误锁
        val advisoryOnly = threat == ShieldThreat.CLOCK_CHANGED || threat == ShieldThreat.WEAK_LOCK
        if (!advisoryOnly) {
            if (threat != ShieldThreat.INACTIVE) {
                // 注入类威胁 → 假锁模式（Honeypot）：外观与真锁一致，密钥真实释放；
                // 仅首次进入时重置 streak（周期重报不清零，否则逃生通道永远无法达成）
                if (threat == ShieldThreat.FRIDA_DETECTED || threat == ShieldThreat.SHIELD_TAMPERED) {
                    if (!honeypotM.value) {
                        honeypotM.value = true
                        honeypotStreak = 0
                        recordEvent(threat, ShieldAction.HONEYPOT)
                        // 主动数据污染：通知 App 写入诱饵消息
                        try {
                            honeypotCallback?.invoke()
                        } catch (e: Exception) {
                        }
                    }
                }
                setState(ShieldState.LOCKED)
                recordEvent(threat, ShieldAction.LOCKED)
            } else if (current.isEmpty() || (current.size == 1 && current.first() == ShieldThreat.INACTIVE)) {
                setState(ShieldState.LOCKED)
                recordEvent(threat, ShieldAction.LOCKED)
            }
        }
    }

    /** 看门狗触发：检测/哨兵线程停滞 → 强制锁定（无论威胁列表状态）+ 主动自愈 */
    private fun watchdogTrip() {
        if (!enabledM.value) return
        recordEvent(ShieldThreat.WATCHDOG_TRIP, ShieldAction.WATCHDOG)
        reportThreat(ShieldThreat.WATCHDOG_TRIP)
        // 主动对抗：trip 后尝试重启检测扫描（自愈）——
        // 若检测线程被暂停（非杀死），重启可恢复心跳；若被杀死，重启即重生
        try {
            engine.stop()
            engine.start()
        } catch (e: Exception) {
        }
        // 自愈完成后自动清除看门狗威胁（心跳已恢复 = 停滞解除），
        // 防止 WATCHDOG_TRIP 永久残留导致解锁后 30s 无限再锁循环
        try {
            val recovered = scope.launch {
                delay(3_000)
                if (ShieldGate.isFresh() && threatsM.value.contains(ShieldThreat.WATCHDOG_TRIP)) {
                    clearThreat(ShieldThreat.WATCHDOG_TRIP)
                }
            }
        } catch (e: Exception) {
        }
    }

    fun clearThreat(threat: ShieldThreat) {
        val current = threatsM.value.filterNot { it == threat }
        threatsM.value = current
        threatDetailsM.value = threatDetailsM.value - threat
        recordEvent(threat, ShieldAction.CLEARED)
        // 仅 UNLOCKED 状态回 ARMED（威胁消除 ≠ 解锁）：
        // LOCKED/AWAITING_TOTP 必须保持锁定，仍需生物识别/第二因子验证——
        // 否则锁定后威胁一消失（VPN 恢复等）就免验证进入应用（绕过漏洞）
        if (current.isEmpty() && stateM.value == ShieldState.UNLOCKED) {
            setState(ShieldState.ARMED)
        }
    }

    /** 用户请求解锁（触发平台生物识别；冷却期内直接忽略） */
    fun requestUnlock() {
        stateIntact()
        if (!enabledM.value) {
            setState(ShieldState.UNLOCKED)
            return
        }
        // 已处于 TOTP 等待：不得用生物识别绕过第二因子
        if (stateM.value == ShieldState.AWAITING_TOTP) return
        val now = System.currentTimeMillis()
        if (now < nextUnlockAt) {
            // 冷却期：静默忽略（不弹窗、不计数），防连续尝试
            return
        }
        engine.requestBiometricUnlock { granted ->
            if (granted) {
                if (totpEnabledM.value && stateM.value == ShieldState.LOCKED) {
                    // 第一因子通过：进入 TOTP 等待（第二因子）
                    setState(ShieldState.AWAITING_TOTP)
                    return@requestBiometricUnlock
                }
                if (honeypotM.value) {
                    // 假锁模式：一次验证不够——连续成功多次才放行（攻击者无生物特征）
                    honeypotStreak++
                    recordEvent(ShieldThreat.FRIDA_DETECTED, ShieldAction.HONEYPOT)
                    if (honeypotStreak >= HONEYPOT_REQUIRED_STREAK) {
                        honeypotM.value = false
                        honeypotStreak = 0
                        setState(ShieldState.UNLOCKED)
                        recordEvent(ShieldThreat.INACTIVE, ShieldAction.UNLOCKED)
                        scheduleRelockIfCritical()
                        scheduleUnlockExpiry()
                    }
                    // 未达次数：保持锁定（密钥保持释放）
                } else {
                    // 解锁成功：清零暴力失败计数与冷却
                    biometricFailsM.value = 0
                    persistBiometricFails(0)
                    nextUnlockAt = 0L
                    cooldownMs = 0L
                    // 先恢复门禁（gate 放行），再捕获会话密钥并轮换（前向安全）：
                    // 顺序保证迁移前能解密旧数据（解锁时序修复）
                    setState(ShieldState.UNLOCKED)
                    SessionKeyStore.captureAuth()
                    recordEvent(ShieldThreat.INACTIVE, ShieldAction.UNLOCKED)
                    scheduleRelockIfCritical()
                    scheduleUnlockExpiry()
                    sessionRotateCallback?.invoke()
                }
            } else {
                onBiometricFailed()
            }
        }
    }

    /**
     * 身份确认（敏感操作二次认证）：纯生物识别验证，不改变状态机、不计入暴力计数。
     * Shield 未启用时直接放行。
     */
    fun verifyIdentity(onResult: (Boolean) -> Unit) {
        if (!enabledM.value) {
            onResult(true)
            return
        }
        try {
            engine.requestBiometricUnlock(onResult)
        } catch (e: Exception) {
            onResult(false)
        }
    }

    /** 当前状态是否为 TOTP 等待（供 UI 判断） */
    val awaitingTotp: Boolean
        get() = stateM.value == ShieldState.AWAITING_TOTP

    /**
     * 验证 TOTP 第二因子：正确 → 解锁；错误 → 计入暴力防护（失败上限 + 冷却）。
     */
    fun verifyTotp(code: String) {
        if (stateM.value != ShieldState.AWAITING_TOTP) return
        if (!enabledM.value) return
        val seed = TotpSeedStore.load(totpSeedPath) ?: run {
            setState(ShieldState.LOCKED)
            return
        }
        if (TotpCode.verify(seed, code)) {
            // 清零暴力失败计数与冷却
            biometricFailsM.value = 0
            persistBiometricFails(0)
            nextUnlockAt = 0L
            cooldownMs = 0L
            if (disablePending) {
                // 双因子关闭：第二因子通过 → 真正关闭护盾
                disablePending = false
                disablingM.value = false
                val cb = disableCallback
                disableCallback = null
                setEnabled(false)
                recordEvent(ShieldThreat.INACTIVE, ShieldAction.DISABLED)
                cb?.invoke()
                return
            }
            // 解锁成功：门禁恢复后捕获会话密钥并轮换（前向安全）
            honeypotM.value = false
            honeypotStreak = 0
            setState(ShieldState.UNLOCKED)
            SessionKeyStore.captureAuth()
            recordEvent(ShieldThreat.INACTIVE, ShieldAction.UNLOCKED)
            scheduleRelockIfCritical()
            scheduleUnlockExpiry()
            sessionRotateCallback?.invoke()
        } else {
            // 错误码：回到锁定，计入失败（触发冷却与暴力上限）
            setState(ShieldState.LOCKED)
            onBiometricFailed()
        }
    }

    /** 开启双重验证：生成种子并保存（首次需在 TOTP 应用中导入 otpauth URI） */
    fun enableTotp(): String? {
        // 先清理可能损坏的旧种子文件（否则 load 校验失败 → 永远无法启用）
        TotpSeedStore.clear(totpSeedPath)
        val seed = TotpCode.newSeed()
        // 种子保存失败则不启用（避免"看似开启实则无种子"的永久锁定）
        TotpSeedStore.save(seed, totpSeedPath)
        if (TotpSeedStore.load(totpSeedPath) == null) {
            // 不静默失败：明确告知用户
            try {
                com.syna.util.notifyMessage("Syna", "双重验证启用失败：安全存储不可用")
            } catch (e: Exception) {
            }
            return null
        }
        totpEnabledM.value = true
        return TotpCode.otpauthUri(seed, deviceLabel())
    }

    /** 生物识别硬件不可用（未录入/无传感器）：提示但不计入暴力失败 */
    fun onBiometricUnavailable() {
        try {
            com.syna.util.notifyMessage("Syna", "设备无可用生物识别，请先录入指纹或面容")
        } catch (e: Exception) {
        }
    }

    /** 关闭双重验证：清除种子（需先处于已解锁或监测状态） */
    fun disableTotp() {
        TotpSeedStore.clear(totpSeedPath)
        totpEnabledM.value = false
        if (stateM.value == ShieldState.AWAITING_TOTP) {
            setState(ShieldState.LOCKED)
        }
    }

    private fun deviceLabel(): String {
        return try {
            val os = System.getProperty("os.name") ?: "device"
            val model = System.getProperty("os.arch") ?: ""
            "$os $model"
        } catch (e: Exception) {
            "device"
        }
    }

    /**
     * 生物识别失败处理：连续失败 [BIOMETRIC_FAIL_LIMIT] 次 → 暴力防护：
     * 释放会话密钥（门禁失效）+ 触发自毁协议（若启用）+ 锁定。
     * 计数加密持久化，重启无法清零绕过。
     */
    fun onBiometricFailed() {
        if (!enabledM.value) return
        val fails = biometricFailsM.value + 1
        biometricFailsM.value = fails
        persistBiometricFails(fails)
        // 冷却指数退避：1s → 2s → 4s ... 封顶 64s
        if (cooldownMs == 0L) {
            cooldownMs = 1_000L
        } else {
            cooldownMs = minOf(cooldownMs * 2, 64_000L)
        }
        nextUnlockAt = System.currentTimeMillis() + cooldownMs
        if (fails >= BIOMETRIC_FAIL_LIMIT) {
            biometricFailsM.value = 0
            persistBiometricFails(0)
            nextUnlockAt = 0L
            cooldownMs = 0L
            val current = threatsM.value
            if (ShieldThreat.BRUTE_FORCE !in current) {
                threatsM.value = current + ShieldThreat.BRUTE_FORCE
            }
            recordEvent(ShieldThreat.BRUTE_FORCE, ShieldAction.DETECTED)
            maybeSelfDestruct(ShieldThreat.BRUTE_FORCE)
            setState(ShieldState.LOCKED)
            recordEvent(ShieldThreat.BRUTE_FORCE, ShieldAction.LOCKED)
        }
    }

    private fun biometricFailsFile(): String = "$eventsFile.fails"

    private fun persistBiometricFails(count: Int) {
        try {
            val enc = ShieldStorageKey.encryptWithMaster(count.toString().toByteArray())
            val file = java.io.File(biometricFailsFile())
            if (enc != null) {
                file.writeBytes(enc)
            } else {
                file.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun loadBiometricFails(): Int {
        return try {
            val file = java.io.File(biometricFailsFile())
            if (!file.exists()) return 0
            ShieldStorageKey.decryptWithMaster(file.readBytes())?.decodeToString()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
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
     * 双因子关闭：生物识别（+ 若开启 2FA 则 TOTP 第二因子）通过后才真正停用护盾。
     * 攻击者既无生物特征也无第二因子种子 → 已开启的护盾无法被关闭；
     * 锁定中尝试关闭同样需要双因子，失败计入暴力防护（自毁兜底）。
     */
    fun requestDisableWithVerification(onDisabled: () -> Unit = {}) {
        if (!enabledM.value) return
        engine.requestBiometricUnlock { granted ->
            if (granted) {
                if (totpEnabledM.value) {
                    disablePending = true
                    disableCallback = onDisabled
                    disablingM.value = true
                    setState(ShieldState.AWAITING_TOTP)
                } else {
                    setEnabled(false)
                    recordEvent(ShieldThreat.INACTIVE, ShieldAction.DISABLED)
                    onDisabled()
                }
            } else {
                onBiometricFailed()
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

    /** 注册会话密钥轮换回调（解锁成功后触发；由 App 接入 SessionKeyStore 轮换 + 全量重写） */
    fun setSessionRotateCallback(callback: () -> Unit) {
        sessionRotateCallback = callback
    }

    /** 注册假锁激活回调（App 接入"诱饵消息写入"：污染攻击者视野） */
    fun setHoneypotCallback(callback: () -> Unit) {
        honeypotCallback = callback
    }

    /**
     * 注册后台内存擦除回调：切后台一段时间后释放内存明文缓存（数据仍在加密盘上）。
     * restore 回调在重新回到前台时恢复（从加密存储重载）。
     */
    fun setMemoryWipeCallbacks(wipe: () -> Unit, restore: () -> Unit) {
        memoryWipeCallback = wipe
        memoryRestoreCallback = restore
    }

    /** 切到后台：延迟擦除内存明文（默认 60 秒），回前台前取消 */
    fun onAppBackgrounded() {
        clearOwnClipboard()
        wipeJob?.cancel()
        val wipe = memoryWipeCallback ?: return
        wipeJob = scope.launch {
            delay(memoryWipeDelayMs)
            if (!ShieldGate.isFresh()) return@launch // 已锁定：锁定路径负责擦除
            try {
                wipe()
                memoryWiped = true
            } catch (e: Exception) {
            }
        }
    }

    /** 回到前台：取消延迟擦除；已擦除则触发重载恢复 */
    fun onAppForegrounded() {
        wipeJob?.cancel()
        if (memoryWiped) {
            memoryWiped = false
            try {
                memoryRestoreCallback?.invoke()
            } catch (e: Exception) {
            }
        }
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
                val content = ShieldStorageKey.encryptWithMaster(plain.toByteArray())
                    ?.let { java.util.Base64.getEncoder().encodeToString(it) }
                    ?: plain
                val hash = sha256("$prevHash|$content")
                file.appendText("$content|$prevHash|$hash\n")
                // 事件文件封顶：超过上限保留最近 N 条（截断哈希链起点）
                if (file.length() > MAX_PERSISTED_EVENTS * 220L) {
                    val keep = file.readLines().takeLast(MAX_PERSISTED_EVENTS)
                    java.io.FileOutputStream(file).use { out ->
                        keep.forEach { out.write((it + "\n").toByteArray()) }
                    }
                }
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
                        ShieldStorageKey.decryptWithMaster(bytes)?.decodeToString()
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
        const val MAX_PERSISTED_EVENTS = 2_000 // 事件文件封顶（防无限增长）
        const val GENESIS_HASH = "genesis" // 哈希链起点
        const val BIOMETRIC_FAIL_LIMIT = 5
        const val MEMORY_WIPE_DELAY_MS = 60_000L // 切后台 60 秒后擦除内存明文

        /** 进程级当前实例（供通知隐藏等模块读取锁定状态） */
        @Volatile
        var current: ShieldController? = null

        val isLocked: Boolean
            get() = current?.state?.value == ShieldState.LOCKED

        /** 检测是否应锁定：当前威胁列表非空（含闲置） */
        fun hasActiveThreat(threats: List<ShieldThreat>): Boolean = threats.isNotEmpty()
    }
}
