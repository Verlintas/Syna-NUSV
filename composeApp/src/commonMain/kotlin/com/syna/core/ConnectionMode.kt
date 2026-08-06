package com.syna.core

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionMode(val label: String) {
    AUTO("自动"),
    TCP("TCP"),
    UDP("UDP"),
    HOTSPOT("主机热点");

    companion object {
        fun fromName(name: String?): ConnectionMode =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}
