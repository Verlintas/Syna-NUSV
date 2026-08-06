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
import kotlinx.coroutines.delay
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
    private val tempChatTtlMsOverride: Long? = null,
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

    private val groupsM = MutableStateFlow<List<GroupInfo>>(emptyList())
    val groups: StateFlow<List<GroupInfo>> = groupsM.asStateFlow()

    private val pendingBurnsM = MutableStateFlow<List<Triple<String, String, String>>>(emptyList())

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
        scope.launch {
            chatStore.activeConversationId.collect { conversationId ->
                conversationId?.let { trySchedulePendingBurns(it) }
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
                    preview = if (frame.burn) "🔥 阅后即焚消息" else frame.body ?: "",
                )
                if (frame.burn) {
                    pendingBurnsM.updateList { it + Triple(frame.from, frame.msgId, frame.from) }
                    trySchedulePendingBurns(frame.from)
                }
                if (chatStore.activeConversationId.value == frame.from) {
                    chatStore.markAllRead(frame.from)
                    sendReceipt(frame.from, frame.msgId)
                }
            }
            FrameType.GROUP_MESSAGE -> {
                val groupId = frame.to
                val group = groupsM.value.firstOrNull { it.id == groupId }
                if (group != null) {
                    val senderName = group.memberNames[frame.from] ?: peerName
                    chatStore.addIncoming(
                        peerId = groupId,
                        peerName = group.name,
                        msg = ChatMessage(
                            id = frame.msgId,
                            conversationId = groupId,
                            senderId = frame.from,
                            body = frame.body ?: "",
                            ts = frame.ts,
                            status = MessageStatus.READ,
                            burnAfterReading = frame.burn,
                            encrypted = frame.enc,
                        ),
                        preview = if (frame.burn) "🔥 阅后即焚消息" else frame.body ?: "",
                    )
                    if (frame.burn) {
                        pendingBurnsM.updateList { it + Triple(groupId, frame.msgId, frame.from) }
                        trySchedulePendingBurns(groupId)
                    }
                    if (chatStore.activeConversationId.value == groupId) {
                        chatStore.markAllRead(groupId)
                    }
                }
            }
            FrameType.GROUP_INVITE -> {
                val group = try {
                    synaJson.decodeFromString(GroupInfo.serializer(), frame.body ?: return)
                } catch (e: Exception) {
                    return
                }
                addOrMergeGroup(group)
                // 通知其他成员本成员已加入
                val joinEvent = GroupMemberEvent(groupId = group.id, memberId = userId, memberName = username)
                group.memberIds.filter { it != userId && it != frame.from }
                    .forEach { memberId -> sendGroupEvent(memberId, FrameType.GROUP_JOIN, joinEvent) }
            }
            FrameType.GROUP_JOIN -> {
                val event = try {
                    synaJson.decodeFromString(GroupMemberEvent.serializer(), frame.body ?: return)
                } catch (e: Exception) {
                    return
                }
                groupsM.updateList { list ->
                    list.map { group ->
                        if (group.id == event.groupId && event.memberId !in group.memberIds) {
                            group.copy(
                                memberIds = group.memberIds + event.memberId,
                                memberNames = group.memberNames + (event.memberId to event.memberName),
                            )
                        } else group
                    }
                }
            }
            FrameType.GROUP_LEAVE -> {
                val event = try {
                    synaJson.decodeFromString(GroupMemberEvent.serializer(), frame.body ?: return)
                } catch (e: Exception) {
                    return
                }
                groupsM.updateList { list ->
                    list.map { group ->
                        if (group.id == event.groupId) {
                            group.copy(
                                memberIds = group.memberIds.filter { it != event.memberId },
                                memberNames = group.memberNames - event.memberId,
                            )
                        } else group
                    }
                }
            }
            FrameType.READ -> frame.body?.let { msgId -> chatStore.updateStatus(msgId, MessageStatus.READ) }
            FrameType.BURN_ACK -> frame.body?.let { msgId -> chatStore.removeMessageById(msgId) }
            else -> Unit
        }
    }

    private fun trySchedulePendingBurns(conversationId: String) {
        if (chatStore.activeConversationId.value != conversationId) return
        val pending = pendingBurnsM.value.filter { it.first == conversationId }
        if (pending.isEmpty()) return
        pendingBurnsM.updateList { list -> list.filterNot { it.first == conversationId } }
        pending.forEach { (conversation, msgId, senderId) ->
            scheduleBurnPurge(conversation, msgId, senderId, deliverAck = true)
        }
    }

    private fun scheduleBurnPurge(
        conversationId: String,
        msgId: String,
        ackTo: String?,
        deliverAck: Boolean,
        delayMs: Long = BURN_DISPLAY_MS,
    ) {
        scope.launch {
            delay(delayMs)
            chatStore.removeMessage(conversationId, msgId)
            if (deliverAck && ackTo != null) {
                sendBurnAck(ackTo, msgId)
            }
        }
    }

    private suspend fun sendBurnAck(senderId: String, msgId: String) {
        val peer = peersM.value.firstOrNull { it.id == senderId } ?: return
        val frame = TransportFrame(
            type = FrameType.BURN_ACK,
            from = userId,
            to = senderId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = msgId,
        )
        try {
            send(peer, frame)
        } catch (e: Exception) {
        }
    }

    suspend fun createGroup(name: String, memberIds: List<String>): String {
        val members = memberIds.distinct().filter { it != userId }
        val group = GroupInfo(
            id = Uuid.random().toString(),
            name = name.ifBlank { "群聊" },
            creatorId = userId,
            memberIds = listOf(userId) + members,
            memberNames = mapOf(userId to username) + members.associateWith { memberId ->
                peersM.value.firstOrNull { it.id == memberId }?.username ?: memberId
            },
            ts = System.currentTimeMillis(),
        )
        groupsM.updateList { it + group }
        val body = synaJson.encodeToString(GroupInfo.serializer(), group)
        members.forEach { memberId ->
            val peer = peersM.value.firstOrNull { it.id == memberId } ?: return@forEach
            val frame = TransportFrame(
                type = FrameType.GROUP_INVITE,
                from = userId,
                to = group.id,
                msgId = newMsgId(),
                ts = System.currentTimeMillis(),
                body = body,
            )
            send(peer, frame)
        }
        return group.id
    }

    suspend fun leaveGroup(groupId: String) {
        val group = groupsM.value.firstOrNull { it.id == groupId } ?: return
        groupsM.updateList { it.filter { g -> g.id != groupId } }
        val event = GroupMemberEvent(groupId = groupId, memberId = userId, memberName = username)
        group.memberIds.filter { it != userId }.forEach { memberId ->
            sendGroupEvent(memberId, FrameType.GROUP_LEAVE, event)
        }
    }

    suspend fun sendGroupText(groupId: String, text: String, burn: Boolean = false): String {
        val group = groupsM.value.firstOrNull { it.id == groupId } ?: return ""
        val msgId = newMsgId()
        group.memberIds.filter { it != userId }.forEach { memberId ->
            val peer = peersM.value.firstOrNull { it.id == memberId } ?: return@forEach
            val peerKey = peerKeysM.value[memberId]
            val encrypted = settings.e2eEnabled && peerKey != null
            val frame = TransportFrame(
                type = FrameType.GROUP_MESSAGE,
                from = userId,
                to = groupId,
                msgId = msgId,
                ts = System.currentTimeMillis(),
                body = if (encrypted) {
                    SynaCrypto.encrypt(SynaCrypto.deriveSessionKey(identity.privateBytes, peerKey, sessionId(memberId)), text)
                } else text,
                enc = encrypted,
                burn = burn,
            )
            try {
                send(peer, frame)
            } catch (e: Exception) {
            }
        }
        chatStore.addOutgoing(
            peerId = groupId,
            peerName = group.name,
            msg = ChatMessage(
                id = msgId,
                conversationId = groupId,
                senderId = userId,
                body = text,
                ts = System.currentTimeMillis(),
                status = MessageStatus.SENT,
                burnAfterReading = burn,
                encrypted = settings.e2eEnabled,
            ),
        )
        if (burn) {
            scheduleBurnPurge(groupId, msgId, ackTo = null, deliverAck = false, delayMs = BURN_ACK_FALLBACK_MS)
        }
        return msgId
    }

    private suspend fun sendGroupEvent(memberId: String, type: FrameType, event: GroupMemberEvent) {
        val peer = peersM.value.firstOrNull { it.id == memberId } ?: return
        val frame = TransportFrame(
            type = type,
            from = userId,
            to = event.groupId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = synaJson.encodeToString(GroupMemberEvent.serializer(), event),
        )
        try {
            send(peer, frame)
        } catch (e: Exception) {
        }
    }

    private fun addOrMergeGroup(group: GroupInfo) {
        groupsM.updateList { list ->
            val existing = list.firstOrNull { it.id == group.id }
            if (existing == null) {
                list + group
            } else {
                list.map { g ->
                    if (g.id == group.id) {
                        g.copy(
                            memberIds = (g.memberIds + group.memberIds).distinct(),
                            memberNames = g.memberNames + group.memberNames,
                        )
                    } else g
                }
            }
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
        val effectiveTtl = tempChatTtlMsOverride ?: if (settings.tempChatEnabled) {
            settings.tempChatTtlHours * 3600_000L
        } else 0L
        if (effectiveTtl > 0L) {
            chatStore.purgeExpired(effectiveTtl, now)
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
        if (burn) {
            scheduleBurnPurge(peerId, msgId, ackTo = null, deliverAck = false, delayMs = BURN_ACK_FALLBACK_MS)
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
