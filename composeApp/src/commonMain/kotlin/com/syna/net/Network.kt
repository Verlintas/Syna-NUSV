/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.syna.net

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

sealed interface IncomingEvent {
    data class PeerFrame(val peerId: String, val frame: TransportFrame) : IncomingEvent
    data class PeerConnected(val peerId: String) : IncomingEvent
    data class PeerDisconnected(val peerId: String) : IncomingEvent
}

interface DiscoveryService {
    val announcements: SharedFlow<Pair<DiscoveryAnnouncement, String>>
    val localAddress: String
    fun start()
    fun sendNow()
    fun stop()
}

interface ConnectionManager {
    val incoming: SharedFlow<IncomingEvent>
    val localTcpPort: Int
    val localUdpPort: Int
    fun start()
    suspend fun send(addr: PeerAddr, frame: TransportFrame)
    fun stop()
}

expect fun createDiscoveryService(
    announcement: DiscoveryAnnouncement,
    intervalMs: Long = DISCOVERY_INTERVAL_MS,
): DiscoveryService

expect fun createTcpTransport(myId: String, myPublicKeyB64: String): ConnectionManager

expect fun createUdpTransport(myId: String): ConnectionManager

expect class PlatformNet {
    fun lockMulticast()
    fun unlockMulticast()
    fun deviceName(): String
}

expect fun platformNet(): PlatformNet

/** 本机全部 IPv4 地址（用于识别"发往本机的流量"并归一化为回环地址） */
expect fun localIpAddresses(): Set<String>
