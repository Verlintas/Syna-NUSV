package com.syna.net

import com.syna.core.ConnectionMode
import com.syna.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SynaEngine(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val version: String = "0.1.0",
    private val discoveryIntervalMs: Long = DISCOVERY_INTERVAL_MS,
    private val peerTimeoutMs: Long = PEER_TIMEOUT_MS,
    private val sweepIntervalMs: Long = SWEEP_INTERVAL_MS,
) {
    val userId: String = settings.userId
        .ifEmpty { Uuid.random().toString().also { settings.userId = it } }

    val username: String
        get() = settings.username.ifBlank { defaultUsername() }

    private val device = platformNet().deviceName()

    private val announcementsM = MutableSharedFlow<Pair<DiscoveryAnnouncement, String>>()
    private val incomingM = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 256)

    val announcements: SharedFlow<Pair<DiscoveryAnnouncement, String>> = announcementsM.asSharedFlow()
    val incoming: SharedFlow<IncomingEvent> = incomingM.asSharedFlow()

    private val peersM = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = peersM.asStateFlow()

    private val connectionsM = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val connections: StateFlow<Map<String, Peer>> = connectionsM.asStateFlow()

    private var tcp: ConnectionManager? = null
    private var udp: ConnectionManager? = null
    private var discovery: DiscoveryService? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        platformNet().lockMulticast()

        val tcp = createTcpTransport(userId).also { it.start() }
        val udp = createUdpTransport(userId).also { it.start() }
        val announcement = DiscoveryAnnouncement(
            id = userId,
            username = username,
            device = device,
            tcpPort = tcp.localTcpPort,
            version = version,
        )
        val discovery = createDiscoveryService(announcement, discoveryIntervalMs).also { it.start() }

        this.tcp = tcp
        this.udp = udp
        this.discovery = discovery

        scope.launch {
            discovery.announcements.collect { (ann, ip) ->
                if (ann.id != userId) {
                    updatePeer(ann, ip, System.currentTimeMillis(), online = true)
                }
            }
        }
        scope.launch {
            tcp.incoming.collect { incomingM.emit(it) }
        }
        scope.launch {
            udp.incoming.collect { incomingM.emit(it) }
        }
        scope.launch {
            while (scope.isActive) {
                kotlinx.coroutines.delay(sweepIntervalMs)
                sweep()
            }
        }
    }

    private fun updatePeer(ann: DiscoveryAnnouncement, ip: String, now: Long, online: Boolean) {
        peersM.update { list ->
            val existing = list.firstOrNull { it.id == ann.id }
            val updated = Peer(
                id = ann.id,
                username = ann.username,
                device = ann.device,
                addr = PeerAddr(ip, ann.tcpPort),
                version = ann.version,
                lastSeen = now,
                online = online,
            )
            if (existing == null) {
                list + updated
            } else {
                list.map { if (it.id == ann.id) updated else it }
            }
        }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        peersM.update { list ->
            list.map { peer ->
                if (peer.online && now - peer.lastSeen > peerTimeoutMs) {
                    peer.copy(online = false)
                } else peer
            }
        }
    }

    fun refreshUsername() {
        val ann = DiscoveryAnnouncement(
            id = userId,
            username = username,
            device = device,
            tcpPort = tcp?.localTcpPort ?: 0,
            version = version,
        )
        discovery?.let {
            // restart discovery with the new identity
            it.stop()
            val d = createDiscoveryService(ann).also { d -> d.start() }
            discovery = d
            scope.launch {
                d.announcements.collect { (a, ip) ->
                    if (a.id != userId) updatePeer(a, ip, System.currentTimeMillis(), online = true)
                }
            }
        }
    }

    suspend fun sendText(peerId: String, text: String) {
        val peer = peersM.value.firstOrNull { it.id == peerId } ?: return
        val frame = TransportFrame(
            type = FrameType.TEXT,
            from = userId,
            to = peerId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = text,
        )
        send(peer, frame)
    }

    private suspend fun send(peer: Peer, frame: TransportFrame) {
        when (settings.connectionMode) {
            ConnectionMode.UDP -> udp?.send(peer.addr, frame)
            ConnectionMode.TCP, ConnectionMode.AUTO, ConnectionMode.HOTSPOT -> {
                try {
                    tcp?.send(peer.addr, frame)
                } catch (e: Exception) {
                    udp?.send(peer.addr, frame)
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        platformNet().unlockMulticast()
        discovery?.stop()
        tcp?.stop()
        udp?.stop()
    }

    private fun defaultUsername(): String = "用户-${userId.takeLast(4)}"

    private fun newMsgId(): String = Uuid.random().toString()
}

private fun <T> MutableStateFlow<List<T>>.update(transform: (List<T>) -> List<T>) {
    value = transform(value)
}
