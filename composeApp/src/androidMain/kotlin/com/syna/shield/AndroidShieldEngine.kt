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
        }

        fun detach() {
            instance?.activeActivity = null
        }
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

    override fun start() {
        // 反应更快：轻量检测（root/frida/调试/凭据/签名）每 3s 一次，
        // 重量级检测（应用枚举/无障碍/设备管理）每 15s 一次
        scanJob = scope.launch {
            var tick = 0L
            while (true) {
                runLightChecks()
                if (tick % (HEAVY_SCAN_INTERVAL_MS / LIGHT_SCAN_INTERVAL_MS) == 0L) {
                    runHeavyChecks()
                }
                tick++
                delay(LIGHT_SCAN_INTERVAL_MS)
            }
        }
        monitorVpn()
        monitorLifecycle()
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    /** 轻量高频检测（3s）：注入/调试/签名类，反应迅速 */
    private fun runLightChecks() {
        // 防重打包：签名指纹不一致 → 严重威胁
        if (!verifySignature()) {
            onThreat(ShieldThreat.SHIELD_TAMPERED)
        }
        if (isRooted()) onThreat(ShieldThreat.ROOT_DETECTED)
        if (hasFrida() || detectProcessInjection()) onThreat(ShieldThreat.FRIDA_DETECTED)
        if (isEmulator()) onThreat(ShieldThreat.EMULATOR_DETECTED)
        if (isDebugMode()) onThreat(ShieldThreat.DEBUG_MODE)
        checkCredentialChange()
        checkForegroundApp()
        // VPN 变更由回调单独触发
    }

    /** 重量级低频检测（15s）：应用枚举类 */
    private fun runHeavyChecks() {
        if (hasMonitoringApps()) onThreat(ShieldThreat.MONITORING_APP)
        if (hasAbusiveAccessibility()) onThreat(ShieldThreat.ACCESSIBILITY_ABUSE)
        checkDeviceAdminChange()
        checkAccessibilityChange()
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
        return suPaths.any { File(it).exists() } ||
            hasMagisk ||
            hasXposed ||
            System.getenv("PATH")?.split(":")?.any { dir ->
                File(dir, "su").exists()
            } == true
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

    private fun monitorVpn() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
        } catch (e: Exception) {
        }
    }

    private fun monitorLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        isForeground = false
                        backgroundAt = System.currentTimeMillis()
                        onBackground()
                    }
                    Lifecycle.Event.ON_START -> {
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
            },
        )
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
            val prompt = XBiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : XBiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: XBiometricPrompt.AuthenticationResult) {
                        onResult(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != XBiometricPrompt.ERROR_USER_CANCELED) {
                            onResult(false)
                        }
                    }

                    override fun onAuthenticationFailed() {
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
                prompt.authenticate(info)
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
