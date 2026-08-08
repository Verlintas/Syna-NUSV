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

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual fun createTcpTransport(myId: String, myPublicKeyB64: String): ConnectionManager =
    JvmTcpTransport(myId, myPublicKeyB64)

private const val MAX_CONNECTIONS = 64

class JvmTcpTransport(private val myId: String, private val myPublicKeyB64: String) : ConnectionManager {

    private val incomingM = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 256)
    override val incoming = incomingM.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val outbound = ConcurrentHashMap<String, Socket>()
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)

    override val localTcpPort: Int
        get() = server?.localPort ?: 0

    override val localUdpPort: Int
        get() = 0

    override fun start() {
        val ss = ServerSocket(0)
        server = ss
        scope.launch { acceptLoop(ss) }
        scope.launch { heartbeatLoop() }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(TCP_HEARTBEAT_MS)
            outbound.entries.toList().forEach { (key, socket) ->
                try {
                    val ping = TransportFrame(
                        type = FrameType.PING,
                        from = myId,
                        to = "",
                        msgId = "",
                        ts = System.currentTimeMillis(),
                    )
                    val bytes = ping.encode()
                    synchronized(socket) {
                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        out.flush()
                    }
                } catch (e: IOException) {
                    closeByKey(key)
                } catch (e: Exception) {
                    closeByKey(key)
                }
            }
        }
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (true) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                return // 服务器关闭或异常，正常退出
            }
            // 并发连接数上限，防止异常场景资源耗尽
            if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                activeConnections.decrementAndGet()
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                continue
            }
            scope.launch {
                try {
                    readLoop(socket)
                } finally {
                    activeConnections.decrementAndGet()
                }
            }
        }
    }

    private suspend fun readLoop(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val length = input.readInt()
                if (length < 0 || length > 16 * 1024 * 1024) break
                val bytes = ByteArray(length)
                input.readFully(bytes)
                val frame = try {
                    decodeFrame(bytes)
                } catch (e: Exception) {
                    continue
                }
                incomingM.emit(IncomingEvent.PeerFrame(frame.from, frame))
                if (frame.type == FrameType.HELLO) {
                    incomingM.emit(IncomingEvent.PeerConnected(frame.from))
                }
            }
        } catch (_: IOException) {
        } catch (_: Exception) {
        } finally {
            // 连接结束：清理 outbound 中已失效的 socket（防止死 socket 泄漏）
            outbound.entries.removeIf { it.value === socket }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun send(addr: PeerAddr, frame: TransportFrame) {
        withContext(Dispatchers.IO) {
            val socket = getOrCreate(addr)
            try {
                val out = DataOutputStream(socket.getOutputStream())
                val bytes = frame.encode()
                synchronized(socket) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: IOException) {
                closeByKey("${addr.ip}:${addr.tcpPort}")
                throw e
            }
        }
    }

    private fun getOrCreate(addr: PeerAddr): Socket {
        val key = "${addr.ip}:${addr.tcpPort}"
        synchronized(outbound) {
            val existing = outbound[key]
            if (existing != null && existing.isConnected && !existing.isClosed) return existing

            val socket = Socket()
            try {
                socket.connect(java.net.InetSocketAddress(addr.ip, addr.tcpPort), 5_000)
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                throw e
            }
            socket.tcpNoDelay = true
            outbound[key] = socket

            val hello = TransportFrame(
                type = FrameType.HELLO,
                from = myId,
                to = "",
                msgId = "",
                ts = System.currentTimeMillis(),
                body = myPublicKeyB64,
            )
            val out = DataOutputStream(socket.getOutputStream())
            val bytes = hello.encode()
            synchronized(socket) {
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
            }
            scope.launch { readLoop(socket) }
            return socket
        }
    }

    private fun closeByKey(key: String) {
        outbound.remove(key)?.let { socket ->
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        outbound.values.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        outbound.clear()
        scope.coroutineContext[Job]?.cancel()
    }
}
