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
package com.syna.chat

import com.syna.storage.ChatPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED,
    READ,
}

enum class MessageKind {
    TEXT,
    IMAGE,
    FILE,
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
    val kind: MessageKind = MessageKind.TEXT,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val localPath: String? = null,
    val progress: Int? = null,
    val recalled: Boolean = false,
    val replyToId: String? = null,
    val mentions: List<String> = emptyList(),
)

data class Conversation(
    val peerId: String,
    val peerName: String,
    val lastMessage: String,
    val lastTs: Long,
    val unreadCount: Int,
)

class ChatStore(private val persistence: ChatPersistence? = null) {

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rewriteJob: Job? = null

    private val messagesM = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = messagesM.asStateFlow()

    private val conversationsM = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = conversationsM.asStateFlow()

    val activeConversationId = MutableStateFlow<String?>(null)

    init {
        // 启动时从本地文件恢复聊天记录（多端持久化）
        persistence?.load()?.let { loaded ->
            if (loaded.isNotEmpty()) {
                val grouped = loaded.groupBy { it.conversationId }
                messagesM.value = grouped
                grouped.forEach { (conversationId, list) ->
                    val last = list.last()
                    conversationsM.updateList { convs ->
                        convs + Conversation(
                            peerId = conversationId,
                            peerName = conversationId,
                            lastMessage = last.body,
                            lastTs = last.ts,
                            unreadCount = 0,
                        )
                    }
                }
            }
        }
    }

    /** 消息变更后防抖重写本地文件（500ms 合并批量操作） */
    private fun scheduleRewrite() {
        if (persistence == null) return
        rewriteJob?.cancel()
        rewriteJob = persistScope.launch {
            delay(500)
            persistence.rewrite(messagesM.value)
        }
    }

    /** 立即全量重写持久化文件（会话密钥轮换迁移用；用当前密钥重新加密全部数据） */
    fun rewriteNow() {
        persistence?.rewrite(messagesM.value)
    }

    fun addIncoming(peerId: String, peerName: String, msg: ChatMessage, preview: String = msg.body) {
        messagesM.updateMap { it + (peerId to (it[peerId] ?: emptyList()) + msg) }
        upsertConversation(
            peerId = peerId,
            peerName = peerName,
            lastMessage = preview,
            lastTs = msg.ts,
            unreadDelta = if (activeConversationId.value == peerId) 0 else 1,
        )
        scheduleRewrite()
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
        scheduleRewrite()
    }


    fun updateStatus(msgId: String, status: MessageStatus) {
        messagesM.updateMap { map ->
            map.mapValues { (_, list) ->
                list.map { if (it.id == msgId) it.copy(status = status) else it }
            }
        }
        scheduleRewrite()
    }

    fun updateProgress(msgId: String, progress: Int) {
        messagesM.updateMap { map ->
            map.mapValues { (_, list) ->
                list.map { if (it.id == msgId) it.copy(progress = progress) else it }
            }
        }
    }

    fun markRecalledByMsgId(msgId: String) {
        messagesM.updateMap { map ->
            map.mapValues { (_, list) ->
                list.map { if (it.id == msgId) it.copy(recalled = true, body = "[消息已撤回]") else it }
            }
        }
        scheduleRewrite()
    }

    fun messageById(msgId: String): ChatMessage? =
        messagesM.value.values.flatten().firstOrNull { it.id == msgId }

    fun removeMessageById(msgId: String) {
        val map = messagesM.value
        var removedFrom: String? = null
        val newMap = map.mapValues { (conversationId, list) ->
            if (list.any { it.id == msgId }) {
                removedFrom = conversationId
                list.filterNot { it.id == msgId }
            } else list
        }
        messagesM.value = newMap
        val conversationId = removedFrom ?: return
        val remaining = newMap[conversationId]
        if (remaining.isNullOrEmpty()) {
            conversationsM.updateList { it.filterNot { c -> c.peerId == conversationId } }
        } else {
            refreshPreview(conversationId, remaining)
        }
        scheduleRewrite()
    }

    private fun refreshPreview(conversationId: String, remaining: List<ChatMessage>) {
        val last = remaining.lastOrNull()
        conversationsM.updateList { list ->
            list.map { conv ->
                if (conv.peerId == conversationId) {
                    conv.copy(
                        lastMessage = if (last == null) "" else if (last.burnAfterReading) "🔥 阅后即焚消息" else last.body,
                        lastTs = last?.ts ?: conv.lastTs,
                    )
                } else conv
            }
        }
    }

    fun removeMessage(conversationId: String, msgId: String) {
        messagesM.updateMap { map ->
            val list = map[conversationId] ?: return@updateMap map
            val updated = list.filterNot { it.id == msgId }
            if (updated.isEmpty()) {
                map - conversationId
            } else {
                map + (conversationId to updated)
            }
        }
        // 若移除的是最后一条，刷新会话预览
        val remaining = messagesM.value[conversationId]
        conversationsM.updateList { list ->
            list.map { conv ->
                if (conv.peerId == conversationId) {
                    val last = remaining?.lastOrNull()
                    if (last == null) {
                        conv.copy(lastMessage = "", lastTs = conv.lastTs)
                    } else {
                        conv.copy(
                            lastMessage = if (last.burnAfterReading) "🔥 阅后即焚消息" else last.body,
                            lastTs = last.ts,
                        )
                    }
                } else conv
            }
        }
    }

    /** 锁定期间释放内存中的消息（防 root 进程 dump 读取），会话元数据保留 */
    fun releaseMemory() {
        messagesM.value = emptyMap()
        // 会话元数据中的消息明文预览一并清除（锁定期间防 root dump 泄露）
        conversationsM.value = conversationsM.value.map { c ->
            c.copy(lastMessage = "", unreadCount = 0)
        }
    }

    /** 解锁后从持久化重新加载消息 */
    fun reloadFromPersistence() {
        persistence?.load()?.let { loaded ->
            if (loaded.isNotEmpty()) {
                messagesM.value = loaded.groupBy { it.conversationId }
            }
        }
    }

    /** 清除本地全部聊天记录（内存 + 持久化文件） */
    fun clearAllHistory() {
        messagesM.value = emptyMap()
        conversationsM.value = emptyList()
        activeConversationId.value = null
        persistence?.clear()
    }

    /** 聊天记录文件占用（字节） */
    fun historyFileSize(): Long = persistence?.fileSize() ?: 0L

    fun removeConversation(conversationId: String) {
        messagesM.updateMap { it - conversationId }
        conversationsM.updateList { list -> list.filterNot { it.peerId == conversationId } }
    }

    fun purgeExpired(ttlMs: Long, now: Long = System.currentTimeMillis()) {
        conversationsM.value.filter { now - it.lastTs > ttlMs }.forEach { conv ->
            removeConversation(conv.peerId)
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

// CAS 原子更新（读-改-写不再跨线程丢失更新）
internal fun <T> MutableStateFlow<T>.casUpdate(transform: (T) -> T) {
    while (true) {
        val cur = value
        val next = transform(cur)
        if (compareAndSet(cur, next)) return
    }
}

internal fun <K, V> MutableStateFlow<Map<K, V>>.updateMap(transform: (Map<K, V>) -> Map<K, V>) {
    casUpdate(transform)
}

internal fun <T> MutableStateFlow<List<T>>.updateList(transform: (List<T>) -> List<T>) {
    casUpdate(transform)
}
