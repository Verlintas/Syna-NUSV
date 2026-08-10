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

import com.syna.chat.ChatMessage
import com.syna.chat.ChatStore
import com.syna.chat.MessageKind
import com.syna.chat.MessageStatus
import com.syna.core.ConnectionMode
import com.syna.crypto.SynaCrypto
import com.syna.crypto.createIdentityStore
import com.syna.storage.SettingsRepository
import com.syna.util.notifyMessage
import com.syna.util.saveReceivedFile
import com.syna.util.synaLog
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
    private val version: String = "0.7.5",
    private val discoveryIntervalMs: Long = DISCOVERY_INTERVAL_MS,
    private val peerTimeoutMs: Long = PEER_TIMEOUT_MS,
    private val sweepIntervalMs: Long = SWEEP_INTERVAL_MS,
    private val tempChatTtlMsOverride: Long? = null,
    /** 聊天记录持久化（默认启用；测试可传 null 关闭落盘） */
    chatPersistence: com.syna.storage.ChatPersistence? = com.syna.storage.ChatPersistence(),
) {
    val userId: String = settings.userId
        .ifEmpty { Uuid.random().toString().also { settings.userId = it } }

    val username: String
        get() = settings.username.ifBlank { defaultUsername() }

    private val device = platformNet().deviceName()

    private val localIps = localIpAddresses()

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

    private val outboxM = MutableStateFlow<Map<String, List<TransportFrame>>>(emptyMap())
    val outbox: StateFlow<Map<String, List<TransportFrame>>> = outboxM.asStateFlow()

    val chatStore = ChatStore(persistence = chatPersistence)

    private val serverStateM = MutableStateFlow(ServerState.DISCONNECTED)
    val serverState: StateFlow<ServerState> = serverStateM.asStateFlow()

    private val serverErrorM = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = serverErrorM.asStateFlow()

    private val serverAnnouncementM = MutableStateFlow<Announcement?>(null)
    val serverAnnouncement: StateFlow<Announcement?> = serverAnnouncementM.asStateFlow()

    private val blockedM = MutableStateFlow(settings.blockedPeerIds)
    val blockedContacts: StateFlow<List<String>> = blockedM.asStateFlow()

    private val typingM = MutableStateFlow<Map<String, Pair<Long, String>>>(emptyMap())
    val typing: StateFlow<Map<String, Pair<Long, String>>> = typingM.asStateFlow()

    private val typingSentM = MutableStateFlow<Map<String, Long>>(emptyMap())

    private class FileAssembler(
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val totalChunks: Int,
    ) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var received = 0
        var receivedBytes = 0
        var lastUpdate: Long = System.currentTimeMillis()
    }

    private val fileAssemblers = mutableMapOf<String, FileAssembler>()

    private var serverSession: ServerSession? = null

    val serverGroupId: String?
        get() = serverSession?.groupId

    fun isServerGroup(groupId: String): Boolean = serverSession?.groupId == groupId

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
            udpPort = udp.localUdpPort,
        )
        val discovery = createDiscoveryService(announcement, discoveryIntervalMs).also { it.start() }

        this.tcp = tcp
        this.udp = udp
        this.discovery = discovery

        synaLog("Engine") {
            "started userId=$userId username=$username device=$device tcpPort=${tcp.localTcpPort} e2e=${settings.e2eEnabled}"
        }

        scope.launch {
            discovery.announcements.collect { (ann, ip) ->
                if (ann.id != userId && !isBlocked(ann.id)) {
                    updatePeer(ann, ip, System.currentTimeMillis(), online = true)
                    sendKeyFrame(ann.id, PeerAddr(normalizePeerIp(ip), ann.tcpPort))
                    flushOutbox(peerFrom(ann, ip))
                }
            }
        }
        scope.launch {
            tcp.incoming.collect { event ->
                rawIncomingM.emitRaw(event)
                decryptEvent(event)?.let { incomingM.emit(it) }
            }
        }
        scope.launch {
            udp.incoming.collect { event ->
                rawIncomingM.emitRaw(event)
                decryptEvent(event)?.let { incomingM.emit(it) }
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
                try {
                    if (event is IncomingEvent.PeerFrame) handleChatFrame(event.frame)
                } catch (e: Throwable) {
                    // 单帧异常隔离：恶意/损坏帧不得杀死整条收信管线
                    synaLog("Frame") { "帧处理异常: ${e.message}" }
                }
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

    private suspend fun decryptEvent(event: IncomingEvent): IncomingEvent? {
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
                val now = System.currentTimeMillis()
                val lastSent = peerKeySentM.value[frame.from] ?: 0L
                if (now - lastSent > 30_000L) {
                    peerKeySentM.updateMap { it + (frame.from to now) }
                    if (serverSession != null) {
                        sendServerKeyFrame()
                    } else {
                        val peer = peersM.value.firstOrNull { p -> p.id == frame.from } ?: return event
                        sendKeyFrame(frame.from, peer.addr)
                    }
                }
            }
            else -> Unit
        }
        if (frame.enc && frame.type != FrameType.KEY && frame.type != FrameType.HELLO) {
            // 优先 E2E（成员间会话密钥）解密；失败则尝试群密钥（兼容旧客户端/旧历史）
            val peerKey = peerKeysM.value[frame.from]
            if (peerKey != null) {
                try {
                    val session = SynaCrypto.deriveSessionKey(identity.privateBytes, peerKey, sessionId(frame.from))
                    val plain = SynaCrypto.decrypt(session, frame.body ?: "")
                    return event.copyWith(frame.copy(body = plain))
                } catch (e: Exception) {
                    // E2E 失败，可能是群密钥加密的旧消息
                }
            }
            val serverGroup = serverSession?.takeIf { it.groupId == frame.to }
            if (serverGroup != null) {
                return try {
                    val plain = SynaCrypto.decrypt(serverGroup.groupKey, frame.body ?: "")
                    event.copyWith(frame.copy(body = plain))
                } catch (e: Exception) {
                    synaLog("Crypto") { "group decrypt FAILED from=${frame.from.take(6)}: ${e.message}" }
                    // UDP 通道可能丢失过对方的公钥帧：主动请求重发，保证自愈
                    sendKeyRequest(frame.from)
                    // 解密失败：丢弃密文（不入库），防止后续真实明文副本被去重跳过
                    return null
                }
            }
            if (peerKey != null) {
                synaLog("Crypto") { "decrypt FAILED from=${frame.from.take(6)} type=${frame.type}" }
                sendKeyRequest(frame.from)
                return null
            }
            // 没有对方的公钥（UDP 下 KEY 帧可能丢失）：请求重发
            sendKeyRequest(frame.from)
        }
        return event
    }

    private suspend fun sendKeyRequest(peerId: String) {
        if (serverSession != null) {
            sendServerKeyFrame()
            return
        }
        val peer = peersM.value.firstOrNull { it.id == peerId } ?: return
        val frame = TransportFrame(
            type = FrameType.REQ_KEY,
            from = userId,
            to = peerId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
        )
        try {
            send(peer, frame)
        } catch (e: Exception) {
        }
    }

    private suspend fun handleChatFrame(frame: TransportFrame) {
        if (frame.from == userId) return
        // 同一条消息的多份密文副本（服务器 E2E 广播）只处理一次
        if (frame.msgId.isNotEmpty() && chatStore.messageById(frame.msgId) != null) return
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
                        replyToId = frame.replyTo,
                        mentions = frame.mentions,
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
                } else {
                    notifyMessageSafe(
                        peerName,
                        if (frame.burn) "[阅后即焚消息]" else (frame.body ?: "").take(80),
                    )
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
                        replyToId = frame.replyTo,
                        mentions = frame.mentions,
                    ),
                    preview = if (frame.burn) "🔥 阅后即焚消息" else frame.body ?: "",
                    )
                    if (frame.burn) {
                        pendingBurnsM.updateList { it + Triple(groupId, frame.msgId, frame.from) }
                        trySchedulePendingBurns(groupId)
                    }
                    if (chatStore.activeConversationId.value == groupId) {
                        chatStore.markAllRead(groupId)
                    } else {
                        notifyMessageSafe(
                            "${group.name} · $senderName",
                            if (frame.burn) "[阅后即焚消息]" else (frame.body ?: "").take(80),
                        )
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
            FrameType.GROUP_DISSOLVE -> {
                val event = try {
                    synaJson.decodeFromString(GroupMemberEvent.serializer(), frame.body ?: return)
                } catch (e: Exception) {
                    return
                }
                removeGroupLocally(event.groupId)
                synaLog("Group") { "群已解散: ${event.groupId.take(8)}" }
            }
            FrameType.TYPING -> {
                val conversationId = if (groupsM.value.any { it.id == frame.to }) frame.to else frame.from
                typingM.updateMap { it + (conversationId to (System.currentTimeMillis() to frame.from)) }
            }
            FrameType.RECALL -> frame.body?.let { msgId ->
                // 防伪造：仅消息发送者本人可撤回（群聊防他人撤回）
                val msg = chatStore.messageById(msgId)
                if (msg != null && msg.senderId == frame.from) {
                    chatStore.markRecalledByMsgId(msgId)
                }
            }
            FrameType.REQ_KEY -> {
                // 对方请求我们的公钥：必须无节流直发（sendKeyFrame 有 30s 节流，
                // 若被拦截则丢失 KEY 的一方永远无法自愈）
                if (serverSession != null) {
                    sendServerKeyFrame()
                } else {
                    val peer = peersM.value.firstOrNull { it.id == frame.from } ?: return
                    sendKeyFrameNow(frame.from, peer.addr)
                }
            }
            FrameType.FILE_CHUNK -> handleFileChunk(frame)
            FrameType.ANNOUNCEMENT -> {
                val ann = try {
                    synaJson.decodeFromString(Announcement.serializer(), frame.body ?: return)
                } catch (e: Exception) {
                    return
                }
                serverAnnouncementM.value = ann
                synaLog("Server") { "收到群公告: ${ann.text.take(30)}" }
            }
            FrameType.GROUP_KICK -> {
                val event = try {
                    synaJson.decodeFromString(GroupMemberEvent.serializer(), frame.body ?: return)
                } catch (e: Exception) {
                    return
                }
                if (event.memberId == userId) {
                    synaLog("Server") { "已被服务器踢出" }
                    removeGroupLocally(event.groupId)
                    serverSession = null
                    serverStateM.value = ServerState.DISCONNECTED
                    notifyMessage("Syna", "你已被服务器移出群聊")
                } else {
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
            }
            FrameType.READ -> frame.body?.let { msgId -> chatStore.updateStatus(msgId, MessageStatus.READ) }
            FrameType.BURN_ACK -> frame.body?.let { msgId ->
                // 防伪造：仅"我发出的"且"来自对话对端"的确认才清除焚毁副本
                // （攻击者嗅探 msgId 后伪造 ACK 无法删除他人消息）
                val msg = chatStore.messageById(msgId)
                if (msg != null && msg.senderId == userId && frame.from == msg.conversationId) {
                    chatStore.removeMessageById(msgId)
                }
            }
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
        val frame = TransportFrame(
            type = FrameType.BURN_ACK,
            from = userId,
            to = senderId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = msgId,
        )
        try {
            if (serverSession != null) {
                serverSession?.channel?.send(frame)
            } else {
                val peer = peersM.value.firstOrNull { it.id == senderId } ?: return
                send(peer, frame)
            }
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
        chatStore.removeConversation(groupId)
        if (chatStore.activeConversationId.value == groupId) {
            chatStore.activeConversationId.value = null
        }
    }

    /** 群主解散群聊：通知所有成员并本地清除 */
    suspend fun dissolveGroup(groupId: String) {
        val group = groupsM.value.firstOrNull { it.id == groupId } ?: return
        if (group.creatorId != userId) {
            synaLog("Group") { "只有群主可以解散群聊" }
            return
        }
        val event = GroupMemberEvent(groupId = groupId, memberId = userId, memberName = username)
        val frame = TransportFrame(
            type = FrameType.GROUP_DISSOLVE,
            from = userId,
            to = groupId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = synaJson.encodeToString(GroupMemberEvent.serializer(), event),
        )
        group.memberIds.filter { it != userId }.forEach { memberId ->
            try {
                val peer = peersM.value.firstOrNull { it.id == memberId } ?: return@forEach
                send(peer, frame)
            } catch (e: Exception) {
            }
        }
        removeGroupLocally(groupId)
        synaLog("Group") { "已解散群聊: ${group.name}" }
    }

    private fun removeGroupLocally(groupId: String) {
        groupsM.updateList { list -> list.filterNot { it.id == groupId } }
        chatStore.removeConversation(groupId)
        if (chatStore.activeConversationId.value == groupId) {
            chatStore.activeConversationId.value = null
        }
    }

    /** 撤回消息：仅本人 2 分钟内的消息，广播 RECALL 并本地标记 */
    suspend fun recallMessage(conversationId: String, msgId: String) {
        val msg = chatStore.messageById(msgId) ?: return
        if (msg.senderId != userId) {
            synaLog("Recall") { "只能撤回自己的消息" }
            return
        }
        if (msg.recalled) return
        if (System.currentTimeMillis() - msg.ts > 2 * 60_000L) {
            synaLog("Recall") { "超过 2 分钟无法撤回" }
            return
        }
        val frame = TransportFrame(
            type = FrameType.RECALL,
            from = userId,
            to = conversationId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = msgId,
        )
        chatStore.markRecalledByMsgId(msgId)
        val serverSession = serverSession
        try {
            when {
                serverSession != null && serverSession.groupId == conversationId -> serverSession.channel.send(frame)
                groupsM.value.any { it.id == conversationId } -> {
                    val group = groupsM.value.first { it.id == conversationId }
                    group.memberIds.filter { it != userId }.forEach { memberId ->
                        val p = peersM.value.firstOrNull { it.id == memberId } ?: return@forEach
                        try {
                            send(p, frame)
                        } catch (e: Exception) {
                        }
                    }
                }
                else -> {
                    val peer = peersM.value.firstOrNull { it.id == conversationId }
                    if (peer != null) send(peer, frame)
                }
            }
            synaLog("Recall") { "已撤回 $msgId" }
        } catch (e: Exception) {
            synaLog("Recall") { "撤回失败: ${e.message}" }
        }
    }

    /** 删除联系人：屏蔽其发现广播并清除会话 */
    fun removeContact(peerId: String) {
        settings.blockedPeerIds = settings.blockedPeerIds + peerId
        blockedM.updateList { it + peerId }
        peersM.updateList { list -> list.filterNot { it.id == peerId } }
        chatStore.removeConversation(peerId)
        if (chatStore.activeConversationId.value == peerId) {
            chatStore.activeConversationId.value = null
        }
        synaLog("Contact") { "已删除联系人 ${peerId.take(8)}" }
    }

    fun unblockContact(peerId: String) {
        settings.blockedPeerIds = settings.blockedPeerIds.filterNot { it == peerId }
        blockedM.updateList { it.filterNot { id -> id == peerId } }
        synaLog("Contact") { "已解除屏蔽 ${peerId.take(8)}" }
    }

    fun isBlocked(peerId: String): Boolean = peerId in blockedM.value

    /** 手动刷新：立即广播自身存在并重算在线状态（排障用） */
    fun refreshContacts() {
        discovery?.sendNow()
        sweep()
        synaLog("Discovery") { "手动刷新完成（已广播存在并重算在线状态）" }
    }

    suspend fun sendGroupText(
        groupId: String,
        text: String,
        burn: Boolean = false,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
    ): String {
        val group = groupsM.value.firstOrNull { it.id == groupId } ?: return ""
        val msgId = newMsgId()
        val serverSession = serverSession
        if (serverSession != null && serverSession.groupId == groupId) {
            // 服务器群 E2E：对每个成员用其 X25519 会话密钥加密一份（msgId 相同）。
            // 服务器广播全部密文副本（各自只能解自己的）并持久化一份——
            // 服务器管理员与公网中转方无法解密任何消息内容。
            val ciphers = group.memberIds.filter { it != userId }.mapNotNull { memberId ->
                val peerKey = peerKeysM.value[memberId]
                if (peerKey != null) {
                    SynaCrypto.encrypt(
                        SynaCrypto.deriveSessionKey(identity.privateBytes, peerKey, sessionId(memberId)),
                        text,
                    )
                } else null
            }
            val frames: List<TransportFrame>
            if (ciphers.isNotEmpty()) {
                frames = ciphers.map { cipher ->
                    TransportFrame(
                        type = FrameType.GROUP_MESSAGE,
                        from = userId,
                        to = groupId,
                        msgId = msgId,
                        ts = System.currentTimeMillis(),
                        body = cipher,
                        enc = true,
                        burn = burn,
                        replyTo = replyTo,
                        mentions = mentions,
                    )
                }
            } else {
                // 尚无成员密钥（首条消息前）：明文发送一份
                frames = listOf(
                    TransportFrame(
                        type = FrameType.GROUP_MESSAGE,
                        from = userId,
                        to = groupId,
                        msgId = msgId,
                        ts = System.currentTimeMillis(),
                        body = text,
                        enc = false,
                        burn = burn,
                        replyTo = replyTo,
                        mentions = mentions,
                    ),
                )
            }
            frames.forEach { frame ->
                try {
                    serverSession.channel.send(frame)
                } catch (e: Exception) {
                    synaLog("Server") { "group send failed: ${e.message}" }
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
                    replyToId = replyTo,
                    mentions = mentions,
                ),
            )
            if (burn) {
                scheduleBurnPurge(groupId, msgId, ackTo = null, deliverAck = false, delayMs = BURN_ACK_FALLBACK_MS)
            }
            return msgId
        }

        // 局域网 P2P 群：按成员直连加密广播
        group.memberIds.filter { it != userId }.forEach { memberId ->
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
                replyTo = replyTo,
                mentions = mentions,
            )
            try {
                val peer = peersM.value.firstOrNull { it.id == memberId } ?: return@forEach
                send(peer, frame)
            } catch (e: Exception) {
                synaLog("Server") { "group send failed to $memberId: ${e.message}" }
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
                replyToId = replyTo,
                mentions = mentions,
            ),
        )
        if (burn) {
            scheduleBurnPurge(groupId, msgId, ackTo = null, deliverAck = false, delayMs = BURN_ACK_FALLBACK_MS)
        }
        return msgId
    }

    fun dismissAnnouncement() {
        serverAnnouncementM.value = null
    }

    /** 转发消息到目标会话（1:1 或群聊；文本内容转发） */
    suspend fun forwardMessage(targetConversationId: String, message: ChatMessage) {
        if (message.recalled) return
        val text = when (message.kind) {
            MessageKind.TEXT -> message.body
            MessageKind.IMAGE -> "🖼 [图片] ${message.fileName ?: ""}".trim()
            MessageKind.FILE -> "📄 [文件] ${message.fileName ?: ""}".trim()
        }
        if (text.isBlank()) return
        if (groupsM.value.any { it.id == targetConversationId }) {
            sendGroupText(targetConversationId, text)
        } else {
            sendText(targetConversationId, text)
        }
        synaLog("Forward") { "转发 ${message.id.take(8)} -> $targetConversationId" }
    }

    /** 通过 IP:端口 + 密码加入私人服务器群聊 */
    suspend fun joinServer(host: String, port: Int, password: String): Result<String> {
        if (serverStateM.value == ServerState.CONNECTING) return Result.failure(IllegalStateException("正在连接中"))
        leaveServer()
        serverStateM.value = ServerState.CONNECTING
        serverErrorM.value = null
        synaLog("Server") { "joining $host:$port" }
        return try {
            val result = createServerChannel().connect(
                host = host,
                port = port,
                password = password,
                userId = userId,
                username = username,
                publicKeyB64 = publicKeyB64,
                scope = scope,
            )
            // 注册服务器群
            val group = GroupInfo(
                id = result.groupId,
                name = result.groupName,
                creatorId = result.serverId,
                memberIds = listOf(userId) + result.members.filter { it.id != userId }.map { it.id },
                memberNames = mapOf(userId to username) + result.members.associate { it.id to it.name },
                ts = System.currentTimeMillis(),
            )
            addOrMergeGroup(group)
            result.members.filter { it.id != userId && it.publicKeyB64 != null }.forEach { member ->
                peerKeysM.updateMap { it + (member.id to member.publicKeyB64!!) }
            }

            val groupKey = SynaCrypto.deriveFromPassword(password, result.groupId, SERVER_GROUP_INFO)
            serverSession = ServerSession(result.groupId, result.serverId, host, port, result.channel, groupKey)

            // 把自己的公钥发给服务器（中继给全体成员）
            sendServerKeyFrame()
            // 回放历史（成员密钥帧与群消息，逐条进入解密/聊天管线）
            result.history.forEach { frame -> routeServerFrame(frame) }
            // 启动持续读取
            scope.launch {
                result.channel.incoming.collect { frame -> routeServerFrame(frame) }
            }
            // 断线检测
            scope.launch {
                while (result.channel.isOpen()) {
                    delay(2_000)
                }
                if (serverSession?.channel === result.channel) {
                    serverSession = null
                    serverStateM.value = ServerState.DISCONNECTED
                    synaLog("Server") { "连接断开 $host:$port" }
                }
            }

            serverStateM.value = ServerState.CONNECTED
            synaLog("Server") { "joined $host:$port group=${result.groupName}" }
            Result.success(result.groupId)
        } catch (e: Exception) {
            serverSession = null
            serverStateM.value = ServerState.ERROR
            serverErrorM.value = e.message ?: "连接失败"
            synaLog("Server") { "join FAILED: ${e.message}" }
            Result.failure(e)
        }
    }

    fun leaveServer() {
        val session = serverSession ?: return
        scope.launch {
            try {
                session.channel.send(
                    TransportFrame(
                        type = FrameType.SRV_LEAVE,
                        from = userId,
                        to = session.groupId,
                        msgId = newMsgId(),
                        ts = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
            }
            session.channel.close()
        }
        serverSession = null
        serverStateM.value = ServerState.DISCONNECTED
    }

    private suspend fun routeServerFrame(frame: TransportFrame) {
        if (frame.from == userId) return
        rawIncomingM.emitRaw(IncomingEvent.PeerFrame(frame.from, frame))
        decryptEvent(IncomingEvent.PeerFrame(frame.from, frame))?.let { incomingM.emit(it) }
    }

    private suspend fun sendServerKeyFrame() {
        val session = serverSession ?: return
        val frame = TransportFrame(
            type = FrameType.KEY,
            from = userId,
            to = session.groupId,
            msgId = newMsgId(),
            ts = System.currentTimeMillis(),
            body = publicKeyB64,
        )
        try {
            session.channel.send(frame)
        } catch (e: Exception) {
        }
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

    /** 发送文件/图片：64KB 分块，支持 1:1 / 局域网群 / 服务器群；单文件上限 200MB 防内存溢出 */
    suspend fun sendFile(conversationId: String, fileName: String, bytes: ByteArray, mimeType: String = "application/octet-stream") {
        if (bytes.size > MAX_FILE_SIZE_BYTES) {
            synaLog("File") { "拒绝发送超大文件 ${fileName} (${bytes.size}B > 200MB)" }
            return
        }
        val chunkSize = 64 * 1024
        val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        val fileId = newMsgId()
        val kind = if (mimeType.startsWith("image/")) MessageKind.IMAGE else MessageKind.FILE

        val group = groupsM.value.firstOrNull { it.id == conversationId }
        val conversationName = group?.name ?: peersM.value.firstOrNull { it.id == conversationId }?.username ?: conversationId
        chatStore.addOutgoing(
            peerId = conversationId,
            peerName = conversationName,
            msg = ChatMessage(
                id = fileId,
                conversationId = conversationId,
                senderId = userId,
                body = fileName,
                ts = System.currentTimeMillis(),
                status = MessageStatus.SENDING,
                burnAfterReading = false,
                encrypted = false,
                kind = kind,
                fileName = fileName,
                fileSize = bytes.size.toLong(),
                progress = 0,
            ),
        )

        var failed = false
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf((i + 1) * chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(start, end)
            val fileChunk = FileChunk(
                fileId = fileId,
                fileName = fileName,
                fileSize = bytes.size.toLong(),
                mimeType = mimeType,
                totalChunks = totalChunks,
                index = i,
                dataB64 = @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                kotlin.io.encoding.Base64.Default.encode(chunk),
            )
            val frame = TransportFrame(
                type = FrameType.FILE_CHUNK,
                from = userId,
                to = conversationId,
                msgId = fileId,
                ts = System.currentTimeMillis(),
                body = synaJson.encodeToString(FileChunk.serializer(), fileChunk),
            )
            try {
                sendToConversation(conversationId, frame)
                chatStore.updateProgress(fileId, ((i + 1) * 100) / totalChunks)
            } catch (e: Exception) {
                failed = true
                synaLog("File") { "发送失败 chunk=$i: ${e.message}" }
                break
            }
        }
        chatStore.updateStatus(fileId, if (failed) MessageStatus.FAILED else MessageStatus.SENT)
        synaLog("File") { "send $fileName (${bytes.size}B, $totalChunks chunks) ${if (failed) "FAILED" else "ok"}" }
    }

    /** 按会话类型路由单帧：服务器群 → 通道；局域网群 → 逐成员；1:1 → 对端 */
    private suspend fun sendToConversation(conversationId: String, frame: TransportFrame) {
        val serverSession = serverSession
        if (serverSession != null && serverSession.groupId == conversationId) {
            serverSession.channel.send(frame)
            return
        }
        val group = groupsM.value.firstOrNull { it.id == conversationId }
        if (group != null) {
            group.memberIds.filter { it != userId }.forEach { memberId ->
                val peer = peersM.value.firstOrNull { it.id == memberId } ?: return@forEach
                send(peer, frame)
            }
            return
        }
        val peer = peersM.value.firstOrNull { it.id == conversationId } ?: return
        send(peer, frame)
    }

    private suspend fun handleFileChunk(frame: TransportFrame) {
        val fc = try {
            synaJson.decodeFromString(FileChunk.serializer(), frame.body ?: return)
        } catch (e: Exception) {
            return
        }
        // 恶意/损坏元数据防护：尺寸上限、分片索引/总数范围校验（防 OOM/越界）
        if (fc.fileSize <= 0 || fc.fileSize > MAX_FILE_SIZE_BYTES) return
        if (fc.totalChunks <= 0 || fc.totalChunks > MAX_FILE_CHUNKS) return
        if (fc.index < 0 || fc.index >= fc.totalChunks) return
        val conversationId = if (groupsM.value.any { it.id == frame.to }) frame.to else frame.from
        val assembler = fileAssemblers.getOrPut(fc.fileId) {
            FileAssembler(fc.fileName, fc.fileSize, fc.mimeType, fc.totalChunks)
        }
        if (assembler.chunks[fc.index] == null) {
            val chunkBytes = try {
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                kotlin.io.encoding.Base64.Default.decode(fc.dataB64)
            } catch (e: Exception) {
                return
            }
            // 累计长度不能超过声明的 fileSize
            if (assembler.receivedBytes + chunkBytes.size > fc.fileSize) return
            assembler.chunks[fc.index] = chunkBytes
            assembler.received++
            assembler.receivedBytes += chunkBytes.size
            assembler.lastUpdate = System.currentTimeMillis()
        }
        if (assembler.received >= assembler.totalChunks) {
            fileAssemblers.remove(fc.fileId)
            val full = ByteArray(assembler.receivedBytes)
            var offset = 0
            for (i in 0 until assembler.totalChunks) {
                val c = assembler.chunks[i] ?: continue
                c.copyInto(full, offset)
                offset += c.size
            }
            val path = try {
                saveReceivedFile(assembler.fileName, full)
            } catch (e: Exception) {
                synaLog("File") { "保存失败: ${e.message}" }
                return
            }
            val kind = if (assembler.mimeType.startsWith("image/")) MessageKind.IMAGE else MessageKind.FILE
            val group = groupsM.value.firstOrNull { it.id == conversationId }
            chatStore.addIncoming(
                peerId = conversationId,
                peerName = group?.name ?: peersM.value.firstOrNull { it.id == frame.from }?.username ?: frame.from,
                msg = ChatMessage(
                    id = fc.fileId,
                    conversationId = conversationId,
                    senderId = frame.from,
                    body = assembler.fileName,
                    ts = frame.ts,
                    status = MessageStatus.READ,
                    burnAfterReading = false,
                    encrypted = false,
                    kind = kind,
                    fileName = assembler.fileName,
                    fileSize = assembler.fileSize,
                    localPath = path,
                ),
                preview = if (kind == MessageKind.IMAGE) "🖼 ${assembler.fileName}" else "📄 ${assembler.fileName}",
            )
            synaLog("File") { "received ${assembler.fileName} (${assembler.fileSize}B) -> $path" }
        }
    }

    /** 通知发送：Shield 锁定期间不泄露消息内容 */
    private fun notifyMessageSafe(title: String, body: String) {
        if (com.syna.shield.ShieldController.isLocked) {
            notifyMessage("🔒 Syna 已锁定", "收到新消息（Shield 锁定期间不显示内容）")
        } else {
            notifyMessage(title, body)
        }
    }

    private suspend fun sendReceipt(peerId: String, msgId: String) {        val peer = peersM.value.firstOrNull { it.id == peerId } ?: return
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
            val wasOnline = existing?.online == true
            if (wasOnline != online) {
                synaLog("Discovery") { "${ann.username}(${ann.id.take(6)}) $ip:${ann.tcpPort} ${if (online) "上线" else "离线"}" }
            }
            val updated = Peer(
                id = ann.id,
                username = ann.username,
                device = ann.device,
                addr = PeerAddr(normalizePeerIp(ip), ann.tcpPort, ann.udpPort),
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
        // 清理超过 10 分钟未完成的文件重组（对方断开等场景，防内存泄漏）
        fileAssemblers.entries.removeIf { (_, a) -> now - a.lastUpdate > 10 * 60_000L }
        // 清理超时的"正在输入"状态（3 秒无更新视为停止输入）
        typingM.updateMap { map ->
            map.filterValues { (ts, _) -> now - ts < 3_000L }
        }
    }

    /** 发送"正在输入"信号（2 秒节流），支持单聊/群聊/服务器群 */
    fun sendTyping(conversationId: String) {
        val now = System.currentTimeMillis()
        val lastSent = typingSentM.value[conversationId] ?: 0L
        if (now - lastSent < 2_000L) return
        typingSentM.updateMap { it + (conversationId to now) }
        val frame = TransportFrame(
            type = FrameType.TYPING,
            from = userId,
            to = conversationId,
            msgId = newMsgId(),
            ts = now,
        )
        val serverSession = serverSession
        if (serverSession != null && serverSession.groupId == conversationId) {
            scope.launch {
                try {
                    serverSession.channel.send(frame)
                } catch (e: Exception) {
                }
            }
            return
        }
        val peer = peersM.value.firstOrNull { it.id == conversationId }
        if (peer != null) {
            scope.launch {
                try {
                    send(peer, frame)
                } catch (e: Exception) {
                }
            }
            return
        }
        // 局域网群：发给每个在线成员
        val group = groupsM.value.firstOrNull { it.id == conversationId } ?: return
        scope.launch {
            group.memberIds.filter { it != userId }.forEach { memberId ->
                val p = peersM.value.firstOrNull { it.id == memberId } ?: return@forEach
                try {
                    send(p, frame)
                } catch (e: Exception) {
                }
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
            udpPort = udp?.localUdpPort ?: UDP_DATA_PORT,
        )
        discovery?.let {
            it.stop()
            val d = createDiscoveryService(ann, discoveryIntervalMs).also { d -> d.start() }
            discovery = d
            scope.launch {
                d.announcements.collect { (a, ip) ->
                    if (a.id != userId && !isBlocked(a.id)) {
                        updatePeer(a, ip, System.currentTimeMillis(), online = true)
                        sendKeyFrame(a.id, PeerAddr(normalizePeerIp(ip), a.tcpPort))
                        flushOutbox(peerFrom(a, ip))
                    }
                }
            }
        }
    }

    suspend fun sendText(
        peerId: String,
        text: String,
        burn: Boolean = false,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
    ): String {
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
            replyTo = replyTo,
            mentions = mentions,
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
            synaLog("Send") { "text to=${peerId.take(6)} len=${text.length} enc=$encrypted burn=$burn" }
        } catch (e: Exception) {
            chatStore.updateStatus(msgId, MessageStatus.FAILED)
            synaLog("Send") { "text FAILED to=${peerId.take(6)}: ${e.message}" }
        }
        if (burn) {
            scheduleBurnPurge(peerId, msgId, ackTo = null, deliverAck = false, delayMs = BURN_ACK_FALLBACK_MS)
        }
        return msgId
    }

    /** 无节流直发公钥（REQ_KEY 响应专用，绕过 30s 限频） */
    private suspend fun sendKeyFrameNow(peerId: String, addr: PeerAddr) {
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

    private suspend fun sendKeyFrame(peerId: String, addr: PeerAddr) {
        val now = System.currentTimeMillis()
        val lastSent = peerKeySentM.value[peerId] ?: 0L
        if (now - lastSent < 30_000L) return
        peerKeySentM.updateMap { it + (peerId to now) }
        val frame = TransportFrame(
            type = FrameType.KEY,
            from = userId,
            to = peerId,
            msgId = newMsgId(),
            ts = now,
            body = publicKeyB64,
        )
        send(Peer(id = peerId, username = "", device = "", addr = addr, version = "", lastSeen = 0, online = true), frame)
    }

    private suspend fun send(peer: Peer, frame: TransportFrame) {
        if (!peer.online) {
            enqueueOutbox(peer.id, frame)
            synaLog("Outbox") { "queued ${frame.type} to=${peer.id.take(6)} (offline)" }
            return
        }
        sendNow(peer, frame)
        flushOutbox(peer)
    }

    private suspend fun sendNow(peer: Peer, frame: TransportFrame) {
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

    private fun enqueueOutbox(peerId: String, frame: TransportFrame) {
        outboxM.updateMap { it + (peerId to ((it[peerId] ?: emptyList()) + frame)) }
    }

    private suspend fun flushOutbox(peer: Peer) {
        val frames = outboxM.value[peer.id] ?: return
        val sent = mutableListOf<TransportFrame>()
        for (f in frames) {
            try {
                sendNow(peer, f)
                sent.add(f)
            } catch (e: Exception) {
                break
            }
        }
        if (sent.isNotEmpty()) {
            outboxM.updateMap { map ->
                map.mapValues { (k, v) -> if (k == peer.id) v.filterNot { it in sent } else v }
            }
            outboxM.updateMap { map -> map.filterValues { it.isNotEmpty() } }
            synaLog("Outbox") { "flushed ${sent.size} frames to ${peer.id.take(6)}" }
        }
    }

    private fun peerFrom(ann: DiscoveryAnnouncement, ip: String): Peer = Peer(
        id = ann.id,
        username = ann.username,
        device = ann.device,
        addr = PeerAddr(normalizePeerIp(ip), ann.tcpPort, ann.udpPort),
        version = ann.version,
        lastSeen = System.currentTimeMillis(),
        online = true,
    )

    fun stop() {
        if (!started) return
        started = false
        platformNet().unlockMulticast()
        discovery?.stop()
        tcp?.stop()
        udp?.stop()
    }

    /** 发往本机 IP 的流量统一走回环地址（规避代理 TUN 劫持、保证同机多实例互通） */
    private fun normalizePeerIp(ip: String): String =
        if (ip in localIps) "127.0.0.1" else ip

    private fun defaultUsername(): String = "用户-${userId.takeLast(4)}"

    private fun sessionId(peerId: String): String =
        listOf(userId, peerId).sorted().joinToString("|")

    private fun newMsgId(): String = Uuid.random().toString()
}

private fun IncomingEvent.PeerFrame.copyWith(frame: TransportFrame): IncomingEvent =
    IncomingEvent.PeerFrame(this.peerId, frame)

// CAS 原子更新
private fun <T> MutableStateFlow<List<T>>.updateList(transform: (List<T>) -> List<T>) {
    while (true) {
        val cur = value
        val next = transform(cur)
        if (compareAndSet(cur, next)) return
    }
}

// CAS 原子更新（读-改-写不再跨线程丢失更新）
private fun <K, V> MutableStateFlow<Map<K, V>>.updateMap(transform: (Map<K, V>) -> Map<K, V>) {
    while (true) {
        val cur = value
        val next = transform(cur)
        if (compareAndSet(cur, next)) return
    }
}
