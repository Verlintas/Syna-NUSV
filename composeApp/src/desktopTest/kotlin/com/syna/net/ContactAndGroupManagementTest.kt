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

            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
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

            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
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

            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }

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
            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
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
            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            val alice = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }

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
            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            val alice = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }
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
            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            val alice = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }

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
            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            val alice = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }

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
            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            val alice = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }

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
