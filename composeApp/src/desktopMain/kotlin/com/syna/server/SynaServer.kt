package com.syna.server

import com.syna.crypto.SynaCrypto
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

class SynaServer(
    val port: Int,
    private val password: String,
    private val groupName: String,
    private val dataDir: Path = Path.of("./syna-server-data"),
    private val historyLimit: Int = 200,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
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
        membersM.value = memberMap.values.toList()
    }

    private fun publishMessageCount() {
        messageCountM.value = messages.size
    }

    fun start() {
        if (runningM.value) return
        runningM.value = true
        Files.createDirectories(dataDir)
        loadHistory()
        val ss = ServerSocket(port)
        serverSocket = ss
        scope.launch { acceptLoop(ss) }
        printBanner()
    }

    fun stop() {
        runningM.value = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        sessions.forEach { it.close() }
        sessions.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (true) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                return
            }
            scope.launch { handleClient(socket) }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        var session: ClientSession? = null
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            session = ClientSession(socket, input, output)
            synchronized(sessions) { sessions.add(session) }

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
                        ServerHello(serverId, salt, "0.2.0", groupName),
                    ),
                ),
            )
            session.channelKey = SynaCrypto.deriveFromPassword(password, salt, "syna-server-channel")

            // 2. 等待加密认证
            val authFrame = session.receiveFrame() ?: return
            if (authFrame.type != FrameType.SRV_AUTH) return
            val auth = synaJson.decodeFromString(ServerAuth.serializer(), authFrame.body ?: return)
            if (auth.password != password) {
                 log("[SynaServer] 认证失败: ${auth.username} (${auth.userId.take(6)})")
                return
            }
            session.userId = auth.userId

            // 3. 注册成员 + 广播加入
            val isNew = auth.userId !in memberMap
            memberMap[auth.userId] = ServerMember(auth.userId, auth.username, auth.publicKeyB64)
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

            // 4. 下发群信息 + 成员密钥 + 历史
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
                            members = memberMap.values.toList(),
                            history = messages.toList(),
                        ),
                    ),
                ),
            )

            // 5. 持续读取与中继
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
                    memberMap.remove(uid)
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
                                GroupMemberEvent(groupId, uid, memberMap[uid]?.name ?: uid),
                            ),
                        ),
                        except = null,
                    )
                }
            }
        }
    }

    private suspend fun handleRelayFrame(session: ClientSession, frame: TransportFrame) {
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
                frame.body?.let { msgId -> purgeMessage(msgId) }
                broadcast(frame, except = session)
            }
            FrameType.RECALL -> {
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
            FrameType.TYPING, FrameType.FILE_CHUNK, FrameType.ANNOUNCEMENT,
            -> {
                if (frame.type == FrameType.KEY) {
                    memberMap[frame.from]?.let {
                        memberMap[frame.from] = it.copy(publicKeyB64 = frame.body)
                    }
                }
                if (frame.type == FrameType.GROUP_MESSAGE || frame.type == FrameType.KEY) {
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

    private fun rewriteHistory() {
        try {
            val lines = messages.joinToString("\n") { frame ->
                synaJson.encodeToString(TransportFrame.serializer(), frame)
            }
            Files.writeString(historyFile, if (lines.isEmpty()) "" else "$lines\n")
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
        val targets = synchronized(sessions) {
            sessions.filter { it !== except && it.isOpen() }
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
        println("  Syna 私人聊天服务器 v0.2.0")
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
        var userId: String? = null
        var channelKey: com.syna.crypto.SessionKey? = null

        fun isOpen(): Boolean = !socket.isClosed

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        suspend fun sendRaw(frame: TransportFrame) {
            val payload = if (channelKey == null) {
                frame.encode()
            } else {
                val key = channelKey!!
                com.syna.crypto.SynaCrypto.encrypt(key, synaJson.encodeToString(TransportFrame.serializer(), frame))
                    .encodeToByteArray()
            }
            sendBytes(payload)
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
