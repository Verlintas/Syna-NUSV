package com.syna.net

import com.russhwolf.settings.MapSettings
import com.syna.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EngineLoopbackTest {

    @Test
    fun twoEnginesDiscoverEachOtherAndChat() = runBlocking {
        val settingsA = SettingsRepository(MapSettings())
        settingsA.username = "Alice"
        val settingsB = SettingsRepository(MapSettings())
        settingsB.username = "Bob"

        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val a = SynaEngine(settingsA, scopeA, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)
        val b = SynaEngine(settingsB, scopeB, discoveryIntervalMs = 1_000, peerTimeoutMs = 5_000, sweepIntervalMs = 1_000)

        val received = mutableListOf<TransportFrame>()
        val rawFrames = mutableListOf<TransportFrame>()
        val receiveJob = scopeB.launch {
            b.incoming.collect { event ->
                if (event is IncomingEvent.PeerFrame && event.frame.type == FrameType.TEXT) {
                    received.add(event.frame)
                }
            }
        }
        val rawJob = scopeB.launch {
            b.rawIncoming.collect { frame ->
                if (frame.type == FrameType.TEXT) rawFrames.add(frame)
            }
        }

        try {
            a.start()
            b.start()

            val bobInA = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }
            assertTrue(bobInA.online)
            assertTrue(bobInA.addr.tcpPort > 0)

            val aliceInB = b.peers.first { list -> list.any { it.username == "Alice" && it.online } }
                .first { it.username == "Alice" }
            assertTrue(aliceInB.online)

            // 第一条消息触发 TCP 连接与密钥交换
            a.sendText(bobInA.id, "握手消息")
            val deadline1 = System.currentTimeMillis() + 8_000
            while ((a.peerKeys.value[bobInA.id] == null || b.peerKeys.value[aliceInB.id] == null) && System.currentTimeMillis() < deadline1) {
                delay(200)
            }
            assertTrue(a.peerKeys.value[bobInA.id] != null, "A 应已持有 B 的公钥")
            assertTrue(b.peerKeys.value[aliceInB.id] != null, "B 应已持有 A 的公钥")

            // 打开 B 端会话，使已读回执生效
            b.chatStore.activeConversationId.value = aliceInB.id

            // 密钥就绪后发送的消息应为端到端加密
            a.sendText(bobInA.id, "加密的机密消息")

            val deadline = System.currentTimeMillis() + 8_000
            while (received.size < 2 && System.currentTimeMillis() < deadline) {
                delay(200)
            }

            assertEquals(2, received.size)
            assertEquals("握手消息", received[0].body)
            assertEquals("加密的机密消息", received[1].body)

            assertEquals(2, rawFrames.size)
            val raw = rawFrames[1]
            assertEquals(true, raw.enc, "线路上 TEXT 帧应标记为加密")
            assertNotEquals("加密的机密消息", raw.body, "密文不应是明文")

            // 已读回执：B 收到加密消息后自动回复 READ，A 侧状态应变为已读
            val readDeadline = System.currentTimeMillis() + 8_000
            while (a.chatStore.messages.value[bobInA.id]?.last()?.status != com.syna.chat.MessageStatus.READ &&
                System.currentTimeMillis() < readDeadline
            ) {
                delay(200)
            }
            assertEquals(
                com.syna.chat.MessageStatus.READ,
                a.chatStore.messages.value[bobInA.id]?.last()?.status,
                "A 侧消息应收到已读回执",
            )
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
            receiveJob.cancel()
            rawJob.cancel()
        }
    }
}
