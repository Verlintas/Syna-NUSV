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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BurnAndTempChatTest {

    @Test
    fun burnAfterReadingMessageIsDestroyedOnBothSides() = runBlocking {
        val settingsA = SettingsRepository(MapSettings())
        settingsA.username = "Alice"
        val settingsB = SettingsRepository(MapSettings())
        settingsB.username = "Bob"

        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val a = SynaEngine(settingsA, scopeA, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
        val b = SynaEngine(settingsB, scopeB, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)

        try {
            a.start()
            b.start()

            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            val alice = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }

            // 密钥交换
            a.sendText(bob.id, "handshake")
            val keyDeadline = System.currentTimeMillis() + 8_000
            while ((a.peerKeys.value[bob.id] == null || b.peerKeys.value[alice.id] == null) && System.currentTimeMillis() < keyDeadline) {
                delay(200)
            }

            // B 打开会话，阅后即焚显示 8 秒后双向销毁
            b.chatStore.activeConversationId.value = alice.id
            a.sendText(bob.id, "这条消息阅后即焚 🔥", burn = true)

            // B 应短暂显示
            val showDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < showDeadline) {
                val msg = b.chatStore.messages.value[alice.id]?.firstOrNull { it.burnAfterReading }
                if (msg != null) break
                delay(100)
            }
            assertTrue(
                b.chatStore.messages.value[alice.id]?.any { it.burnAfterReading } == true,
                "B 应先显示阅后即焚消息",
            )

            // 等待销毁（显示 8s + 余量）
            val burnDeadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < burnDeadline) {
                val bGone = b.chatStore.messages.value[alice.id]?.none { it.burnAfterReading } != false
                val aGone = a.chatStore.messages.value[bob.id]?.none { it.burnAfterReading } != false
                if (bGone && aGone) break
                delay(200)
            }

            assertTrue(
                b.chatStore.messages.value[alice.id]?.none { it.burnAfterReading } == true,
                "B 侧阅后即焚消息应已销毁",
            )
            assertTrue(
                a.chatStore.messages.value[bob.id]?.none { it.burnAfterReading } == true,
                "A 侧收到 BURN_ACK 后应销毁本地副本",
            )
            // 会话预览也不应残留焚毁消息体
            val conv = a.chatStore.conversations.value.firstOrNull { it.peerId == bob.id }
            assertTrue(conv == null || conv.lastMessage != "这条消息阅后即焚 🔥")
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun tempChatPurgesConversationsAfterTtl() = runBlocking {
        val settingsA = SettingsRepository(MapSettings())
        settingsA.username = "Alice"
        val settingsB = SettingsRepository(MapSettings())
        settingsB.username = "Bob"

        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val a = SynaEngine(
            settingsA, scopeA,
            discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 500,
            tempChatTtlMsOverride = 1_500,
        )
        val b = SynaEngine(
            settingsB, scopeB,
            discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 500,
            tempChatTtlMsOverride = 1_500,
        )

        try {
            a.start()
            b.start()

            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            val alice = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }

            a.sendText(bob.id, "临时聊天测试")
            val recvDeadline = System.currentTimeMillis() + 8_000
            while (b.chatStore.messages.value[alice.id]?.any { it.body == "临时聊天测试" } != true &&
                System.currentTimeMillis() < recvDeadline
            ) {
                delay(200)
            }
            assertEquals("临时聊天测试", b.chatStore.messages.value[alice.id]?.lastOrNull()?.body)

            // TTL 1.5s 后双方会话应被清除
            val purgeDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < purgeDeadline) {
                val aGone = a.chatStore.conversations.value.none { it.peerId == bob.id }
                val bGone = b.chatStore.conversations.value.none { it.peerId == alice.id }
                if (aGone && bGone) break
                delay(300)
            }
            assertTrue(a.chatStore.conversations.value.none { it.peerId == bob.id }, "A 侧临时会话应被清除")
            assertTrue(b.chatStore.conversations.value.none { it.peerId == alice.id }, "B 侧临时会话应被清除")
            assertNull(b.chatStore.messages.value[alice.id], "B 侧消息应被清空")
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
        }
    }
}
