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
import androidx.biometric.BiometricManager as XBiometricManager
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
        const val SCAN_INTERVAL_MS = 10_000L
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

    override fun start() {
        scanJob = scope.launch {
            while (true) {
                runThreatChecks()
                delay(SCAN_INTERVAL_MS)
            }
        }
        monitorVpn()
        monitorLifecycle()
    }

    override fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun runThreatChecks() {
        if (isRooted()) onThreat(ShieldThreat.ROOT_DETECTED)
        if (isEmulator()) onThreat(ShieldThreat.EMULATOR_DETECTED)
        if (isDebugMode()) onThreat(ShieldThreat.DEBUG_MODE)
        if (hasMonitoringApps()) onThreat(ShieldThreat.MONITORING_APP)
        if (hasAbusiveAccessibility()) onThreat(ShieldThreat.ACCESSIBILITY_ABUSE)
        // VPN 变更由回调单独触发
    }

    // ===== 检测源（纯逻辑抽取，便于单元测试） =====

    internal fun isRooted(): Boolean {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/app/Superuser.apk", "/system/etc/init.d/99SuperSUDaemon",
        )
        return suPaths.any { File(it).exists() } ||
            System.getenv("PATH")?.split(":")?.any { dir ->
                File(dir, "su").exists()
            } == true
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
