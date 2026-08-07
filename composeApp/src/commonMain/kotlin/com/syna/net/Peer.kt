package com.syna.net

import kotlinx.serialization.Serializable

@Serializable
data class PeerAddr(val ip: String, val tcpPort: Int, val udpPort: Int = UDP_DATA_PORT)

data class Peer(
    val id: String,
    val username: String,
    val device: String,
    val addr: PeerAddr,
    val version: String,
    val lastSeen: Long,
    val online: Boolean,
)
