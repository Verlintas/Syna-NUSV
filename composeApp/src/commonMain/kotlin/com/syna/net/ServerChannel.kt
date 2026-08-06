package com.syna.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

data class ServerChannelResult(
    val serverId: String,
    val groupId: String,
    val groupName: String,
    val members: List<ServerMember>,
    val history: List<TransportFrame>,
    val channel: ServerChannel,
)

interface ServerChannel {
    val incoming: SharedFlow<TransportFrame>
    fun isOpen(): Boolean
    suspend fun send(frame: TransportFrame)
    fun close()
}

interface ServerChannelFactory {
    suspend fun connect(
        host: String,
        port: Int,
        password: String,
        userId: String,
        username: String,
        publicKeyB64: String,
        scope: CoroutineScope,
    ): ServerChannelResult
}

data class ServerSession(
    val groupId: String,
    val serverId: String,
    val host: String,
    val port: Int,
    val channel: ServerChannel,
    val groupKey: com.syna.crypto.SessionKey,
)

enum class ServerState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

expect fun createServerChannel(): ServerChannelFactory
