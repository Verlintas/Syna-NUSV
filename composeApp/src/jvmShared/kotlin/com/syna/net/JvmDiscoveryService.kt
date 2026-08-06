package com.syna.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Enumeration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

actual fun createDiscoveryService(
    announcement: DiscoveryAnnouncement,
    intervalMs: Long,
): DiscoveryService = JvmDiscoveryService(announcement, intervalMs)

class JvmDiscoveryService(
    private val announcement: DiscoveryAnnouncement,
    private val intervalMs: Long = DISCOVERY_INTERVAL_MS,
) : DiscoveryService {

    private val announcementsM = kotlinx.coroutines.flow.MutableSharedFlow<Pair<DiscoveryAnnouncement, String>>()
    override val announcements = announcementsM.asSharedFlow()

    private var receiver: MulticastSocket? = null
    private var sender: DatagramSocket? = null
    private var jobs = mutableListOf<kotlinx.coroutines.Job>()

    override val localAddress: String
        get() = findSiteLocalAddress() ?: "127.0.0.1"

    override fun start() {
        val socket = MulticastSocket(null)
        socket.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
        try {
            socket.setOption(java.net.StandardSocketOptions.SO_REUSEPORT, true)
        } catch (_: Exception) {
        }
        socket.broadcast = true
        socket.bind(java.net.InetSocketAddress(DISCOVERY_PORT))
        val group = InetAddress.getByName(DISCOVERY_MULTICAST_GROUP)
        socket.joinGroup(
            java.net.InetSocketAddress(group, DISCOVERY_PORT),
            NetworkInterface.getByInetAddress(localInetAddress()!!),
        )
        receiver = socket

        val out = DatagramSocket()
        out.broadcast = true
        sender = out

        jobs += kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { receiveLoop(socket) }
        jobs += kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { announceLoop() }
    }

    private suspend fun receiveLoop(socket: MulticastSocket) {
        val buffer = ByteArray(8192)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketException) {
                return
            }
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val ann = try {
                decodeAnnouncement(data)
            } catch (e: Exception) {
                null
            } ?: continue
            if (ann.id != announcement.id) {
                announcementsM.emit(ann to (packet.address.hostAddress ?: "127.0.0.1"))
            }
        }
    }

    private suspend fun announceLoop() {
        val payload = announcement.encode()
        val groupAddr = InetAddress.getByName(DISCOVERY_MULTICAST_GROUP)
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            try {
                sender?.let { out ->
                    out.send(DatagramPacket(payload, payload.size, groupAddr, DISCOVERY_PORT))
                    val broadcast = InetAddress.getByName(DISCOVERY_BROADCAST)
                    out.send(DatagramPacket(payload, payload.size, broadcast, DISCOVERY_PORT))
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        try {
            val group = InetAddress.getByName(DISCOVERY_MULTICAST_GROUP)
            receiver?.leaveGroup(InetSocketAddress(group, DISCOVERY_PORT), NetworkInterface.getByInetAddress(localInetAddress()!!))
        } catch (_: Exception) {
        }
        receiver?.close()
        sender?.close()
    }

    private fun localInetAddress(): InetAddress? {
        findSiteLocalAddress()?.let {
            return InetAddress.getByName(it)
        }
        return InetAddress.getByName("127.0.0.1")
    }

    private fun findSiteLocalAddress(): String? {
        val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces() ?: return null
        var fallback: String? = null
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            if (!intf.isUp || intf.isLoopback) continue
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr.isSiteLocalAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress
                }
                if (addr is java.net.Inet4Address && fallback == null) {
                    fallback = addr.hostAddress
                }
            }
        }
        return fallback
    }
}
