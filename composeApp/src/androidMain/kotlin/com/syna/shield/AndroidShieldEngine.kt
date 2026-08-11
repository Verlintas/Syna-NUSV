package com.syna.shield

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.biometric.BiometricPrompt as XBiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.syna.SynaApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

actual fun createShieldEngine(onThreat: (ShieldThreat) -> Unit): ShieldEngine =
    AndroidShieldEngine.instance(onThreat)

/**
 * 已知监控/远程控制/录屏类应用包名片段（检测白名单，随版本补充）。
 * 仅作为提示项：命中不代表必然恶意，用户可自行判断。
 */
private val KNOWN_MONITORING_FRAGMENTS = listOf(
    "com.teamviewer", "com.anydesk", "air.com.xtremelabs.android", // 远程控制
    "com.mobisec", "com.oxitec", "com.secugen", "com.cleverfiles", // 监控/录屏类
    "net.mobz", "com.genymotion", // 模拟器
)

class AndroidShieldEngine private constructor(
    private val context: Context,
    private var onThreat: (ShieldThreat) -> Unit,
) : ShieldEngine {

    companion object {
        const val LIGHT_SCAN_INTERVAL_MS = 3_000L
        const val HEAVY_SCAN_INTERVAL_MS = 15_000L
        const val SCAN_INTERVAL_MS = 3_000L
        const val BACKGROUND_SWITCH_THRESHOLD_MS = 1_500L
        private var instance: AndroidShieldEngine? = null

        fun instance(onThreat: (ShieldThreat) -> Unit): AndroidShieldEngine {
            val existing = instance
            return if (existing != null) {
                existing.onThreat = onThreat
                existing
            } else {
                AndroidShieldEngine(SynaApp.context, onThreat).also { instance = it }
            }
        }

        /** 由 MainActivity 在 onResume/onPause 注册宿主（生物识别与防截屏需要 Activity） */
        fun attach(activity: Activity) {
            instance?.activeActivity = activity
            instance?.registerScreenCapture(activity)
        }

        fun detach() {
            // 先注销截屏回调（需要 activity 引用），再清宿主——顺序反了注销恒为 no-op
            instance?.unregisterScreenCapture()
            instance?.activeActivity = null
        }

        /** 当前宿主 Activity（供权限请求等使用；无宿主返回 null） */
        fun activeActivityOrNull(): Activity? = instance?.activeActivity
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private var backgroundAt: Long = 0
    private var lastVpnActive: Boolean? = null
    private var isForeground = true

    private var activeActivity: Activity? = null
    private var currentActivityHolder: ((Activity?) -> Unit)? = null
    private var lastAdminSignature: String? = null
    private var lastAccessibilitySignature: String? = null
    private var lastBiometricState: Int? = null
    private var lastWallClock: Long? = null
    private var lastElapsed: Long? = null
    private var lastCaSignature: String? = null
    private var lastGatewayMac: String? = null
    private var lastGatewayIp: String? = null
    private var lastNetworkFingerprint: String? = null
    private var lastMirroring: Boolean? = null
    private var rwxHits = 0
    private var lastIme: String? = null
    private var lastUsbAttached: Boolean? = null
    private var screenCaptureCallback: Any? = null

    override fun start() {
        // 数据级门禁初始化：生成会话密钥 blob（首启）
        SessionKeyStore.ensureBlob()
        // 反应更快：轻量检测（root/frida/调试/凭据/签名）每 3s 一次，
        // 重量级检测（应用枚举/无障碍/设备管理）每 15s 一次
        scanJob = scope.launch {
            var tick = 0L
            while (true) {
                try {
                    // 心跳节拍：驱动 ShieldGate 门禁（停滞 → 解密路径 fail-closed 拒绝）；
                    // 同步驱动 native 心跳槽（JVM hook 免疫通道）
                    ShieldGate.beat()
                    if (NativeShield.loaded) {
                        NativeShield.gateBeat()
                    }
                    runLightChecks()
                    if (tick % (HEAVY_SCAN_INTERVAL_MS / LIGHT_SCAN_INTERVAL_MS) == 0L) {
                        runHeavyChecks()
                    }
                } catch (e: Throwable) {
                    // 自保：单轮检测异常不得杀死扫描循环（否则心跳停滞，
                    // 虽然门禁 fail-closed 保护数据，但护盾自身失去响应能力）
                    println("[Syna:Shield] scan error: ${e.message}")
                }
                // 重量级检测（APK 哈希等）耗时可观：结束后刷新双侧心跳
                if (tick % (HEAVY_SCAN_INTERVAL_MS / LIGHT_SCAN_INTERVAL_MS) == 0L) {
                    ShieldGate.beat()
                    if (NativeShield.loaded) {
                        NativeShield.gateBeat()
                    }
                }
                tick++
                // 扫描间隔抖动（+0~2s）：防止攻击者精确预测检测时机
                delay(LIGHT_SCAN_INTERVAL_MS + java.security.SecureRandom().nextInt(2_001).toLong())
            }
        }
        monitorVpn()
        monitorLifecycle()
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
        // 注销 VPN 回调（防反复启停重复注册）
        vpnCallback?.let { cb ->
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
            }
            vpnCallback = null
        }
        lifecycleObserver?.let { obs ->
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
            } catch (e: Exception) {
            }
            lifecycleObserver = null
        }
    }

    /** 轻量高频检测（3s）：注入/调试/签名类，反应迅速 */
    private fun runLightChecks() {
        // 防重打包：签名指纹不一致 → 严重威胁
        if (!verifySignature()) {
            onThreat(ShieldThreat.SHIELD_TAMPERED)
        }
        // 运行时自校验：dex 哈希与基准比对（versionCode 未变的篡改 → 命中）
        if (!verifyDexIntegrity()) {
            onThreat(ShieldThreat.SHIELD_TAMPERED)
        }
        if (isRooted()) onThreat(ShieldThreat.ROOT_DETECTED)
        if (hasFrida() || detectProcessInjection()) onThreat(ShieldThreat.FRIDA_DETECTED)
        // native 代码完整性（对抗 inline/GOT hook）：自身被改 → 篡改；libc 被改 → 注入
        if (NativeShield.loaded) {
            try {
                val integrity = NativeShield.integrity()
                if (integrity and 1 != 0) onThreat(ShieldThreat.SHIELD_TAMPERED)
                if (integrity and 2 != 0) onThreat(ShieldThreat.FRIDA_DETECTED)
                if (integrity and 4 != 0) {
                    // 匿名可执行段：连续 3 次命中才上报（节流防厂商 ROM 误报）
                    rwxHits++
                    if (rwxHits >= 3) onThreat(ShieldThreat.FRIDA_DETECTED)
                } else {
                    rwxHits = 0
                }
            } catch (e: Throwable) {
            }
        }
        if (isEmulator()) onThreat(ShieldThreat.EMULATOR_DETECTED)
        if (isDebugMode()) onThreat(ShieldThreat.DEBUG_MODE)
        if (isSelinuxPermissive()) onThreat(ShieldThreat.SELINUX_DISABLED)
        checkScreenMirroring()
        checkDowngradeAttempt()
        checkCredentialChange()
        checkForegroundApp()
        checkNetworkFingerprint()
        checkClockChange()
        checkWeakLock()
        checkImeChange()
        checkUsbChange()
        checkSystemProxy()
        checkDeviceIdentity()
        val suspicious = suspiciousModules()
        if (suspicious.isNotEmpty()) {
            onThreat(ShieldThreat.SUSPICIOUS_MODULE)
            // 明细上报：具体模块路径（锁定页/审计可查）
            ShieldController.current?.setThreatDetail(
                ShieldThreat.SUSPICIOUS_MODULE,
                "可疑模块: " + suspicious.joinToString(", ") { it.substringAfterLast('/') },
            )
        }
        // VPN 变更由回调单独触发
    }

    /** 重量级低频检测（15s）：应用枚举类 + 完整性命中 */
    private fun runHeavyChecks() {
        if (hasMonitoringApps()) onThreat(ShieldThreat.MONITORING_APP)
        if (hasAbusiveAccessibility()) onThreat(ShieldThreat.ACCESSIBILITY_ABUSE)
        checkDeviceAdminChange()
        checkAccessibilityChange()
        checkCaChange()
        checkArpSpoof()
        // 运行时自校验（dex 哈希，全量读 APK，放重量级档）
        if (!verifyDexIntegrity()) {
            onThreat(ShieldThreat.SHIELD_TAMPERED)
        }
        // 审计完整性（文件 IO）：审计日志被外部清除 → 篡改
        checkAuditIntegrity()
    }

    /** 时钟跳变检测：墙钟与系统运行计时偏差 > 5 分钟 → 低危提示（审计与消息时效受影响） */
    private fun checkClockChange() {
        try {
            val wall = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (lastWallClock != null && lastElapsed != null) {
                val wallDelta = wall - lastWallClock!!
                val elapsedDelta = elapsed - lastElapsed!!
                if (kotlin.math.abs(wallDelta - elapsedDelta) > 5 * 60_000L) {
                    onThreat(ShieldThreat.CLOCK_CHANGED)
                }
            }
            lastWallClock = wall
            lastElapsed = elapsed
        } catch (e: Exception) {
        }
    }

    /** 锁屏未启用提示：无锁屏则生物识别不可用（低危提示，不锁定） */
    private fun checkWeakLock() {
        try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            if (!km.isKeyguardSecure) {
                onThreat(ShieldThreat.WEAK_LOCK)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 前台应用感知（需使用情况访问授权）：
     * 监控类应用正在前台运行时，立即触发高威胁锁定——比单纯"已安装"信号更强。
     */
    private fun checkForegroundApp() {
        if (!shieldUsageAccessGranted()) return
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 60_000L, end)
            var foreground: String? = null
            var lastTs = 0L
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && event.timeStamp >= lastTs) {
                    foreground = event.packageName
                    lastTs = event.timeStamp
                }
            }
            foreground?.let { pkg ->
                if (KNOWN_MONITORING_FRAGMENTS.any { pkg.contains(it, ignoreCase = true) }) {
                    onThreat(ShieldThreat.MONITORING_APP)
                }
            }
        } catch (e: Exception) {
        }
    }

    // ===== 检测源（纯逻辑抽取，便于单元测试） =====

    internal fun isRooted(): Boolean {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/app/Superuser.apk", "/system/etc/init.d/99SuperSUDaemon",
        )
        // Magisk 隐藏 root 特征
        val magiskPaths = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/.magisk",
            "/cache/magisk.log", "/data/cache/magisk.log",
        )
        val magiskPackages = listOf("com.topjohnwu.magisk", "com.magisk")
        val hasMagisk = magiskPaths.any { File(it).exists() } ||
            context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).any {
                magiskPackages.contains(it.packageName)
            }
        // Xposed 框架特征
        val xposedPaths = listOf(
            "/system/framework/xposed.jar", "/system/lib/libxposed_art.so",
            "/system/lib64/libxposed_art.so",
        )
        val xposedInstaller = "de.robv.android.xposed.installer"
        val hasXposed = xposedPaths.any { File(it).exists() } ||
            context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).any {
                it.packageName == xposedInstaller
            }
        // Zygisk / Shamiko / LSPosed / Riru / EdXposed / TaiChi 隐藏框架特征
        val zygiskPaths = listOf("/data/adb/zygisk", "/data/adb/modules/zygisk")
        val shamikoPaths = listOf("/data/adb/modules/shamiko", "/data/adb/shamiko")
        val lsposedPaths = listOf("/data/adb/lspd", "/data/adb/modules/lsposed")
        val riruPaths = listOf("/data/adb/riru", "/data/adb/modules/riru-core", "/data/adb/modules/riru")
        val edxposedPaths = listOf("/data/adb/modules/edxposed", "/data/adb/modules/edxposed-sandhook", "/data/adb/modules/edxposed-yahfa")
        val taichiPaths = listOf("/data/adb/modules/taichi", "/data/adb/modules/me.weishu.exp")
        val lsposedPackages = listOf("org.lsposed.manager")
        val hasHiddenFramework = zygiskPaths.any { File(it).exists() } ||
            shamikoPaths.any { File(it).exists() } ||
            lsposedPaths.any { File(it).exists() } ||
            riruPaths.any { File(it).exists() } ||
            edxposedPaths.any { File(it).exists() } ||
            taichiPaths.any { File(it).exists() } ||
            context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).any {
                it.packageName == "org.lsposed.manager"
            }
        // SELinux 进程域特征：magisk/zygisk 注入会改变进程 context
        val suspiciousContext = try {
            val ctx = java.io.File("/proc/self/attr/current").readText().trim()
            ctx.contains("magisk") || ctx.contains("zygisk")
        } catch (e: Exception) {
            false
        }
        return suPaths.any { File(it).exists() } ||
            hasMagisk ||
            hasXposed ||
            hasHiddenFramework ||
            suspiciousContext ||
            System.getenv("PATH")?.split(":")?.any { dir ->
                File(dir, "su").exists()
            } == true
    }

    /** 设备身份检测：ANDROID_ID 与基准比对（重装/恢复备份 → 提示；主密钥加密基准） */
    private fun checkDeviceIdentity() {
        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            ) ?: return
            val baseFile = java.io.File(context.filesDir, "syna_device_base")
            if (!baseFile.exists()) {
                baseFile.writeBytes(ShieldStorageKey.encryptWithMaster(androidId.toByteArray()) ?: return)
                return
            }
            val base = ShieldStorageKey.decryptWithMaster(baseFile.readBytes())?.decodeToString()
            if (base != null && base != androidId) {
                onThreat(ShieldThreat.DEVICE_CHANGED)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 审计缺失检测：Shield 运行过（有审计基准）但审计文件被外部删除/清空
     * （攻击者抹除痕迹）→ 上报篡改。自毁流程会先停用 Shield，不冲突。
     */
    private fun checkAuditIntegrity() {
        try {
            val events = java.io.File(shieldEventsPath())
            val seen = java.io.File(context.filesDir, "syna_audit_seen")
            if (events.exists() && events.length() > 0L) {
                if (!seen.exists()) {
                    seen.writeBytes(ShieldStorageKey.encryptWithMaster("1".toByteArray()) ?: return)
                }
            } else if (seen.exists()) {
                ShieldController.current?.reportThreat(ShieldThreat.SHIELD_TAMPERED)
                ShieldController.current?.setThreatDetail(ShieldThreat.SHIELD_TAMPERED, "审计日志被清除")
            }
        } catch (e: Exception) {
        }
    }

    /** 输入法变更检测：系统输入法切换（防被替换为键盘记录型 IME，advisory） */
    private fun checkImeChange() {
        try {
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            val prev = lastIme
            if (prev != null && prev != ime && ime.isNotEmpty()) {
                onThreat(ShieldThreat.IME_CHANGED)
            }
            if (ime.isNotEmpty()) lastIme = ime
        } catch (e: Exception) {
        }
    }

    /** 系统代理检测：全局 HTTP 代理被设置 → 流量可能经第三方中转（advisory） */
    private fun checkSystemProxy() {
        try {
            val proxy = Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY) ?: ""
            if (proxy.isNotBlank()) {
                onThreat(ShieldThreat.PROXY_SET)
                ShieldController.current?.setThreatDetail(
                    ShieldThreat.PROXY_SET,
                    "代理: $proxy",
                )
            }
        } catch (e: Exception) {
        }
    }

    /** USB 连接变化检测：USB 设备接入/移除（调试/数据提取窗口，advisory） */
    private fun checkUsbChange() {
        try {
            val usb = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val attached = usb.deviceList.isNotEmpty()
            val prev = lastUsbAttached
            if (prev != null && prev != attached) {
                onThreat(ShieldThreat.USB_CHANGED)
            }
            lastUsbAttached = attached
        } catch (e: Exception) {
        }
    }

    /**
     * 可疑可执行模块检测：maps 中不在系统分区的可执行文件映射（注入痕迹，advisory）。
     * 白名单按"分区前缀"判定（/system /apex /vendor /product /system_ext /odm /
     * /data/app /data/user 全部放行）——厂商 ROM 自定义库不再误报；
     * 返回具体可疑路径列表（供锁定页/审计展示）。
     */
    internal fun suspiciousModules(): List<String> {
        return try {
            val maps = java.io.File("/proc/self/maps").readText()
            // 系统/应用分区前缀全部放行（覆盖厂商 ROM 的自定义库位置）
            val trustedPrefixes = listOf(
                "/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/",
                "/data/app/", "/data/user/", "/data/apex/",
            )
            val found = LinkedHashSet<String>()
            val lines = maps.split("\n")
            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 6) continue
                val perms = parts[1]
                val path = parts[5]
                if (!perms.contains("x")) continue
                if (!path.startsWith("/")) continue
                if (trustedPrefixes.any { path.startsWith(it) }) continue
                // 自身与运行时核心库（linker/libc/ART 等可能以非分区路径出现）
                if (path.contains("libsyna_shield") || path.contains("linker") ||
                    path.contains("libc.so") || path.contains("libart") ||
                    path.contains("libjavacore") || path.contains("libopenjdk") ||
                    path.contains("libandroid_runtime") || path.contains("libnativeloader")
                ) continue
                found.add(path)
                if (found.size >= 5) break // 最多报告 5 个，防刷屏
            }
            found.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** SELinux 状态：非强制模式 → 系统防护降级（低危提示） */
    internal fun isSelinuxPermissive(): Boolean {
        return try {
            val enforce = java.io.File("/sys/fs/selinux/enforce").readText().trim()
            enforce == "0"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 投屏/镜像变化检测：出现/消失额外演示显示屏（无线投屏/HDMI 镜像载体）→ 报警一次。
     * 持续存在不重复报警（避免反复锁定与审计刷屏）；WifiDisplay 状态为系统 API 不可用，
     * 以演示屏数量作为应用层可观测信号。
     */
    internal fun checkScreenMirroring() {
        val now = try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty()
        } catch (e: Exception) {
            false
        }
        val prev = lastMirroring
        if (prev != null && prev != now) {
            // 出现或消失均视为投屏状态变化（共享/镜像会话的起止）
            onThreat(ShieldThreat.SCREEN_SHARE_SUSPECT)
        }
        lastMirroring = now
    }

    /**
     * 防降级安装：版本低于上次运行记录 → 严重威胁（降级绕过防护的常见手法）。
     * 基准经 ShieldStorageKey 加密；正常升级自动更新基准。
     */
    internal fun checkDowngradeAttempt() {
        try {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            val file = java.io.File(context.filesDir, "syna_version_base")
            if (!file.exists()) {
                file.writeBytes(ShieldStorageKey.encrypt(version.toString().toByteArray()) ?: return)
                return
            }
            val base = ShieldStorageKey.decrypt(file.readBytes())?.decodeToString()?.toLongOrNull() ?: return
            when {
                version < base -> onThreat(ShieldThreat.DOWNGRADE_ATTEMPT)
                version > base -> file.writeBytes(ShieldStorageKey.encrypt(version.toString().toByteArray()) ?: return)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 用户 CA 证书变化检测：系统证书库新增用户证书（安装抓包证书等 MITM 前提）→ 提示。
     * 系统 CA 随 OTA 更新也可能变化（罕见），提示后用户可自行消除。
     */
    internal fun checkCaChange() {
        try {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            val aliases = ks.aliases().toList().sorted()
            // 仅关注用户证书域（系统 CA 随 OTA 更新会变化，纳入会误锁）：
            // AndroidCAStore 用户证书别名通常以 u0 开头或含 "/u0/"
            val userAliases = aliases.filter { it.startsWith("u") || it.contains("/u") }
            val signature = userAliases.joinToString("|")
            val prev = lastCaSignature
            if (prev != null && prev.isNotEmpty() && prev != signature) {
                onThreat(ShieldThreat.NETWORK_MITM)
            }
            lastCaSignature = signature
        } catch (e: Exception) {
        }
    }

    /** ARP 欺骗检测：默认网关 MAC 变化 → 局域网流量劫持迹象 */
    internal fun checkArpSpoof() {
        try {
            val gateway = defaultGateway() ?: return
            val mac = arpMacOf(gateway) ?: return
            // 网关 IP 变化 = 网络切换（DHCP 重连/换网），更新基准而非误报欺骗
            if (lastGatewayIp != null && lastGatewayIp != gateway) {
                lastGatewayMac = mac
            }
            val prev = lastGatewayMac
            if (prev != null && prev != mac) {
                onThreat(ShieldThreat.NETWORK_MITM)
            }
            lastGatewayMac = mac
            lastGatewayIp = gateway
        } catch (e: Exception) {
        }
    }

    private fun defaultGateway(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val link = cm.getLinkProperties(cm.activeNetwork) ?: return null
            link.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun arpMacOf(ip: String): String? {
        return try {
            val lines = java.io.File("/proc/net/arp").readLines().drop(1)
            val line = lines.firstOrNull { it.trim().startsWith(ip) } ?: return null
            val mac = line.split(Regex("\\s+")).getOrNull(3)
            mac?.takeIf { it.contains(":") }
        } catch (e: Exception) {
            null
        }
    }

    /** 网络环境指纹：SSID 变化 → 低危提示（信任网络概念的基础信号） */
    internal fun checkNetworkFingerprint() {
        try {
            val ssid = currentSsid() ?: return
            val prev = lastNetworkFingerprint
            if (prev != null && prev != ssid) {
                onThreat(ShieldThreat.NETWORK_CHANGED)
            }
            lastNetworkFingerprint = ssid
        } catch (e: Exception) {
        }
    }

    private fun currentSsid(): String? {
        return try {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    (caps.transportInfo as? android.net.wifi.WifiInfo)?.ssid
                } else null
            } else {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiInfo
                wm.ssid
            }
            // 无权限时系统返回占位符：忽略（不参与变化检测，避免误报）
            if (ssid.isNullOrEmpty() || ssid.contains("unknown")) null else ssid
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 运行时自校验：比对 APK dex 哈希与安装时固化的基准（经 ShieldStorageKey 加密）。
     * 应用正常升级（versionCode 变化）→ 重写基准；versionCode 未变的任何差异 → 篡改。
     * 源码公开环境下：即使攻击者 hook 签名校验，dex 哈希通道依然独立生效。
     */
    internal fun verifyDexIntegrity(): Boolean {
        return try {
            val appInfo = context.applicationInfo
            val dexes = buildList {
                add(java.io.File(appInfo.sourceDir))
                appInfo.splitSourceDirs?.forEach { add(java.io.File(it)) }
            }.filter { it.exists() }.sortedBy { it.name }
            val hash = dexes.joinToString("|") { d ->
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(d.readBytes())
                digest.joinToString("") { "%02x".format(it) }
            }
            val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$version|$hash".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val baseFile = java.io.File(context.filesDir, "syna_dex_base")
            if (!baseFile.exists()) {
                // 首次运行：固化基准
                baseFile.writeBytes(ShieldStorageKey.encrypt(digest.toByteArray()) ?: return true)
                return true
            }
            val base = ShieldStorageKey.decrypt(baseFile.readBytes())?.decodeToString()
            base == digest
        } catch (e: Exception) {
            true
        }
    }

    /**
     * 防重打包：校验当前 APK 签名指纹是否与官方发布一致。
     * 期望值来自 BuildConfig（构建注入）；被重打包/重新签名后必然不匹配。
     */
    internal fun verifySignature(): Boolean {
        // debug 构建跳过签名校验（避免开发/测试包被锁定页拦截）
        if (com.syna.BuildConfig.DEBUG) return true
        val expected = com.syna.BuildConfig.SYNA_SIGNATURE_HASH
        if (expected == "REPLACE_WITH_RELEASE_HASH") {
            // 未注入期望值：跳过校验
            return true
        }
        val actual = signatureHash() ?: return false
        return actual.equals(expected, ignoreCase = true)
    }

    private fun signatureHash(): String? {
        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
            val sig = info.signatures?.firstOrNull() ?: return null
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 进程注入检测：TracerPid（ptrace 调试/注入）+ /proc/self/maps Frida 特征库
     * + 线程名特征。TracerPid 是反调试最有效手段：Frida/gdb/strace 全部命中。
     */
    internal fun detectProcessInjection(): Boolean {
        // 0) native 通道（NDK，JVM hook 无法覆盖）——命中即报
        if (NativeShield.loaded) {
            try {
                if (NativeShield.tracerPid() != 0) return true
                if (NativeShield.fridaMaps() != 0) return true
                if (NativeShield.fridaThreads() != 0) return true
            } catch (e: Throwable) {
            }
        }
        // 1) TracerPid：/proc/self/status 中不为 0 即被 ptrace
        try {
            val status = java.io.File("/proc/self/status").readText()
            val m = Regex("TracerPid:\\s*(\\d+)").find(status)
            val pid = m?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: 0
            if (pid != 0) return true
        } catch (e: Exception) {
        }
        // 2) /proc/self/maps：Frida 特征库映射
        try {
            val maps = java.io.File("/proc/self/maps").readText()
            if (maps.contains("frida-gadget") || maps.contains("frida-agent")) return true
        } catch (e: Exception) {
        }
        // 3) 线程名特征（frida / gum-js-loop）
        try {
            val tasks = java.io.File("/proc/self/task").listFiles() ?: emptyArray()
            for (task in tasks) {
                val comm = java.io.File(task, "comm").readText().trim()
                if (comm.contains("frida", ignoreCase = true) || comm.contains("gum-js-loop")) {
                    return true
                }
            }
        } catch (e: Exception) {
        }
        return false
    }

    /** Frida 注入框架检测（frida-server 进程/端口/库特征） */
    internal fun hasFrida(): Boolean {
        // frida-server 常见驻留路径
        val fridaPaths = listOf(
            "/data/local/tmp/frida-server", "/data/local/tmp/frida",
            "/data/local/tmp/re.frida.server",
        )
        if (fridaPaths.any { File(it).exists() }) return true
        // frida 默认端口 27042 监听检测
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", 27042), 300)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    internal fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val brand = Build.BRAND ?: ""
        return fingerprint.contains("generic") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            brand.contains("generic")
    }

    internal fun isDebugMode(): Boolean {
        // USB 调试开启
        val adbEnabled = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
        return adbEnabled || android.os.Debug.isDebuggerConnected()
    }

    internal fun hasMonitoringApps(): Boolean {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA).any { app ->
            KNOWN_MONITORING_FRAGMENTS.any { fragment ->
                app.packageName.contains(fragment, ignoreCase = true)
            }
        }
    }

    internal fun hasAbusiveAccessibility(): Boolean {
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
        } catch (e: Exception) {
            ""
        }
        // 常见被滥用的无障碍服务特征（仅提示）
        return enabled.contains("com.teamviewer") ||
            enabled.contains("com.anydesk") ||
            enabled.contains("screenrecord")
    }

    /** 设备管理状态变更检测：新的设备管理应用激活（MDM 接管预警） */
    private fun checkDeviceAdminChange() {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admins = dpm.activeAdmins ?: emptyList()
            val signature = admins.sortedBy { it.flattenToString() }.joinToString("|")
            val prev = lastAdminSignature
            if (prev != null && prev != signature) {
                onThreat(ShieldThreat.DEVICE_ADMIN_CHANGE)
            }
            lastAdminSignature = signature
        } catch (e: Exception) {
        }
    }

    /** 无障碍服务列表变更检测（新增/移除无障碍服务） */
    private fun checkAccessibilityChange() {
        try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val prev = lastAccessibilitySignature
            if (prev != null && prev != enabled) {
                onThreat(ShieldThreat.ACCESSIBILITY_ABUSE)
            }
            lastAccessibilitySignature = enabled
        } catch (e: Exception) {
        }
    }

    /** 生物识别/凭据变更检测：生物识别可用状态变化（被移除/禁用）提示设备可能易主 */
    private fun checkCredentialChange() {
        // 兼容性：BIOMETRIC_SERVICE / Authenticators 需要 API 29+/30+，
        // minSdk 28 上直接调用会抛 NoSuchMethodError（Error 不被 catch(Exception) 捕获），
        // 因此先做版本检查——低版本跳过该检测项（不砍其他功能）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as android.hardware.biometrics.BiometricManager
            val state = bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val prev = lastBiometricState
            if (prev != null && prev != state && prev == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) {
                // 生物识别从可用变为不可用：凭据可能被移除或设备被刷机
                onThreat(ShieldThreat.CREDENTIAL_CHANGED)
            }
            lastBiometricState = state
        } catch (e: Exception) {
        }
    }

    /**
     * 截屏/录屏事件检测（Android 14+，API 34）：系统级回调，检测到屏幕被截取/录制瞬间触发。
     * 低版本以 FLAG_SECURE 防内容截取兜底，无事件级检测能力（如实声明）。
     */
    private fun registerScreenCapture(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val callback = object : Activity.ScreenCaptureCallback {
                override fun onScreenCaptured() {
                    onThreat(ShieldThreat.SCREEN_RECORDING)
                }
            }
            activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
            screenCaptureCallback = callback
        } catch (e: Exception) {
        }
    }

    private fun unregisterScreenCapture() {
        val callback = screenCaptureCallback as? Activity.ScreenCaptureCallback ?: return
        val activity = activeActivity ?: return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                activity.unregisterScreenCaptureCallback(callback)
            }
        } catch (e: Exception) {
        }
        screenCaptureCallback = null
    }

    private var vpnCallback: ConnectivityManager.NetworkCallback? = null

    private fun monitorVpn() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // 防重复注册：先注销旧的
        vpnCallback?.let { old ->
            try {
                cm.unregisterNetworkCallback(old)
            } catch (e: Exception) {
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val prev = lastVpnActive
                if (prev != null && prev != vpnActive) {
                    onThreat(ShieldThreat.VPN_CHANGE)
                }
                lastVpnActive = vpnActive
            }

            override fun onLost(network: Network) {
                if (lastVpnActive == true) {
                    onThreat(ShieldThreat.VPN_CHANGE)
                }
                lastVpnActive = null
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            vpnCallback = callback
        } catch (e: Exception) {
        }
    }

    private var lifecycleObserver: LifecycleEventObserver? = null

    private fun monitorLifecycle() {
        val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        isForeground = false
                        backgroundAt = System.currentTimeMillis()
                        onBackground()
                        ShieldController.current?.onAppBackgrounded()
                    }
                    Lifecycle.Event.ON_START -> {
                        ShieldController.current?.onAppForegrounded()
                        val now = System.currentTimeMillis()
                        val wasBackground = !isForeground
                        isForeground = true
                        if (wasBackground && now - backgroundAt < BACKGROUND_SWITCH_THRESHOLD_MS &&
                            backgroundAt > 0
                        ) {
                            // 极短后台切换：疑似屏幕共享/监控切换
                            onThreat(ShieldThreat.BACKGROUND_SWITCH)
                        }
                        onForeground()
                    }
                    else -> Unit
                }
            }
        lifecycleObserver = observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    override fun onForeground() {
        // 引擎侧无需额外处理（威胁上报由监测循环完成）
    }

    override fun onBackground() {
        // 引擎侧无需额外处理
    }

    override fun requestBiometricUnlock(onResult: (Boolean) -> Unit) {
        val activity = activeActivity
        if (activity == null) {
            onResult(false)
            return
        }
        if (activity is FragmentActivity) {
            // 数据级门禁：用认证绑定密钥构造 CryptoObject（窗口内 init 成功才附加），
            // 认证成功后立即捕获会话密钥（认证时间戳已刷新，重试必然成功）
            var prompt = XBiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : XBiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: XBiometricPrompt.AuthenticationResult) {
                        // 会话密钥由 ShieldController 在真正解锁时统一捕获
                        // （AWAITING_TOTP 阶段不捕获，第二因子通过前数据保持不可解）
                        onResult(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        when {
                            // 硬件不可用/未录入：提示但不计入暴力失败（防每次点击累积自毁倒计时）
                            errorCode == XBiometricPrompt.ERROR_NO_BIOMETRICS ||
                                errorCode == XBiometricPrompt.ERROR_HW_UNAVAILABLE ||
                                errorCode == XBiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> {
                                ShieldController.current?.onBiometricUnavailable()
                            }
                            // 用户取消：静默
                            errorCode == XBiometricPrompt.ERROR_USER_CANCELED -> Unit
                            // 其余错误：按失败处理（计入暴力计数）
                            else -> onResult(false)
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // 失败计数统一由控制器 onBiometricFailed 处理（防双计数）
                        onResult(false)
                    }
                },
            )
            val info = XBiometricPrompt.PromptInfo.Builder()
                .setTitle("Mirtazapine Shield 验证")
                .setSubtitle("使用生物识别确认是你本人在操作")
                .setNegativeButtonText("取消")
                .build()
            try {
                // 认证绑定 CryptoObject：init 失败（认证窗口外）→ 无 CryptoObject 普通认证，
                // 认证成功回调里仍会 captureAuth 重试捕获
                val crypto = runCatching {
                    val cipher = SessionKeyStore.authEncryptCipher()
                    cipher?.let { androidx.biometric.BiometricPrompt.CryptoObject(it) }
                }.getOrNull()
                if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
            } catch (e: Exception) {
                onResult(false)
            }
        } else {
            onResult(false)
        }
    }

    override fun setSecureScreen(secure: Boolean) {
        val activity = activeActivity
        if (activity != null) {
            activity.runOnUiThread {
                activity.window.setFlags(
                    if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }
    }
}
