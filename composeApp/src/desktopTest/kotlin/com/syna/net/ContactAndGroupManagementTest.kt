package com.syna.net

import com.russhwolf.settings.MapSettings
import com.syna.chat.MessageKind
import com.syna.chat.MessageStatus
import com.syna.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactAndGroupManagementTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
    }

    @Test
    fun dissolveGroupNotifiesAllMembers() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)

        try {
            a.start()
            b.start()

            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            a.sendText(bob.id, "handshake")
            val keyDeadline = System.currentTimeMillis() + 8_000
            while (a.peerKeys.value[bob.id] == null && System.currentTimeMillis() < keyDeadline) delay(200)

            val groupId = a.createGroup("临时讨论组", listOf(bob.id))
            val joinDeadline = System.currentTimeMillis() + 8_000
            while (b.groups.value.none { it.id == groupId } && System.currentTimeMillis() < joinDeadline) delay(200)
            assertTrue(b.groups.value.any { it.id == groupId }, "B 应收到群邀请")

            // 群主解散
            a.dissolveGroup(groupId)

            val dissolveDeadline = System.currentTimeMillis() + 8_000
            while (b.groups.value.any { it.id == groupId } && System.currentTimeMillis() < dissolveDeadline) delay(200)

            assertTrue(a.groups.value.none { it.id == groupId }, "A 本地群应被移除")
            assertTrue(b.groups.value.none { it.id == groupId }, "B 收到解散通知后群应被移除")
            assertNull(b.chatStore.messages.value[groupId], "B 侧群会话应被清除")

            // 非群主不能解散（A 去解散 B 创建的群）
            val aliceInB = b.peers.value.first { it.username == "Alice" }
            b.createGroup("Bob 的群", listOf(aliceInB.id))
            val bobGroup = b.groups.value.first { it.name == "Bob 的群" }
            a.dissolveGroup(bobGroup.id)
            assertTrue(b.groups.value.any { it.id == bobGroup.id }, "非群主解散应被拒绝")
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun removeContactBlocksFurtherDiscovery() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)

        try {
            a.start()
            b.start()

            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            assertTrue(a.peers.value.any { it.id == bob.id })

            // 删除联系人
            a.removeContact(bob.id)
            assertTrue(a.peers.value.none { it.id == bob.id }, "删除后不应出现在联系人列表")
            assertTrue(a.isBlocked(bob.id))
            assertTrue(a.blockedContacts.value.contains(bob.id))

            // 即使 B 持续广播，A 也不会重新显示（屏蔽生效）
            delay(4_000)
            assertTrue(a.peers.value.none { it.id == bob.id }, "屏蔽期间不应重新出现")

            // 解除屏蔽后重新发现
            a.unblockContact(bob.id)
            assertTrue(!a.isBlocked(bob.id))
            val rediscoverDeadline = System.currentTimeMillis() + 8_000
            while (a.peers.value.none { it.id == bob.id } && System.currentTimeMillis() < rediscoverDeadline) delay(300)
            assertTrue(a.peers.value.any { it.id == bob.id }, "解除屏蔽后应能重新发现")
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun manualRefreshBroadcastsAndKeepsPeers() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)

        try {
            a.start()
            b.start()

            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")

            // 手动刷新不应清除已有联系人，也不应抛异常
            a.refreshContacts()
            assertEquals(bob.id, a.peers.value.firstOrNull { it.id == bob.id }?.id)
            assertTrue(a.peers.value.first { it.id == bob.id }.online, "刷新后已知联系人在线状态应保持")

            // B 也能感知到 A 的刷新广播（A 仍在线）
            delay(1_000)
            assertTrue(b.peers.value.any { it.id == a.userId && it.online }, "B 应仍看到 A 在线")
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
        }
    }
}

class TypingAndRecallTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
    }

    @Test
    fun typingSignalVisibleToPeer() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            a.sendTyping(bob.id)
            val deadline = System.currentTimeMillis() + 5_000
            while (b.typing.value[a.userId] == null && System.currentTimeMillis() < deadline) delay(200)
            assertTrue(b.typing.value[a.userId] != null, "B 应看到 A 的输入状态")
            assertEquals(a.userId, b.typing.value[a.userId]?.second, "输入状态应记录发送者")
            // 3 秒后状态应被清理（轮询等待 sweep 执行）
            val cleanDeadline = System.currentTimeMillis() + 6_000
            while (b.typing.value.isNotEmpty() && System.currentTimeMillis() < cleanDeadline) delay(300)
            assertTrue(b.typing.value.isEmpty(), "输入状态应超时清理")
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun recallMessageMarksOnBothSides() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            val alice = waitForPeer(b, "Alice") ?: throw AssertionError("b 未发现 Alice")

            val msgId = a.sendText(bob.id, "这条要撤回")
            val recvDeadline = System.currentTimeMillis() + 8_000
            while (b.chatStore.messages.value[alice.id]?.any { it.id == msgId } != true &&
                System.currentTimeMillis() < recvDeadline
            ) {
                delay(200)
            }
            assertTrue(b.chatStore.messages.value[alice.id]?.any { it.id == msgId } == true)

            a.recallMessage(bob.id, msgId)
            val recallDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < recallDeadline) {
                val bRecalled = b.chatStore.messages.value[alice.id]?.firstOrNull { it.id == msgId }?.recalled == true
                val aRecalled = a.chatStore.messages.value[bob.id]?.firstOrNull { it.id == msgId }?.recalled == true
                if (bRecalled && aRecalled) break
                delay(200)
            }
            assertTrue(a.chatStore.messages.value[bob.id]?.firstOrNull { it.id == msgId }?.recalled == true, "A 侧应标记已撤回")
            assertTrue(b.chatStore.messages.value[alice.id]?.firstOrNull { it.id == msgId }?.recalled == true, "B 侧应标记已撤回")
            assertEquals("[消息已撤回]", b.chatStore.messages.value[alice.id]?.firstOrNull { it.id == msgId }?.body)
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun cannotRecallOthersMessage() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            val alice = waitForPeer(b, "Alice") ?: throw AssertionError("b 未发现 Alice")
            val msgId = a.sendText(bob.id, "Bob 想撤回这条")
            val recvDeadline = System.currentTimeMillis() + 8_000
            while (b.chatStore.messages.value[alice.id]?.any { it.id == msgId } != true &&
                System.currentTimeMillis() < recvDeadline
            ) {
                delay(200)
            }
            // B 尝试撤回 A 的消息 → 应被拒绝
            b.recallMessage(alice.id, msgId)
            delay(1_000)
            assertTrue(
                b.chatStore.messages.value[alice.id]?.firstOrNull { it.id == msgId }?.recalled != true,
                "非本人消息不可撤回",
            )
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }
}

class FileTransferTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
    }

    @Test
    fun fileTransferReassemblesCorrectly() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            val alice = waitForPeer(b, "Alice") ?: throw AssertionError("b 未发现 Alice")

            // 跨多块的大文件（256KB）
            val original = ByteArray(256 * 1024) { (it % 251).toByte() }
            a.sendFile(bob.id, "测试文件.bin", original, "application/octet-stream")

            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val msgs = b.chatStore.messages.value[alice.id].orEmpty()
                if (msgs.any { it.fileName == "测试文件.bin" && it.localPath != null }) break
                delay(300)
            }
            val received = b.chatStore.messages.value[alice.id]
                ?.firstOrNull { it.fileName == "测试文件.bin" && it.localPath != null }
            assertTrue(received != null, "B 应收到完整文件")
            assertEquals(MessageKind.FILE, received?.kind)
            assertEquals(original.size.toLong(), received?.fileSize)

            val saved = com.syna.util.readFileBytes(received!!.localPath!!)
            assertTrue(saved.contentEquals(original), "重组后的文件字节应与原文件一致")

            // A 侧发送状态应为已发送
            val sentMsg = a.chatStore.messages.value[bob.id]?.firstOrNull { it.fileName == "测试文件.bin" }
            assertEquals(MessageStatus.SENT, sentMsg?.status)
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun imageFileMarkedAsImageKind() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            val alice = waitForPeer(b, "Alice") ?: throw AssertionError("b 未发现 Alice")

            val png = ByteArray(1024) { 0x55 }
            a.sendFile(bob.id, "photo.png", png, "image/png")

            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                if (b.chatStore.messages.value[alice.id].orEmpty().any { it.fileName == "photo.png" && it.localPath != null }) break
                delay(300)
            }
            val received = b.chatStore.messages.value[alice.id]
                ?.firstOrNull { it.fileName == "photo.png" && it.localPath != null }
            assertTrue(received != null, "B 应收到图片")
            assertEquals(MessageKind.IMAGE, received?.kind, "图片应标记为 IMAGE 类型")
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }
}

class ReplyAndMentionTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
    }

    @Test
    fun replyToAndMentionsCarriedOverTransport() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "Bob") ?: throw AssertionError("a 未发现 Bob")
            val alice = waitForPeer(b, "Alice") ?: throw AssertionError("b 未发现 Alice")

            val originalId = a.sendText(bob.id, "原始消息")
            val recvDeadline = System.currentTimeMillis() + 8_000
            while (b.chatStore.messages.value[alice.id]?.any { it.id == originalId } != true &&
                System.currentTimeMillis() < recvDeadline
            ) {
                delay(200)
            }

            // 引用回复 + @提及
            a.sendText(bob.id, "回复你 @Bob", replyTo = originalId, mentions = listOf(bob.id))
            val replyDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < replyDeadline) {
                val msgs = b.chatStore.messages.value[alice.id].orEmpty()
                if (msgs.any { it.replyToId == originalId && it.body == "回复你 @Bob" }) break
                delay(200)
            }
            val reply = b.chatStore.messages.value[alice.id]
                ?.firstOrNull { it.body == "回复你 @Bob" }
            assertTrue(reply != null, "B 应收到引用回复")
            assertEquals(originalId, reply?.replyToId, "replyToId 应正确传递")
            assertTrue(reply?.mentions?.contains(bob.id) == true, "mentions 应正确传递")

            // 群聊引用回复
            val groupId = a.createGroup("测试群", listOf(bob.id))
            val joinDeadline = System.currentTimeMillis() + 8_000
            while (b.groups.value.none { it.id == groupId } && System.currentTimeMillis() < joinDeadline) delay(200)
            a.sendGroupText(groupId, "群里的原始消息")
            val gMsgDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < gMsgDeadline) {
                if (b.chatStore.messages.value[groupId].orEmpty().any { it.body == "群里的原始消息" }) break
                delay(200)
            }
            val originalGroup = b.chatStore.messages.value[groupId]?.firstOrNull { it.body == "群里的原始消息" }
            a.sendGroupText(groupId, "群引用", replyTo = originalGroup?.id, mentions = listOf(bob.id))
            val gReplyDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < gReplyDeadline) {
                if (b.chatStore.messages.value[groupId].orEmpty().any { it.body == "群引用" && it.replyToId == originalGroup?.id }) break
                delay(200)
            }
            val gReply = b.chatStore.messages.value[groupId]?.firstOrNull { it.body == "群引用" }
            assertEquals(originalGroup?.id, gReply?.replyToId, "群聊引用回复应正确传递")
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }
}


/** 带超时的发现等待：返回 null 表示超时（挂起时用于诊断） */
private suspend fun waitForPeer(engine: SynaEngine, username: String, timeoutMs: Long = 12_000): com.syna.net.Peer? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val p = engine.peers.value.firstOrNull { it.username == username && it.online }
        if (p != null) return p
        delay(200)
    }
    println("[WAIT-DEBUG] 等待 $username 超时，当前 peers: ${engine.peers.value.map { "${it.username}/${it.online}" }}")
    return null
}

class StabilityTest {

    private fun newEngine(name: String, scope: CoroutineScope, mode: com.syna.core.ConnectionMode = com.syna.core.ConnectionMode.AUTO): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        settings.connectionMode = mode
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
    }

    @Test
    fun twoInstancesOnSameHostChatViaUdp() = runBlocking {
        // 模拟同一台电脑跑两个实例：各自独立的 UDP 数据端口（动态端口），互不冲突
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("UdpAlice", scopeA, com.syna.core.ConnectionMode.UDP)
        val b = newEngine("UdpBob", scopeB, com.syna.core.ConnectionMode.UDP)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "UdpBob") ?: throw AssertionError("a 未发现 UdpBob")
            val alice = waitForPeer(b, "UdpAlice") ?: throw AssertionError("b 未发现 UdpAlice")

            // 双方应通过公告学习到对方的独立 UDP 端口
            println("[UDP-DEBUG] A 视角 B udpPort=${bob.addr.udpPort}")
            println("[UDP-DEBUG] B 视角 A udpPort=${alice.addr.udpPort}")

            // UDP 模式下互发消息（TCP 不参与）
            val received = mutableListOf<String>()
            val job = scopeB.launch {
                b.incoming.collect { event ->
                    if (event is IncomingEvent.PeerFrame && event.frame.type == FrameType.TEXT) {
                        received.add(event.frame.body ?: "")
                    }
                }
            }
            val aRaw = mutableListOf<String>()
            val aJob = scopeA.launch {
                a.rawIncoming.collect { f -> aRaw.add("${f.type}:${f.body?.take(20)}") }
            }
            a.sendText(bob.id, "UDP 通道你好")
            b.sendText(alice.id, "UDP 通道回话")
            val deadline = System.currentTimeMillis() + 8_000
            while (received.size < 1 && System.currentTimeMillis() < deadline) delay(200)
            assertTrue(received.any { it == "UDP 通道你好" }, "UDP 通道应能互相收发: $received")
            val aReceived = a.chatStore.messages.value[bob.id]?.any { it.body == "UDP 通道回话" } == true
            println("[UDP-DEBUG] A raw: $aRaw")
            println("[UDP-DEBUG] A store: ${a.chatStore.messages.value.keys} ${a.chatStore.messages.value.values.flatten().map { it.body }}")
            assertTrue(aReceived, "A 应通过 UDP 收到 B 的消息")
            job.cancel()
            aJob.cancel()
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun oversizedFileRejected() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("OversizedAlice", scopeA)
        val b = newEngine("OversizedBob", scopeB)
        try {
            a.start()
            b.start()
            val bob = waitForPeer(a, "OversizedBob") ?: throw AssertionError("a 未发现 OversizedBob")

            // 超过 200MB 的"文件"应被拒绝且不产生消息
            val huge = ByteArray(com.syna.net.MAX_FILE_SIZE_BYTES + 1)
            a.sendFile(bob.id, "huge.bin", huge)
            delay(500)
            assertTrue(a.chatStore.messages.value[bob.id].orEmpty().none { it.fileName == "huge.bin" }, "超大文件不应发送")
            assertTrue(b.chatStore.messages.value[a.userId].orEmpty().none { it.fileName == "huge.bin" }, "接收方不应收到超大文件")
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }
}
