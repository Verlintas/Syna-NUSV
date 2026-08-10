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
package com.syna.server

import com.syna.crypto.SynaCrypto
import com.syna.net.Announcement
import com.syna.net.FrameType
import com.syna.net.GroupMemberEvent
import com.syna.net.ServerAuth
import com.syna.net.ServerAuthOk
import com.syna.net.ServerHello
import com.syna.net.ServerMember
import com.syna.net.TransportFrame
import com.syna.net.decodeFrame
import com.syna.net.encode
import com.syna.net.synaJson
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SynaServer(
    val port: Int,
    private val password: String,
    private val groupName: String,
    private val dataDir: Path = Path.of("./syna-server-data"),
    private val historyLimit: Int = 200,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val MAX_SESSIONS = 64
    private val BURN_HISTORY_TTL_MS = 60 * 60 * 1000L // burn 消息服务器历史 1h TTL
    val boundPort: Int
        get() = serverSocket?.localPort ?: 0

    private val historyFile: Path = dataDir.resolve("history.jsonl")
    private val idFile: Path = dataDir.resolve("server-id")

    val groupId: String = "srv-${serverId()}-${port}"

    private val serverId: String by lazy { serverId() }

    private var serverSocket: ServerSocket? = null
    private val sessions = mutableListOf<ClientSession>()
    private val messages = mutableListOf<TransportFrame>()
    private val memberMap = mutableMapOf<String, ServerMember>()
    private val banned = mutableSetOf<String>()
    private var announcementText: String? = null
    private val bansFile: Path = dataDir.resolve("bans.json")

    private val bannedM = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val bannedUsers: StateFlow<List<String>> = bannedM.asStateFlow()

    // ===== UI 可观测状态 =====
    private val runningM = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRunning = runningM.asStateFlow()

    private val membersM = kotlinx.coroutines.flow.MutableStateFlow<List<ServerMember>>(emptyList())
    val members: StateFlow<List<ServerMember>> = membersM.asStateFlow()

    private val messageCountM = kotlinx.coroutines.flow.MutableStateFlow(0)
    val messageCount: StateFlow<Int> = messageCountM.asStateFlow()

    private val logsM = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = logsM.asStateFlow()

    private fun log(msg: String) {
        println(msg)
        logsM.value = (logsM.value + msg).takeLast(500)
    }

    private fun publishMembers() {
        synchronized(memberMap) { membersM.value = memberMap.values.toList() }
    }

    private fun publishMessageCount() {
        messageCountM.value = messages.size
    }

    fun start() {
        if (runningM.value) return
        runningM.value = true
        Files.createDirectories(dataDir)
        loadBans()
        loadHistory()
        sweepBurnExpired()
        val ss = try {
            ServerSocket(port)
        } catch (e: Exception) {
            // 端口占用等启动失败：复位状态（否则 UI 显示"运行中"但端口不可用）
            runningM.value = false
            throw e
        }
        serverSocket = ss
        scope.launch { acceptLoop(ss) }
        // burn 消息历史 TTL 清理（每小时）
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(BURN_HISTORY_TTL_MS)
                sweepBurnExpired()
            }
        }
        printBanner()
    }

    fun stop() {
        runningM.value = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        // 加锁遍历关闭，避免与 handleClient 的并发 remove 竞争（ConcurrentModificationException）
        val toClose = synchronized(sessions) { sessions.toList() }
        toClose.forEach { it.close() }
        synchronized(sessions) { sessions.clear() }
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        try {
            while (true) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    return
                }
                scope.launch { handleClient(socket) }
            }
        } finally {
            // 非手动停止的意外退出：复位运行状态（崩溃自动重启依赖此标志）
            if (runningM.value) {
                runningM.value = false
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        var session: ClientSession? = null
        try {
            // 连接数上限（防资源耗尽）
            if (synchronized(sessions) { sessions.size } >= MAX_SESSIONS) {
                socket.close()
                return
            }
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            session = ClientSession(socket, input, output)
            synchronized(sessions) { sessions.add(session) }
            session.startSender(scope)

            // 1. 明文 SRV_HELLO（携带 salt，客户端据此派生通道密钥）；发送完成后再启用加密
            val salt = randomSalt()
            session.sendRaw(
                TransportFrame(
                    type = FrameType.SRV_HELLO,
                    from = groupId,
                    to = "",
                    msgId = "",
                    ts = System.currentTimeMillis(),
                    body = synaJson.encodeToString(
                        ServerHello.serializer(),
                        ServerHello(serverId, salt, "0.9.1", groupName),
                    ),
                ),
            )
            session.channelKey = SynaCrypto.deriveFromPassword(password, salt, "syna-server-channel")

            // 2. 等待加密认证（15s 超时：未认证连接不得永久占资源）
            val authFrame = withTimeoutOrNull(15_000L) { session.receiveFrame() } ?: return
            if (authFrame.type != FrameType.SRV_AUTH) return
            val auth = try {
                synaJson.decodeFromString(ServerAuth.serializer(), authFrame.body ?: return)
            } catch (e: Exception) {
                return
            }
            if (synchronized(banned) { auth.userId in banned }) {
                log("[SynaServer] 拒绝封禁用户: ${auth.username} (${auth.userId.take(6)})")
                return
            }
            if (auth.password != password) {
                log("[SynaServer] 认证失败: ${auth.username} (${auth.userId.take(6)})")
                // 认证失败限速（1s 延迟：防远程暴力破解）
                kotlinx.coroutines.delay(1_000L)
                return
            }
            session.userId = auth.userId

            // 3. 注册成员 + 广播加入
            val isNew = auth.userId !in memberMap
            synchronized(memberMap) { memberMap[auth.userId] = ServerMember(auth.userId, auth.username, auth.publicKeyB64) }
            publishMembers()
            log("[SynaServer] 成员加入: ${auth.username} (${auth.userId.take(6)})${if (isNew) "" else " 重新连接"}")
            if (isNew) {
                broadcast(
                    TransportFrame(
                        type = FrameType.GROUP_JOIN,
                        from = auth.userId,
                        to = groupId,
                        msgId = UUID.randomUUID().toString(),
                        ts = System.currentTimeMillis(),
                        body = synaJson.encodeToString(
                            GroupMemberEvent.serializer(),
                            GroupMemberEvent(groupId, auth.userId, auth.username),
                        ),
                    ),
                    except = session,
                )
            }

            // 4. 下发群信息 + 成员密钥 + 历史 + 公告
            session.sendRaw(
                TransportFrame(
                    type = FrameType.SRV_AUTH_OK,
                    from = groupId,
                    to = auth.userId,
                    msgId = "",
                    ts = System.currentTimeMillis(),
                    body = synaJson.encodeToString(
                        ServerAuthOk.serializer(),
                        ServerAuthOk(
                            groupId = groupId,
                            groupName = groupName,
                            members = synchronized(memberMap) { memberMap.values.toList() },
                            history = synchronized(messages) { messages.toList() },
                        ),
                    ),
                ),
            )
            announcementText?.let { text ->
                session.sendRaw(
                    TransportFrame(
                        type = FrameType.ANNOUNCEMENT,
                        from = groupId,
                        to = groupId,
                        msgId = UUID.randomUUID().toString(),
                        ts = System.currentTimeMillis(),
                        body = synaJson.encodeToString(
                            Announcement.serializer(),
                            Announcement(groupId, text, System.currentTimeMillis(), "服务器"),
                        ),
                    ),
                )
            }

            // 5. 持续读取与中继（读超时：网络黑洞/进程被杀不留僵尸会话）
            socket.soTimeout = 180_000
            while (true) {
                val frame = session.receiveFrame() ?: break
                handleRelayFrame(session, frame)
            }
        } catch (e: Exception) {
            // 连接中断
        } finally {
            synchronized(sessions) { sessions.remove(session) }
            session?.close()
            session?.userId?.let { uid ->
                log("[SynaServer] 成员离开: $uid")
                val remaining = sessions.any { it.userId == uid }
                if (!remaining) {
                    // 名称快照：remove 前取值（否则恒为 null 显示为 id）
                    val name = synchronized(memberMap) { memberMap[uid]?.name } ?: uid
                    synchronized(memberMap) { memberMap.remove(uid) }
                    publishMembers()
                    broadcast(
                        TransportFrame(
                            type = FrameType.GROUP_LEAVE,
                            from = uid,
                            to = groupId,
                            msgId = UUID.randomUUID().toString(),
                            ts = System.currentTimeMillis(),
                            body = synaJson.encodeToString(
                                GroupMemberEvent.serializer(),
                                GroupMemberEvent(groupId, uid, name),
                            ),
                        ),
                        except = null,
                    )
                }
            }
        }
    }

    private suspend fun handleRelayFrame(session: ClientSession, frame: TransportFrame) {
        // 防身份伪造：所有中继帧的 from 必须等于认证身份；from == groupId（服务器身份）一律拒绝，
        // 否则已认证成员可伪造服务器身份广播公告/管理帧。
        if (frame.from.isNotEmpty() && frame.from != session.userId) return
        when (frame.type) {
            FrameType.PING -> {
                // 保活：响应 PONG，不做中继
                session.sendRaw(
                    TransportFrame(
                        type = FrameType.PONG,
                        from = groupId,
                        to = "",
                        msgId = "",
                        ts = System.currentTimeMillis(),
                    ),
                )
            }
            FrameType.SRV_LEAVE -> throw IOException("client leave")
            FrameType.BURN_ACK -> {
                // 鉴权：仅当目标消息是阅后即焚且请求者为原发送者时清除（防任意成员删他人消息）
                val msgId = frame.body ?: ""
                val target = synchronized(messages) { messages.firstOrNull { it.msgId == msgId } }
                if (target != null && target.burn && target.from == session.userId) {
                    purgeMessage(msgId)
                    broadcast(frame, except = session)
                }
            }
            FrameType.RECALL -> {
                // 归属校验：仅消息原发送者可撤回（防任意成员撤回他人消息）
                val target = synchronized(messages) { messages.firstOrNull { it.msgId == frame.msgId } }
                if (target != null && target.from != session.userId) return
                // 撤回帧也持久化，保证后加入者回放历史时能看到撤回标记
                synchronized(messages) {
                    if (messages.none { it.msgId == frame.msgId }) {
                        messages.add(frame)
                        if (messages.size > historyLimit) messages.removeAt(0)
                        publishMessageCount()
                    }
                }
                rewriteHistory()
                broadcast(frame, except = session)
            }
            FrameType.GROUP_MESSAGE, FrameType.KEY, FrameType.TEXT, FrameType.READ,
            FrameType.GROUP_JOIN, FrameType.GROUP_LEAVE, FrameType.EPHEMERAL_SESSION,
            FrameType.TYPING, FrameType.FILE_CHUNK, FrameType.ANNOUNCEMENT, FrameType.REQ_KEY,
            -> {
                if (frame.type == FrameType.KEY) {
                    synchronized(memberMap) {
                        memberMap[frame.from]?.let {
                            memberMap[frame.from] = it.copy(publicKeyB64 = frame.body)
                        }
                    }
                }
                // 仅持久化加密的群消息（明文回退帧不落库，服务器不留存可读内容）与公钥帧
                if ((frame.type == FrameType.GROUP_MESSAGE && frame.enc) || frame.type == FrameType.KEY) {
                    persistMessage(frame)
                }
                broadcast(frame, except = session)
            }
            else -> Unit
        }
    }

    private fun persistMessage(frame: TransportFrame) {
        synchronized(messages) {
            if (messages.any { it.msgId == frame.msgId }) return
            messages.add(frame)
            if (messages.size > historyLimit) {
                messages.removeAt(0)
            }
            publishMessageCount()
        }
        rewriteHistory()
    }

    private fun purgeMessage(msgId: String) {
        synchronized(messages) {
            if (messages.removeAll { it.msgId == msgId }) {
                publishMessageCount()
            }
        }
        rewriteHistory()
        log("[SynaServer] 阅后即焚清除: ${msgId.take(8)}")
    }

    /** 阅后即焚 TTL：服务器历史中的 burn 消息 1 小时后自动清除（不依赖 BURN_ACK） */
    private fun sweepBurnExpired() {
        val cutoff = System.currentTimeMillis() - BURN_HISTORY_TTL_MS
        val changed = synchronized(messages) {
            val before = messages.size
            messages.removeAll { it.burn && it.ts < cutoff }
            messages.size != before
        }
        if (changed) {
            publishMessageCount()
            rewriteHistory()
        }
    }

    private fun rewriteHistory() {
        try {
            // 锁内快照（防并发 CME）+ 原子写（tmp+rename 防截断）
            val snapshot = synchronized(messages) { messages.toList() }
            val lines = snapshot.joinToString("\n") { frame ->
                synaJson.encodeToString(TransportFrame.serializer(), frame)
            }
            val tmp = java.nio.file.Path.of(historyFile.toString() + ".tmp")
            Files.writeString(tmp, if (lines.isEmpty()) "" else "$lines\n")
            try {
                Files.move(tmp, historyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp, historyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log("[SynaServer] 历史写入失败: ${e.message}")
        }
    }

    private fun loadHistory() {
        try {
            if (!Files.exists(historyFile)) return
            Files.readAllLines(historyFile).forEach { line ->
                try {
                    synchronized(messages) { messages.add(synaJson.decodeFromString(TransportFrame.serializer(), line)) }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            log("[SynaServer] 历史读取失败: ${e.message}")
        }
        publishMessageCount()
        log("[SynaServer] 已加载历史 ${messages.size} 条")
    }

    private suspend fun broadcast(frame: TransportFrame, except: ClientSession?) {
        // 只发给已认证的会话：认证完成前（channelKey 未设置）收到广播会污染握手
        val targets = synchronized(sessions) {
            sessions.filter { it !== except && it.isOpen() && it.userId != null }
        }
        targets.forEach { session ->
            try {
                session.sendRaw(frame)
            } catch (e: Exception) {
            }
        }
    }

    private fun printBanner() {
        println("================================================")
        println("  Syna 私人聊天服务器 v0.9.1")
        println("  群名称: $groupName")
        println("  端口:   ${boundPort}")
        println("  数据目录: ${dataDir.toAbsolutePath()}")
        println("------------------------------------------------")
        println("  局域网访问地址:")
        localAddresses().forEach { ip ->
            println("    $ip:${boundPort}")
        }
        println("------------------------------------------------")
        println("  公网访问（内网穿透，任选其一）:")
        println("    frp:    frpc tcp 类型, remote_port 映射到本机 ${boundPort} 端口")
        println("    ngrok:  ngrok tcp ${boundPort}")
        println("    Tailscale: 同一 Tailnet 内直接使用节点 IP:${boundPort}")
        println("  然后客户端输入穿透后的地址:端口 + 密码加入群聊")
        println("================================================")
    }

    private fun loadBans() {
        try {
            if (Files.exists(bansFile)) {
                val ids = synaJson.decodeFromString<List<String>>(Files.readString(bansFile))
                synchronized(banned) { banned.addAll(ids) }
            }
        } catch (e: Exception) {
            log("[SynaServer] 封禁列表读取失败: ${e.message}")
        }
        publishBans()
    }

    private fun saveBans() {
        try {
            // 锁内快照（防与并发 kick/unban 竞争 CME 丢封禁）
            val snapshot = synchronized(banned) { banned.toList() }
            Files.writeString(bansFile, synaJson.encodeToString(snapshot))
        } catch (e: Exception) {
        }
    }

    private fun publishBans() {
        synchronized(banned) { bannedM.value = banned.toList() }
    }

    /** 踢出并封禁成员（GUI 调用） */
    fun kickUser(userId: String) {
        if (userId in banned) return
        synchronized(banned) { banned.add(userId) }
        saveBans()
        publishBans()
        // 发送踢出通知并断开该用户全部连接（多会话用户不能留后门）
        val sessionsToKick = synchronized(sessions) { sessions.filter { it.userId == userId } }
        val member = synchronized(memberMap) { memberMap[userId] }
        val kickFrame = TransportFrame(
            type = FrameType.GROUP_KICK,
            // from = 服务器身份（客户端以 creatorId==serverId 校验管理权限）
            from = serverId,
            to = groupId,
            msgId = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            body = synaJson.encodeToString(
                com.syna.net.GroupAdminEvent.serializer(),
                com.syna.net.GroupAdminEvent(groupId, userId, com.syna.net.GroupAdminAction.KICK),
            ),
        )
        scope.launch {
            sessionsToKick.forEach { s ->
                try {
                    s.sendRaw(kickFrame)
                } catch (e: Exception) {
                }
            }
            // 延迟关闭：给接收端 readLoop 处理踢人帧的时间，
            // 防止"帧在 TCP 缓冲中未读 + 立即 close"竞争导致踢人通知丢失
            kotlinx.coroutines.delay(500)
            sessionsToKick.forEach { it.close() }
            broadcast(kickFrame, except = null)
        }
        log("[SynaServer] 已踢出并封禁: ${member?.name ?: userId} (${userId.take(6)})")
    }

    fun unbanUser(userId: String) {
        synchronized(banned) { banned.remove(userId) }
        saveBans()
        publishBans()
        log("[SynaServer] 已解除封禁: ${userId.take(6)}")
    }

    /** 发布群公告（GUI 调用），广播给所有在线成员 */
    fun setAnnouncement(text: String) {
        val trimmed = text.trim()
        announcementText = trimmed
        val ann = Announcement(groupId, trimmed, System.currentTimeMillis(), "服务器")
        val frame = TransportFrame(
            type = FrameType.ANNOUNCEMENT,
            from = groupId,
            to = groupId,
            msgId = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            body = synaJson.encodeToString(Announcement.serializer(), ann),
        )
        scope.launch { broadcast(frame, except = null) }
        log("[SynaServer] 群公告已发布: ${trimmed.take(40)}")
    }

    fun localAddressesText(): String = localAddresses().joinToString(", ") { "$it:$boundPort" }

    private fun localAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isSiteLocalAddress && addr is java.net.Inet4Address) {
                        result.add(addr.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
        }
        if (result.isEmpty()) result.add(InetAddress.getLocalHost().hostAddress ?: "127.0.0.1")
        return result
    }

    private fun serverId(): String {
        try {
            Files.createDirectories(dataDir)
        } catch (e: Exception) {
        }
        try {
            if (Files.exists(idFile)) {
                return Files.readString(idFile).trim()
            }
        } catch (e: Exception) {
        }
        val id = UUID.randomUUID().toString()
        try {
            Files.writeString(idFile, id)
        } catch (e: Exception) {
        }
        return id
    }

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    inner class ClientSession(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: DataOutputStream,
    ) {
        @Volatile
        var userId: String? = null
        var channelKey: com.syna.crypto.SessionKey? = null

        /** 慢客户端隔离：有界发送队列（广播入队不阻塞其他成员；满则丢帧并计数） */
        private val sendQueue = kotlinx.coroutines.channels.Channel<ByteArray>(256)
        @Volatile
        var droppedFrames = 0
            private set
        private var senderStarted = false

        fun isOpen(): Boolean = !socket.isClosed

        fun startSender(scope: CoroutineScope) {
            if (senderStarted) return
            senderStarted = true
            scope.launch {
                for (payload in sendQueue) {
                    try {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            synchronized(socket) {
                                output.writeInt(payload.size)
                                output.write(payload)
                                output.flush()
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            sendQueue.close()
        }

        suspend fun sendRaw(frame: TransportFrame) {
            val payload = if (channelKey == null) {
                frame.encode()
            } else {
                val key = channelKey!!
                com.syna.crypto.SynaCrypto.encrypt(key, synaJson.encodeToString(TransportFrame.serializer(), frame))
                    .encodeToByteArray()
            }
            // 入队（有界）：慢客户端队列满时丢帧，不阻塞广播
            if (!sendQueue.trySend(payload).isSuccess) {
                droppedFrames++
            }
        }

        suspend fun receiveFrame(): TransportFrame? {
            return kotlinx.coroutines.withContext(Dispatchers.IO) {
                val length = input.readInt()
                if (length < 0 || length > 16 * 1024 * 1024) throw IOException("帧长度异常")
                val bytes = ByteArray(length)
                input.readFully(bytes)
                val key = channelKey
                val frame = if (key == null) {
                    try {
                        decodeFrame(bytes)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    try {
                        val payload = SynaCrypto.decrypt(key, bytes.decodeToString())
                        synaJson.decodeFromString(TransportFrame.serializer(), payload)
                    } catch (e: Exception) {
                        null
                    }
                }
                frame ?: throw IOException("帧解析失败")
            }
        }

        private suspend fun sendBytes(payload: ByteArray) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                synchronized(socket) {
                    output.writeInt(payload.size)
                    output.write(payload)
                    output.flush()
                }
            }
        }
    }
}
