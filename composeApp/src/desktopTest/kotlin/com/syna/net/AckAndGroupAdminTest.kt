package com.syna.net

import com.syna.core.ConnectionMode
import com.russhwolf.settings.MapSettings
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AckAndGroupAdminTest {

    private fun makeEngine(name: String, scope: CoroutineScope, mode: ConnectionMode = ConnectionMode.AUTO): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 500, peerTimeoutMs = 3_000, sweepIntervalMs = 500, chatPersistence = null)
    }

    @Test
    fun p2pTextGetsAckedAndRetriesStop() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = makeEngine("Alice", scopeA)
        val b = makeEngine("Bob", scopeB)
        try {
            a.start(); b.start()
            // 等待发现
            val alice = b.peers.first { list -> list.any { p -> p.username == "Alice" && p.online } }
                .first { it.username == "Alice" }
            val bob = a.peers.first { list -> list.any { p -> p.username == "Bob" && p.online } }
                .first { it.username == "Bob" }

            // 密钥交换
            a.sendText(bob.id, "handshake")
            delay(1_500)
            assertTrue(a.peerKeys.value[bob.id] != null, "A 应获得 B 的公钥")

            // 发送消息 → 应收到 ACK 并停止重传追踪
            a.sendText(bob.id, "需要确认的消息")
            delay(500)
            assertTrue(b.chatStore.messages.value[alice.id]?.any { it.body == "需要确认的消息" } == true, "B 应收到消息")
            // 等待 ACK 处理
            delay(1_500)
            assertEquals(0, a.pendingAckCount.value, "收到 ACK 后不应有待确认消息")
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun groupAdminKickMuteAndAdmins() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeC = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = makeEngine("Alice", scopeA)
        val b = makeEngine("Bob", scopeB)
        val c = makeEngine("Carol", scopeC)
        try {
            a.start(); b.start(); c.start()
            delay(1_200)
            val bob = a.peers.first { list -> list.any { p -> p.username == "Bob" && p.online } }
                .first { it.username == "Bob" }
            val carol = a.peers.first { list -> list.any { p -> p.username == "Carol" && p.online } }
                .first { it.username == "Carol" }

            // Alice 建群（含 Bob、Carol）
            val groupId = a.createGroup("测试群", listOf(bob.id, carol.id))
            delay(1_500)
            assertTrue(b.groups.value.any { it.id == groupId }, "Bob 应加入群")

            // 禁言 Bob（Alice 是创建者）
            a.muteMember(groupId, bob.id, 60 * 60 * 1000L)
            delay(1_000)
            assertTrue(b.isMuted(groupId, bob.id) || a.isMuted(groupId, bob.id), "禁言应生效")
            assertTrue(a.isMuted(groupId, bob.id), "A 本地禁言记录应生效")

            // 设 Carol 为管理员
            a.setGroupAdmin(groupId, carol.id, true)
            delay(1_000)
            assertTrue(c.groups.value.firstOrNull { it.id == groupId }?.admins?.contains(carol.id) == true, "Carol 应成为管理员")

            // Carol（管理员）踢出 Bob
            c.kickFromGroup(groupId, bob.id)
            delay(1_000)
            assertFalse(b.groups.value.any { it.id == groupId }, "Bob 应被移出群")
            assertTrue(a.groups.value.firstOrNull { it.id == groupId }?.memberIds?.contains(bob.id) == false, "A 侧 Bob 应从成员列表移除")
        } finally {
            a.stop(); b.stop(); c.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel(); scopeC.coroutineContext[Job]?.cancel()
        }
    }
}
