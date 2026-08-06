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

val synaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
enum class FrameType {
    DISCOVERY,
    HELLO,
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
)

fun DiscoveryAnnouncement.encode(): ByteArray = synaJson.encodeToString(this).encodeToByteArray()

fun TransportFrame.encode(): ByteArray = synaJson.encodeToString(this).encodeToByteArray()

fun decodeAnnouncement(bytes: ByteArray): DiscoveryAnnouncement =
    synaJson.decodeFromString(DiscoveryAnnouncement.serializer(), bytes.decodeToString())

fun decodeFrame(bytes: ByteArray): TransportFrame =
    synaJson.decodeFromString(TransportFrame.serializer(), bytes.decodeToString())
