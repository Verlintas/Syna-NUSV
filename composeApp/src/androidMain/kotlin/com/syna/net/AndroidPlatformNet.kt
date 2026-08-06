package com.syna.net

import android.content.Context
import android.net.wifi.WifiManager
import com.syna.SynaApp

actual class PlatformNet {
    private var multicastLock: WifiManager.MulticastLock? = null

    actual fun lockMulticast() {
        try {
            val wifi = SynaApp.context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("SynaMulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    actual fun unlockMulticast() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    actual fun deviceName(): String = "Android"
}

actual fun platformNet(): PlatformNet = PlatformNet()
