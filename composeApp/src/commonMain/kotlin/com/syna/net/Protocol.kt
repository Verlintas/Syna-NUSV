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
const val BURN_ACK_FALLBACK_MS = 60_000L
const val SERVER_CHANNEL_INFO = "syna-server-channel"
const val SERVER_GROUP_INFO = "syna-server-group"

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
    PING,
    PONG,
    BURN_ACK,
    GROUP_INVITE,
    GROUP_JOIN,
    GROUP_LEAVE,
    GROUP_MESSAGE,
    EPHEMERAL_SESSION,
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
)

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
