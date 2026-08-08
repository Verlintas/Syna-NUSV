package com.syna.net

import java.net.NetworkInterface

actual fun localIpAddresses(): Set<String> {
    val result = mutableSetOf<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            if (!intf.isUp) continue
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is java.net.Inet4Address) {
                    result.add(addr.hostAddress)
                }
            }
        }
    } catch (e: Exception) {
    }
    return result
}
