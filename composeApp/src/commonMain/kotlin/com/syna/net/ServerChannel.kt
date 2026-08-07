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
