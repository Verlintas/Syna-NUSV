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

import com.russhwolf.settings.MapSettings
import com.syna.server.SynaServer
import com.syna.storage.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 3_000, sweepIntervalMs = 1_000, chatPersistence = null)
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

            // A 发送消息（E2E 加密，仅当时在线成员可解密）
            val keyDeadline = System.currentTimeMillis() + 8_000
            while (a.peerKeys.value.isEmpty() && System.currentTimeMillis() < keyDeadline) delay(200)
            a.sendGroupText(groupId, "在线消息一")

            // C 后加入（E2E 模型下，加入前的历史不可见——服务器只存密文）
            val rc = c.joinServer("127.0.0.1", port, "test-secret")
            assertTrue(rc.isSuccess, "C 应能加入")
            assertEquals(groupId, rc.getOrThrow())
            // 历史回放不应包含 A 的 E2E 密文（C 无对应会话密钥，无法解密且不显示密文）
            val historyBodies = c.chatStore.messages.value[groupId].orEmpty().map { it.body }
            assertTrue("在线消息一" !in historyBodies, "加入前的 E2E 历史对 C 不可见: $historyBodies")

            // C 加入后 A 再发消息，C 应能实时收到（E2E 密钥交换完成后）
            val cKeyDeadline = System.currentTimeMillis() + 8_000
            while (c.peerKeys.value.values.isEmpty() && System.currentTimeMillis() < cKeyDeadline) delay(200)
            a.sendGroupText(groupId, "C 加入后的消息")
            val liveDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < liveDeadline) {
                val msgs = c.chatStore.messages.value[groupId].orEmpty()
                if (msgs.any { it.body == "C 加入后的消息" }) break
                delay(200)
            }
            assertTrue(
                c.chatStore.messages.value[groupId].orEmpty().any { it.body == "C 加入后的消息" },
                "C 加入后应能实时收到 E2E 消息",
            )
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

            // C 后加入：E2E 模型下历史密文对 C 不可见（服务器不持有解密能力）
            val rc = c.joinServer("127.0.0.1", port, "test-secret")
            assertTrue(rc.isSuccess, "C 应能加入")
            delay(1_500)
            val bodies = c.chatStore.messages.value[groupId].orEmpty().map { it.body }
            // 历史不可见：不显示密文、不显示焚毁消息（服务器侧的焚毁清除逻辑仍有效）
            assertTrue("普通消息" !in bodies, "历史密文对 C 不可见（E2E 模型）")
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

class ServerManagementTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 3_000, sweepIntervalMs = 1_000, chatPersistence = null)
    }

    @Test
    fun kickAndBanBlocksRejoin() = runBlocking {
        val server = SynaServer(0, "pw", "管理测试", Files.createTempDirectory("syna-kick"))
        server.start()
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        try {
            a.start()
            val port = server.boundPort
            val r1 = a.joinServer("127.0.0.1", port, "pw")
            assertTrue(r1.isSuccess, "A 应先能加入")
            val groupId = r1.getOrThrow()

            // 服务器踢出并封禁
            server.kickUser(a.userId)

            // A 收到 GROUP_KICK → 本地群移除 + 断开
            val kickDeadline = System.currentTimeMillis() + 8_000
            while (a.serverState.value != ServerState.DISCONNECTED && System.currentTimeMillis() < kickDeadline) delay(200)
            assertEquals(ServerState.DISCONNECTED, a.serverState.value, "被踢后应断开")
            assertTrue(a.groups.value.none { it.id == groupId }, "被踢后群应被移除")
            assertTrue(server.bannedUsers.value.contains(a.userId), "服务器应有封禁记录")

            // 封禁期间无法重新加入
            val r2 = a.joinServer("127.0.0.1", port, "pw")
            assertTrue(r2.isFailure, "被封禁用户应无法重新加入")

            // 解除封禁后可加入
            server.unbanUser(a.userId)
            val r3 = a.joinServer("127.0.0.1", port, "pw")
            assertTrue(r3.isSuccess, "解除封禁后应能重新加入")
        } finally {
            server.stop()
            a.stop()
            scopeA.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun announcementBroadcastAndPersistToNewJoiner() = runBlocking {
        val server = SynaServer(0, "pw", "公告测试", Files.createTempDirectory("syna-ann"))
        server.start()
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = newEngine("Alice", scopeA)
        try {
            a.start()
            val port = server.boundPort
            val r1 = a.joinServer("127.0.0.1", port, "pw")
            assertTrue(r1.isSuccess)
            val groupId = r1.getOrThrow()

            server.setAnnouncement("今晚八点开黑！")
            val annDeadline = System.currentTimeMillis() + 8_000
            while (a.serverAnnouncement.value?.text != "今晚八点开黑！" && System.currentTimeMillis() < annDeadline) delay(200)
            assertEquals("今晚八点开黑！", a.serverAnnouncement.value?.text, "在线成员应收到公告")
            assertEquals(groupId, a.serverAnnouncement.value?.groupId)

            // 新加入者也应收到当前公告
            val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val b = newEngine("Bob", scopeB)
            try {
                b.start()
                val rb = b.joinServer("127.0.0.1", port, "pw")
                assertTrue(rb.isSuccess, "B 应能加入")
                val bAnnDeadline = System.currentTimeMillis() + 8_000
                while (b.serverAnnouncement.value?.text != "今晚八点开黑！" && System.currentTimeMillis() < bAnnDeadline) delay(200)
                println("[ANN-DEBUG] bAnn=${b.serverAnnouncement.value} bGroups=${b.groups.value.map{it.id}}")
                assertEquals("今晚八点开黑！", b.serverAnnouncement.value?.text, "新成员加入时应收到当前公告")
            } finally {
                b.stop()
                scopeB.coroutineContext[Job]?.cancel()
            }
        } finally {
            server.stop()
            a.stop()
            scopeA.coroutineContext[Job]?.cancel()
        }
    }
}
