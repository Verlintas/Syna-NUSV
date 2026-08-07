package com.syna.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual fun createUdpTransport(myId: String): ConnectionManager = JvmUdpTransport(myId)

class JvmUdpTransport(private val myId: String) : ConnectionManager {

    private val incomingM = MutableSharedFlow<IncomingEvent>(extraBufferCapacity = 256)
    override val incoming = incomingM.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: DatagramSocket? = null

    override val localTcpPort: Int
        get() = 0

    // UDP 数据通道端口：每实例独立（临时端口），通过发现公告广播，支持同机多实例
    override val localUdpPort: Int
        get() = socket?.localPort ?: 0

    override fun start() {
        try {
            val s = DatagramSocket(null)
            s.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
            s.bind(java.net.InetSocketAddress(0))
            socket = s
            scope.launch { receiveLoop(s) }
        } catch (_: SocketException) {
        }
    }

    private suspend fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (e: SocketException) {
                return
            }
            val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val frame = try {
                decodeFrame(bytes)
            } catch (e: Exception) {
                continue
            }
            if (frame.from != myId) {
                incomingM.emit(IncomingEvent.PeerFrame(frame.from, frame))
            }
        }
    }

    override suspend fun send(addr: PeerAddr, frame: TransportFrame) {
        withContext(Dispatchers.IO) {
            val s = socket ?: return@withContext
            val bytes = frame.encode()
            val packet = DatagramPacket(
                bytes,
                bytes.size,
                InetAddress.getByName(addr.ip),
                addr.udpPort,
            )
            s.send(packet)
        }
    }

    override fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        scope.coroutineContext[Job]?.cancel()
    }
}
