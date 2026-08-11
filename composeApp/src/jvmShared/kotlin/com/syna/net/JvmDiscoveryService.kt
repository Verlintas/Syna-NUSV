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

    private val announcementsM = kotlinx.coroutines.flow.MutableSharedFlow<Pair<DiscoveryAnnouncement, String>>(extraBufferCapacity = 64)
    override val announcements = announcementsM.asSharedFlow()

    private var receiver: MulticastSocket? = null
    private var sender: DatagramSocket? = null

    /** 隐身模式：不发送发现广播（但仍监听入站公告，手动刷新可用） */
    @Volatile
    private var stealth = false

    /** 省电：快频广播计数（前 [FAST_ANNOUNCE_COUNT] 次按 intervalMs，之后按 3 倍间隔） */
    @Volatile
    private var fastAnnounceCount = 0
    private val FAST_ANNOUNCE_COUNT = 10
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
        receiver = socket

        // 加入多播组；多播不可用的网络（如部分 VPN）退化为仅广播接收
        try {
            val group = InetAddress.getByName(DISCOVERY_MULTICAST_GROUP)
            val intf = NetworkInterface.getByInetAddress(localInetAddress()!!)
            if (intf != null && intf.supportsMulticast()) {
                socket.joinGroup(java.net.InetSocketAddress(group, DISCOVERY_PORT), intf)
            }
        } catch (e: Exception) {
            // ignore: broadcast-only mode
        }

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
            } catch (e: Exception) {
                // 非 SocketException 异常：短暂停顿后继续（防 receiveLoop 永久死亡）
                kotlinx.coroutines.delay(200)
                continue
            }
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val ann = try {
                decodeAnnouncement(data)
            } catch (e: Exception) {
                null
            } ?: continue
            if (ann.id != announcement.id) {
                // 收到他人公告：说明网络活跃，重置快频（省电与响应平衡）
                fastAnnounceCount = 0
                announcementsM.emit(ann to (packet.address.hostAddress ?: "127.0.0.1"))
            }
        }
    }

    override fun setStealth(on: Boolean) {
        stealth = on
    }

    private suspend fun announceLoop() {
        val payload = announcement.encode()
        val groupAddr = InetAddress.getByName(DISCOVERY_MULTICAST_GROUP)
        while (true) {
            // 省电：在线稳定后（快频 10 次）降为 3 倍间隔；入站公告/手动刷新重置为快频
            val delayMs = if (fastAnnounceCount < FAST_ANNOUNCE_COUNT) intervalMs else intervalMs * 3
            kotlinx.coroutines.delay(delayMs)
            if (stealth) continue // 隐身：不广播自身
            fastAnnounceCount++
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

    override fun sendNow() {
        val payload = announcement.encode()
        try {
            val groupAddr = InetAddress.getByName(DISCOVERY_MULTICAST_GROUP)
            val broadcast = InetAddress.getByName(DISCOVERY_BROADCAST)
            sender?.let { out ->
                out.send(DatagramPacket(payload, payload.size, groupAddr, DISCOVERY_PORT))
                out.send(DatagramPacket(payload, payload.size, broadcast, DISCOVERY_PORT))
            }
        } catch (_: Exception) {
        }
        // 手动刷新：重置为快频（下轮立即广播）
        fastAnnounceCount = 0
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

    private fun localInetAddress(): InetAddress {
        try {
            findSiteLocalAddress()?.let {
                return InetAddress.getByName(it)
            }
        } catch (_: Exception) {
        }
        return try {
            InetAddress.getByName("127.0.0.1")
        } catch (e: Exception) {
            InetAddress.getLoopbackAddress()
        }
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
