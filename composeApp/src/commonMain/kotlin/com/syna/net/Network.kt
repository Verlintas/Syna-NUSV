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
