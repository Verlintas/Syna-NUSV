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

class JvmTcpTransport(private val myId: String, private val myPublicKeyB64: String) : ConnectionManager {

    private val incomingM = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 256)
    override val incoming = incomingM.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val outbound = ConcurrentHashMap<String, Socket>()

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
                return
            }
            scope.launch { readLoop(socket) }
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

    override fun stop() {        try {
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
