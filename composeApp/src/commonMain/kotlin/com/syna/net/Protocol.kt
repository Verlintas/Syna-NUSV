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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val DISCOVERY_PORT = 45877
const val UDP_DATA_PORT = 45878
const val DISCOVERY_MULTICAST_GROUP = "239.255.42.99"
const val DISCOVERY_BROADCAST = "255.255.255.255"
const val DISCOVERY_INTERVAL_MS = 3_000L
const val PEER_TIMEOUT_MS = 15_000L
const val SWEEP_INTERVAL_MS = 5_000L
const val TCP_HEARTBEAT_MS = 10_000L
const val BURN_DISPLAY_MS = 8_000L
const val BURN_SWEEP_TTL_MS = 60_000L // 未查看的焚毁消息 60s 后兜底烧毁
const val MAX_FRAME_AGE_MS = 10 * 60_000L // 实时帧时间窗口（重放防护）
const val ACK_RETRY_INTERVAL_MS = 3_000L // ACK 重传间隔
const val ACK_MAX_RETRIES = 3 // ACK 最大重传次数
const val MAX_OUTBOX_PER_PEER = 500 // 离线队列每对端上限（防内存泄漏）
const val BURN_ACK_FALLBACK_MS = 60_000L
const val SERVER_CHANNEL_INFO = "syna-server-channel"
const val SERVER_GROUP_INFO = "syna-server-group"
const val MAX_FILE_SIZE_BYTES = 200 * 1024 * 1024
const val MAX_FILE_CHUNKS = 4_000 // 分片总数上限（防恶意元数据撑爆内存）

val synaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
enum class FrameType {
    DISCOVERY,
    HELLO,
    KEY,
    TEXT,
    IMAGE,
    FILE_CHUNK,
    TYPING,
    READ,
    ACK,
    GROUP_KICK,
    GROUP_MUTE,
    GROUP_ADMIN,
    PING,
    PONG,
    BURN_ACK,
    GROUP_INVITE,
    GROUP_JOIN,
    GROUP_LEAVE,
    GROUP_DISSOLVE,
    GROUP_MESSAGE,
    EPHEMERAL_SESSION,
    RECALL,
    ANNOUNCEMENT,
    REQ_KEY,
    SRV_HELLO,
    SRV_AUTH,
    SRV_AUTH_OK,
    SRV_LEAVE,
}

@Serializable
data class DiscoveryAnnouncement(
    val id: String,
    val username: String,
    val device: String,
    val tcpPort: Int,
    val version: String,
    // UDP 数据通道端口：每实例独立，支持同机多实例/多设备并存（旧客户端缺省 45878）
    val udpPort: Int = UDP_DATA_PORT,
)

@Serializable
data class TransportFrame(
    val type: FrameType,
    val from: String,
    val to: String,
    val msgId: String,
    val ts: Long,
    val body: String? = null,
    val enc: Boolean = false,
    val burn: Boolean = false,
    val replyTo: String? = null,
    val mentions: List<String> = emptyList(),
    /** 前向保密进程纪元（v0.9.9）：进程启动随机；双方非 0 时参与会话密钥派生 */
    val epoch: Int = 0,
)

@Serializable
data class FileChunk(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val totalChunks: Int,
    val index: Int,
    val dataB64: String,
    /** 语音时长（毫秒；非语音文件为 0） */
    val durationMs: Long = 0L,
)

@Serializable
data class Announcement(
    val groupId: String,
    val text: String,
    val ts: Long,
    val by: String,
)

fun DiscoveryAnnouncement.encode(): ByteArray = synaJson.encodeToString(this).encodeToByteArray()

fun TransportFrame.encode(): ByteArray = synaJson.encodeToString(this).encodeToByteArray()

fun decodeAnnouncement(bytes: ByteArray): DiscoveryAnnouncement =
    synaJson.decodeFromString(DiscoveryAnnouncement.serializer(), bytes.decodeToString())

fun decodeFrame(bytes: ByteArray): TransportFrame =
    synaJson.decodeFromString(TransportFrame.serializer(), bytes.decodeToString())

@Serializable
data class GroupInfo(
    val id: String,
    val name: String,
    val creatorId: String,
    val memberIds: List<String>,
    val memberNames: Map<String, String>,
    val ts: Long,
    /** 管理员列表（创建者可指定；默认空=仅创建者管理） */
    val admins: List<String> = emptyList(),
)

@Serializable
data class GroupAdminEvent(
    val groupId: String,
    val targetId: String,
    val action: GroupAdminAction,
    /** 禁言时长（毫秒，0=解除禁言） */
    val durationMs: Long = 0L,
)

@Serializable
enum class GroupAdminAction {
    KICK,
    MUTE,
    UNMUTE,
    SET_ADMIN,
    REMOVE_ADMIN,
}

@Serializable
data class GroupMemberEvent(
    val groupId: String,
    val memberId: String,
    val memberName: String,
)

@Serializable
data class ServerHello(
    val serverId: String,
    val salt: String,
    val version: String,
    val groupName: String,
)

@Serializable
data class ServerAuth(
    val userId: String,
    val username: String,
    val publicKeyB64: String,
    val password: String,
)

@Serializable
data class ServerMember(
    val id: String,
    val name: String,
    val publicKeyB64: String? = null,
)

@Serializable
data class ServerAuthOk(
    val groupId: String,
    val groupName: String,
    val members: List<ServerMember>,
    val history: List<TransportFrame> = emptyList(),
)
