package com.syna.net

import com.syna.chat.ChatMessage
import com.syna.chat.ChatStore
import com.syna.chat.MessageStatus
import com.syna.core.ConnectionMode
import com.syna.crypto.SynaCrypto
import com.syna.crypto.createIdentityStore
import com.syna.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SynaEngine(
    val settings: SettingsRepository,
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

    private val identity = createIdentityStore().loadOrCreate()
    private val publicKeyB64 = SynaCrypto.publicKeyB64(identity)

    private val announcementsM = MutableSharedFlow<Pair<DiscoveryAnnouncement, String>>()
    private val incomingM = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 256)
    private val rawIncomingM = MutableSharedFlow<TransportFrame>(extraBufferCapacity = 256)

    val announcements: SharedFlow<Pair<DiscoveryAnnouncement, String>> = announcementsM.asSharedFlow()
    val incoming: SharedFlow<IncomingEvent> = incomingM.asSharedFlow()
    val rawIncoming: SharedFlow<TransportFrame> = rawIncomingM.asSharedFlow()

    private val peersM = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = peersM.asStateFlow()

    private val peerKeysM = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerKeys: StateFlow<Map<String, String>> = peerKeysM.asStateFlow()

    private val peerKeySentM = MutableStateFlow<Map<String, Long>>(emptyMap())

    val chatStore = ChatStore()

    private var tcp: ConnectionManager? = null
    private var udp: ConnectionManager? = null
    private var discovery: DiscoveryService? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        platformNet().lockMulticast()

        val tcp = createTcpTransport(userId, publicKeyB64).also { it.start() }
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
                    sendKeyFrame(ann.id, PeerAddr(ip, ann.tcpPort))
                }
            }
        }
        scope.launch {
            tcp.incoming.collect { event ->
                rawIncomingM.emitRaw(event)
                incomingM.emit(decryptEvent(event))
            }
        }
        scope.launch {
            udp.incoming.collect { event ->
                rawIncomingM.emitRaw(event)
                incomingM.emit(decryptEvent(event))
            }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(sweepIntervalMs)
                sweep()
            }
        }
        scope.launch {
            incomingM.collect { event ->
                if (event is IncomingEvent.PeerFrame) handleChatFrame(event.frame)
            }
        }
        scope.launch {
            peersM.collect { list ->
                list.forEach { peer -> chatStore.renamePeer(peer.id, peer.username) }
            }
        }
    }

    private suspend fun MutableSharedFlow<TransportFrame>.emitRaw(event: IncomingEvent) {
        if (event is IncomingEvent.PeerFrame) emit(event.frame)
    }

    private suspend fun decryptEvent(event: IncomingEvent): IncomingEvent {
        if (event !is IncomingEvent.PeerFrame) return event
        val frame = event.frame
        when (frame.type) {
            FrameType.HELLO -> frame.body?.let { key ->
                peerKeysM.updateMap { it + (frame.from to key) }
            }
            FrameType.KEY -> {
                frame.body?.let { key ->
                    peerKeysM.updateMap { it + (frame.from to key) }
                }
                // 收到公钥后回发自己的公钥，节流避免回复风暴
                val peer = peersM.value.firstOrNull { p -> p.id == frame.from } ?: return event
                val now = System.currentTimeMillis()
                val lastSent = peerKeySentM.value[frame.from] ?: 0L
                if (now - lastSent > 30_000L) {
                    peerKeySentM.updateMap { it + (frame.from to now) }
                    sendKeyFrame(frame.from, peer.addr)
                }
            }
            else -> Unit
        }
        if (frame.enc && frame.type != FrameType.KEY && frame.type != FrameType.HELLO) {
            val peerKey = peerKeysM.value[frame.from]
            if (peerKey != null) {
                return try {
                    val session = SynaCrypto.deriveSessionKey(identity.privateBytes, peerKey, sessionId(frame.from))
                    val plain = SynaCrypto.decrypt(session, frame.body ?: "")
                    event.copyWith(frame.copy(body = plain))
                } catch (e: Exception) {
                    event
                }
            }
        }
        return event
    }

    private suspend fun handleChatFrame(frame: TransportFrame) {
        if (frame.from == userId) return
        val peerName = peersM.value.firstOrNull { it.id == frame.from }?.username ?: frame.from
        when (frame.type) {
            FrameType.TEXT -> {
                chatStore.addIncoming(
                    peerId = frame.from,
                    peerName = peerName,
                    msg = ChatMessage(
                        id = frame.msgId,
                        conversationId = frame.from,
                        senderId = frame.from,
                        body = frame.body ?: "",
                        ts = frame.ts,
                        status = MessageStatus.READ,
                        burnAfterReading = frame.burn,
                        encrypted = frame.enc,
                    ),
                )
                if (chatStore.activeConversationId == frame.from) {
                    chatStore.markAllRead(frame.from)
                    sendReceipt(frame.from, frame.msgId)
                }
            }
            FrameType.READ -> frame.body?.let { msgId -> chatStore.updateStatus(msgId, MessageStatus.READ) }
            else -> Unit
        }
    }

    private suspend fun sendReceipt(peerId: String, msgId: String) {
        val peer = peersM.value.firstOrNull { it.id == peerId } ?: return
        val frame = TransportFrame(
            type = FrameType.READ,
            from = userId,
            to = peerId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = msgId,
        )
        send(peer, frame)
    }

    private fun updatePeer(ann: DiscoveryAnnouncement, ip: String, now: Long, online: Boolean) {
        peersM.updateList { list ->
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
        peersM.updateList { list ->
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
            it.stop()
            val d = createDiscoveryService(ann, discoveryIntervalMs).also { d -> d.start() }
            discovery = d
            scope.launch {
                d.announcements.collect { (a, ip) ->
                    if (a.id != userId) {
                        updatePeer(a, ip, System.currentTimeMillis(), online = true)
                        sendKeyFrame(a.id, PeerAddr(ip, a.tcpPort))
                    }
                }
            }
        }
    }

    suspend fun sendText(peerId: String, text: String, burn: Boolean = false): String {
        val peer = peersM.value.firstOrNull { it.id == peerId } ?: return ""
        val peerKey = peerKeysM.value[peerId]
        val encrypted = settings.e2eEnabled && peerKey != null
        val msgId = newMsgId()
        val frame = TransportFrame(
            type = FrameType.TEXT,
            from = userId,
            to = peerId,
            msgId = msgId,
            ts = System.currentTimeMillis(),
            body = if (encrypted) {
                SynaCrypto.encrypt(SynaCrypto.deriveSessionKey(identity.privateBytes, peerKey, sessionId(peerId)), text)
            } else text,
            enc = encrypted,
            burn = burn,
        )
        val peerName = peersM.value.firstOrNull { it.id == peerId }?.username ?: peerId
        chatStore.addOutgoing(
            peerId = peerId,
            peerName = peerName,
            msg = ChatMessage(
                id = msgId,
                conversationId = peerId,
                senderId = userId,
                body = text,
                ts = frame.ts,
                status = MessageStatus.SENDING,
                burnAfterReading = burn,
                encrypted = encrypted,
            ),
        )
        try {
            send(peer, frame)
            chatStore.updateStatus(msgId, MessageStatus.SENT)
        } catch (e: Exception) {
            chatStore.updateStatus(msgId, MessageStatus.FAILED)
        }
        return msgId
    }

    private suspend fun sendKeyFrame(peerId: String, addr: PeerAddr) {
        val frame = TransportFrame(
            type = FrameType.KEY,
            from = userId,
            to = peerId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = publicKeyB64,
        )
        send(Peer(id = peerId, username = "", device = "", addr = addr, version = "", lastSeen = 0, online = true), frame)
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

    private fun sessionId(peerId: String): String =
        listOf(userId, peerId).sorted().joinToString("|")

    private fun newMsgId(): String = Uuid.random().toString()
}

private fun IncomingEvent.PeerFrame.copyWith(frame: TransportFrame): IncomingEvent =
    IncomingEvent.PeerFrame(this.peerId, frame)

private fun <T> MutableStateFlow<List<T>>.updateList(transform: (List<T>) -> List<T>) {
    value = transform(value)
}

private fun <K, V> MutableStateFlow<Map<K, V>>.updateMap(transform: (Map<K, V>) -> Map<K, V>) {
    value = transform(value)
}
