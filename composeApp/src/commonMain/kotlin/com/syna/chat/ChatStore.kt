package com.syna.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED,
    READ,
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val ts: Long,
    val status: MessageStatus,
    val burnAfterReading: Boolean,
    val encrypted: Boolean,
)

data class Conversation(
    val peerId: String,
    val peerName: String,
    val lastMessage: String,
    val lastTs: Long,
    val unreadCount: Int,
)

class ChatStore {

    private val messagesM = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = messagesM.asStateFlow()

    private val conversationsM = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = conversationsM.asStateFlow()

    var activeConversationId: String? = null

    fun addIncoming(peerId: String, peerName: String, msg: ChatMessage) {
        messagesM.updateMap { it + (peerId to (it[peerId] ?: emptyList()) + msg) }
        upsertConversation(
            peerId = peerId,
            peerName = peerName,
            lastMessage = msg.body,
            lastTs = msg.ts,
            unreadDelta = if (activeConversationId == peerId) 0 else 1,
        )
    }

    fun addOutgoing(peerId: String, peerName: String, msg: ChatMessage) {
        messagesM.updateMap { it + (peerId to (it[peerId] ?: emptyList()) + msg) }
        upsertConversation(
            peerId = peerId,
            peerName = peerName,
            lastMessage = msg.body,
            lastTs = msg.ts,
            unreadDelta = 0,
        )
    }

    fun updateStatus(msgId: String, status: MessageStatus) {
        messagesM.updateMap { map ->
            map.mapValues { (_, list) ->
                list.map { if (it.id == msgId) it.copy(status = status) else it }
            }
        }
    }

    fun markAllRead(peerId: String) {
        conversationsM.updateList { list ->
            list.map { if (it.peerId == peerId) it.copy(unreadCount = 0) else it }
        }
    }

    fun renamePeer(peerId: String, peerName: String) {
        conversationsM.updateList { list ->
            list.map { if (it.peerId == peerId) it.copy(peerName = peerName) else it }
        }
    }

    private fun upsertConversation(peerId: String, peerName: String, lastMessage: String, lastTs: Long, unreadDelta: Int) {
        conversationsM.updateList { list ->
            val existing = list.firstOrNull { it.peerId == peerId }
            if (existing == null) {
                list + Conversation(
                    peerId = peerId,
                    peerName = peerName,
                    lastMessage = lastMessage,
                    lastTs = lastTs,
                    unreadCount = unreadDelta.coerceAtLeast(0),
                )
            } else {
                list.map {
                    if (it.peerId == peerId) {
                        it.copy(
                            lastMessage = lastMessage,
                            lastTs = lastTs,
                            unreadCount = it.unreadCount + unreadDelta,
                        )
                    } else it
                }
            }
        }
    }
}

internal fun <K, V> MutableStateFlow<Map<K, V>>.updateMap(transform: (Map<K, V>) -> Map<K, V>) {
    value = transform(value)
}

internal fun <T> MutableStateFlow<List<T>>.updateList(transform: (List<T>) -> List<T>) {
    value = transform(value)
}
