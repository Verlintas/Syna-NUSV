package com.syna.net

actual class PlatformNet {
    actual fun lockMulticast() {
        // Desktop 无多播锁概念
    }

    actual fun unlockMulticast() {
    }

    actual fun deviceName(): String = "桌面(${System.getProperty("os.name") ?: "Unknown"})"
}

actual fun platformNet(): PlatformNet = PlatformNet()
