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

import com.syna.crypto.SynaCrypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SRV_KEEPALIVE_MS = 10_000L

actual fun createServerChannel(): ServerChannelFactory = JvmServerChannelFactory()

class JvmServerChannelFactory : ServerChannelFactory {

    override suspend fun connect(
        host: String,
        port: Int,
        password: String,
        userId: String,
        username: String,
        publicKeyB64: String,
        scope: CoroutineScope,
    ): ServerChannelResult {
        val channel = withContext(Dispatchers.IO) { TcpServerChannel(host, port) }

        try {
            // 1. 服务器明文下发 SRV_HELLO（salt → 派生通道密钥）
            val hello = channel.receiveOnce()
            if (hello.type != FrameType.SRV_HELLO) throw IOException("服务器响应异常: ${hello.type}")
            val helloBody = synaJson.decodeFromString(ServerHello.serializer(), hello.body ?: throw IOException("服务器响应无法解析"))
            channel.setChannelKey(SynaCrypto.deriveFromPassword(password, helloBody.salt, SERVER_CHANNEL_INFO))

            // 2. 加密发送认证
            val auth = TransportFrame(
                type = FrameType.SRV_AUTH,
                from = userId,
                to = helloBody.serverId,
                msgId = java.util.UUID.randomUUID().toString(),
                ts = System.currentTimeMillis(),
                body = synaJson.encodeToString(
                    ServerAuth.serializer(),
                    ServerAuth(userId, username, publicKeyB64, password),
                ),
            )
            channel.send(auth)

            // 3. 等待认证结果
            val ok = channel.receiveOnce()
            if (ok.type != FrameType.SRV_AUTH_OK) throw IOException("认证失败：密码错误或服务器拒绝")
            val okBody = synaJson.decodeFromString(ServerAuthOk.serializer(), ok.body ?: throw IOException("认证响应无法解析"))

            // 4. 启动持续读取（保留已发送帧的重传不需要——TCP 可靠）
            channel.startReading(scope)
            return ServerChannelResult(
                serverId = helloBody.serverId,
                groupId = okBody.groupId,
                groupName = okBody.groupName,
                members = okBody.members,
                history = okBody.history,
                channel = channel,
            )
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }
}

/** 纯 TCP 服务器通道：长度前缀帧 + 密码派生 AES-GCM 通道加密 + 保活 */
class TcpServerChannel internal constructor(
    host: String,
    port: Int,
) : ServerChannel {
    private val socket = Socket().apply {
        connect(InetSocketAddress(host, port), 8_000)
        tcpNoDelay = true
    }
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    private var channelKey: com.syna.crypto.SessionKey? = null
    // replay 缓冲：避免读循环早于订阅者启动时丢帧（如 AUTH_OK 后紧随的公告/历史帧）
    private val incomingM = MutableSharedFlow<TransportFrame>(extraBufferCapacity = 512, replay = 64)
    override val incoming = incomingM.asSharedFlow()
        @Volatile
    private var open = true

    fun setChannelKey(key: com.syna.crypto.SessionKey) {
        channelKey = key
    }

    override fun isOpen(): Boolean = open && !socket.isClosed

    override suspend fun send(frame: TransportFrame) {
        val key = channelKey ?: throw IOException("通道未就绪")
        val payload = SynaCrypto.encrypt(key, synaJson.encodeToString(TransportFrame.serializer(), frame))
            .encodeToByteArray()
        withContext(Dispatchers.IO) {
            synchronized(socket) {
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
            }
        }
    }

    suspend fun receiveOnce(): TransportFrame {
        return withContext(Dispatchers.IO) {
            val length = input.readInt()
            if (length < 0 || length > 16 * 1024 * 1024) throw IOException("帧长度异常")
            val bytes = ByteArray(length)
            input.readFully(bytes)
            decodePayload(bytes) ?: throw IOException("帧解密失败")
        }
    }

    fun startReading(scope: CoroutineScope) {
        scope.launch {
            readLoop()
        }
        scope.launch {
            keepAliveLoop()
        }
    }

    private suspend fun readLoop() {
        try {
            while (open) {
                val frame = receiveOnce()
                incomingM.emit(frame)
            }
        } catch (e: IOException) {
        } catch (e: Exception) {
        } finally {
            open = false
            close()
        }
    }

    private suspend fun keepAliveLoop() {
        while (open) {
            delay(SRV_KEEPALIVE_MS)
            try {
                val key = channelKey ?: return
                val ping = TransportFrame(
                    type = FrameType.PING,
                    from = "",
                    to = "",
                    msgId = "",
                    ts = System.currentTimeMillis(),
                )
                val payload = SynaCrypto.encrypt(key, synaJson.encodeToString(TransportFrame.serializer(), ping)).encodeToByteArray()
                withContext(Dispatchers.IO) {
                    synchronized(socket) {
                        output.writeInt(payload.size)
                        output.write(payload)
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                open = false
                close()
                return
            }
        }
    }

    private fun decodePayload(bytes: ByteArray): TransportFrame? {
        val key = channelKey ?: return try {
            decodeFrame(bytes)
        } catch (e: Exception) {
            null
        }
        return try {
            val payload = SynaCrypto.decrypt(key, bytes.decodeToString())
            synaJson.decodeFromString(TransportFrame.serializer(), payload)
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        open = false
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
