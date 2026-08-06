package com.syna.net

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
import kotlin.test.assertTrue

class GroupMeshTest {

    private fun newEngine(name: String, scope: CoroutineScope): SynaEngine {
        val settings = SettingsRepository(MapSettings())
        settings.username = name
        return SynaEngine(settings, scope, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
    }

    @Test
    fun threeEnginesGroupChat() = runBlocking {
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

            // 等待全网互相发现
            val deadline = System.currentTimeMillis() + 15_000
            val allPeersOnline: (SynaEngine) -> Boolean = { e ->
                val names = e.peers.value.filter { it.online }.map { it.username }
                listOf("Alice", "Bob", "Carol").filter { it != e.username }.all { it in names }
            }
            while (System.currentTimeMillis() < deadline && !(allPeersOnline(a) && allPeersOnline(b) && allPeersOnline(c))) {
                delay(300)
            }
            assertTrue(allPeersOnline(a), "A 应发现 B 和 C")
            assertTrue(allPeersOnline(b), "B 应发现 A 和 C")

            // 触发 TCP 密钥交换（A→B, A→C, B→C）
            val bob = a.peers.value.first { it.username == "Bob" }
            val carol = a.peers.value.first { it.username == "Carol" }
            val aliceForB = b.peers.value.first { it.username == "Alice" }
            val carolForB = b.peers.value.first { it.username == "Carol" }
            a.sendText(bob.id, "handshake-a-b")
            a.sendText(carol.id, "handshake-a-c")
            b.sendText(carolForB.id, "handshake-b-c")

            val keyDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < keyDeadline) {
                val aKeys = a.peerKeys.value.keys
                val bKeys = b.peerKeys.value.keys
                val cKeys = c.peerKeys.value.keys
                if (aKeys.containsAll(setOf(bob.id, carol.id)) &&
                    bKeys.containsAll(setOf(aliceForB.id, carolForB.id)) &&
                    cKeys.containsAll(a.peers.value.filter { it.username != "Carol" }.map { it.id })
                ) {
                    break
                }
                delay(200)
            }

            // A 创建群聊（B + C）
            val groupId = a.createGroup("周末爬山群", listOf(bob.id, carol.id))

            // 等待 B、C 收到邀请并加入
            val groupDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < groupDeadline) {
                val gB = b.groups.value.firstOrNull { it.id == groupId }
                val gC = c.groups.value.firstOrNull { it.id == groupId }
                if (gB != null && gC != null && gB.memberIds.containsAll(listOf(a.userId, bob.id, carol.id))) break
                delay(200)
            }
            val groupB = b.groups.value.firstOrNull { it.id == groupId }
            val groupC = c.groups.value.firstOrNull { it.id == groupId }
            assertTrue(groupB != null, "B 应收到群邀请")
            assertTrue(groupC != null, "C 应收到群邀请")
            assertEquals("周末爬山群", groupB?.name)
            assertEquals(3, groupB?.memberIds?.size)

            // A 发送群消息，B 和 C 都应收到并成功解密
            b.chatStore.activeConversationId.value = groupId
            c.chatStore.activeConversationId.value = groupId
            a.sendGroupText(groupId, "大家好，我是群主 Alice")

            val msgDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < msgDeadline) {
                val bHas = b.chatStore.messages.value[groupId]?.any { it.body == "大家好，我是群主 Alice" } == true
                val cHas = c.chatStore.messages.value[groupId]?.any { it.body == "大家好，我是群主 Alice" } == true
                if (bHas && cHas) break
                delay(200)
            }

            val bMsg = b.chatStore.messages.value[groupId]?.firstOrNull { it.body == "大家好，我是群主 Alice" }
            val cMsg = c.chatStore.messages.value[groupId]?.firstOrNull { it.body == "大家好，我是群主 Alice" }
            assertTrue(bMsg != null, "B 应收到群消息")
            assertTrue(cMsg != null, "C 应收到群消息")
            assertEquals(a.userId, bMsg?.senderId)
            assertEquals(true, bMsg?.encrypted, "群消息应加密")
            assertEquals(true, bMsg?.conversationId == groupId)

            // B 回复群消息，A 与 C 收到
            b.sendGroupText(groupId, "收到，Bob 在！")
            val replyDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < replyDeadline) {
                val aHas = a.chatStore.messages.value[groupId]?.any { it.body == "收到，Bob 在！" } == true
                val cHas = c.chatStore.messages.value[groupId]?.any { it.body == "收到，Bob 在！" } == true
                if (aHas && cHas) break
                delay(200)
            }
            assertTrue(
                a.chatStore.messages.value[groupId]?.any { it.body == "收到，Bob 在！" } == true,
                "A 应收到 Bob 的群回复",
            )
            assertTrue(
                c.chatStore.messages.value[groupId]?.any { it.body == "收到，Bob 在！" } == true,
                "C 应收到 Bob 的群回复",
            )
        } finally {
            a.stop()
            b.stop()
            c.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
            scopeC.coroutineContext[Job]?.cancel()
        }
    }
}
