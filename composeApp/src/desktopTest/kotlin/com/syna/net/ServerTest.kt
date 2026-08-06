package com.syna.net

import com.russhwolf.settings.MapSettings
import com.syna.server.SynaServer
import com.syna.storage.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 3_000, sweepIntervalMs = 1_000)
    }

    private fun startServer(dataDir: java.nio.file.Path): SynaServer {
        val server = SynaServer(
            port = 0,
            password = "test-secret",
            groupName = "测试服务器",
            dataDir = dataDir,
        )
        server.start()
        return server
    }

    @Test
    fun joinServerAndChat() = runBlocking {
        val server = startServer(Files.createTempDirectory("syna-srv"))
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)

        try {
            a.start()
            b.start()
            val port = server.boundPort

            val ra = a.joinServer("127.0.0.1", port, "test-secret")
            assertTrue(ra.isSuccess, "A 应加入服务器: ${ra.exceptionOrNull()}")
            val rb = b.joinServer("127.0.0.1", port, "test-secret")
            assertTrue(rb.isSuccess, "B 应加入服务器: ${rb.exceptionOrNull()}")
            val groupId = ra.getOrThrow()
            assertEquals(ServerState.CONNECTED, a.serverState.value)
            assertEquals(ServerState.CONNECTED, b.serverState.value)

            // 等待双方公钥交换完成
            val keyDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < keyDeadline) {
                val aHas = a.peerKeys.value.values.isNotEmpty()
                val bHas = b.peerKeys.value.values.isNotEmpty()
                if (aHas && bHas) break
                delay(200)
            }
            assertTrue(a.peerKeys.value.isNotEmpty(), "A 应获得服务器成员公钥")
            assertTrue(b.peerKeys.value.isNotEmpty(), "B 应获得服务器成员公钥")

            // A 发群消息，B 收到并解密
            b.chatStore.activeConversationId.value = groupId
            a.sendGroupText(groupId, "你好，服务器上的 Bob！")
            val msgDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < msgDeadline) {
                val received = b.chatStore.messages.value[groupId]?.any { it.body == "你好，服务器上的 Bob！" } == true
                if (received) break
                delay(200)
            }
            val msg = b.chatStore.messages.value[groupId]?.firstOrNull { it.body == "你好，服务器上的 Bob！" }
            assertTrue(msg != null, "B 应通过服务器收到 A 的消息")
            assertEquals(true, msg?.encrypted, "服务器群消息应以群密钥加密传输")
            assertEquals("Alice", b.groups.value.firstOrNull { it.id == groupId }?.memberNames?.get(msg?.senderId ?: ""))

            // 成员同步：双方都看到对方在群成员列表
            val aMembers = a.groups.value.firstOrNull { it.id == groupId }?.memberIds
            val bMembers = b.groups.value.firstOrNull { it.id == groupId }?.memberIds
            assertEquals(2, aMembers?.size)
            assertTrue(aMembers?.contains(b.userId) == true, "A 的群成员列表应包含 B")
            assertTrue(bMembers?.contains(a.userId) == true, "B 的群成员列表应包含 A")
        } finally {
            server.stop()
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun wrongPasswordFails() = runBlocking {
        val server = startServer(Files.createTempDirectory("syna-srv"))
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        try {
            a.start()
            val result = a.joinServer("127.0.0.1", server.boundPort, "wrong-password")
            assertTrue(result.isFailure, "错误密码应被拒绝")
            assertEquals(ServerState.ERROR, a.serverState.value)
        } finally {
            server.stop()
            a.stop()
            scopeA.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun historyRecoveredByLateJoiner() = runBlocking {
        val server = startServer(Files.createTempDirectory("syna-srv"))
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeC = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val c = newEngine("Carol", scopeC)

        try {
            a.start()
            c.start()
            val port = server.boundPort

            val ra = a.joinServer("127.0.0.1", port, "test-secret")
            val groupId = ra.getOrThrow()

            // A 发送两条消息
            val keyDeadline = System.currentTimeMillis() + 8_000
            while (a.peerKeys.value.isEmpty() && System.currentTimeMillis() < keyDeadline) delay(200)
            a.sendGroupText(groupId, "历史消息一")
            a.sendGroupText(groupId, "历史消息二")
            delay(1_500)

            // C 后加入，应拉取到历史
            val rc = c.joinServer("127.0.0.1", port, "test-secret")
            assertTrue(rc.isSuccess, "C 应能加入")
            assertEquals(groupId, rc.getOrThrow())

            val historyDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < historyDeadline) {
                val msgs = c.chatStore.messages.value[groupId].orEmpty()
                if (msgs.count { it.body == "历史消息一" || it.body == "历史消息二" } == 2) break
                delay(200)
            }
            val bodies = c.chatStore.messages.value[groupId].orEmpty().map { it.body }
            assertTrue("历史消息一" in bodies, "C 应收到历史消息一: $bodies")
            assertTrue("历史消息二" in bodies, "C 应收到历史消息二: $bodies")
        } finally {
            server.stop()
            a.stop()
            c.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeC.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun burnMessagePurgedFromServerHistory() = runBlocking {
        val server = startServer(Files.createTempDirectory("syna-srv"))
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeC = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        val b = newEngine("Bob", scopeB)
        val c = newEngine("Carol", scopeC)

        try {
            a.start()
            b.start()
            c.start()
            val port = server.boundPort

            val ra = a.joinServer("127.0.0.1", port, "test-secret")
            val groupId = ra.getOrThrow()
            b.joinServer("127.0.0.1", port, "test-secret")

            val keyDeadline = System.currentTimeMillis() + 8_000
            while ((a.peerKeys.value.isEmpty() || b.peerKeys.value.isEmpty()) && System.currentTimeMillis() < keyDeadline) {
                delay(200)
            }

            // B 打开会话，A 发送普通消息 + 阅后即焚消息
            b.chatStore.activeConversationId.value = groupId
            a.sendGroupText(groupId, "普通消息")
            a.sendGroupText(groupId, "这条会烧掉", burn = true)

            val recvDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < recvDeadline) {
                val msgs = b.chatStore.messages.value[groupId].orEmpty()
                if (msgs.any { it.body == "这条会烧掉" } && msgs.any { it.body == "普通消息" }) break
                delay(200)
            }
            assertTrue(b.chatStore.messages.value[groupId].orEmpty().any { it.body == "这条会烧掉" }, "B 应先显示阅后即焚消息")

            // 等待焚毁（8s 显示 + ACK 回传 + 服务器清除）
            val burnDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < burnDeadline) {
                val bGone = b.chatStore.messages.value[groupId].orEmpty().none { it.body == "这条会烧掉" }
                if (bGone) break
                delay(300)
            }
            assertTrue(b.chatStore.messages.value[groupId].orEmpty().none { it.body == "这条会烧掉" }, "B 侧焚毁消息应消失")

            // C 后加入：历史里不应有焚毁消息，但应有普通消息
            c.joinServer("127.0.0.1", port, "test-secret")
            val historyDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < historyDeadline) {
                val bodies = c.chatStore.messages.value[groupId].orEmpty().map { it.body }
                if ("普通消息" in bodies) break
                delay(200)
            }
            val bodies = c.chatStore.messages.value[groupId].orEmpty().map { it.body }
            assertTrue("普通消息" in bodies, "普通消息应保留在服务器历史")
            assertTrue("这条会烧掉" !in bodies, "阅后即焚消息不应残留在服务器历史")
        } finally {
            server.stop()
            a.stop()
            b.stop()
            c.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
            scopeC.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun serverIdPersistsAcrossRestarts() {
        val dir = Files.createTempDirectory("syna-id")
        val s1 = SynaServer(0, "pw", "g", dir)
        val id1 = Files.readString(dir.resolve("server-id"))
        s1.stop()
        val s2 = SynaServer(0, "pw", "g", dir)
        val id2 = Files.readString(dir.resolve("server-id"))
        assertEquals(id1, id2, "重启后 serverId 应保持一致")
    }
}
