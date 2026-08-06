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
        val receiveJob = scopeB.launch {
            b.incoming.collect { event ->
                if (event is IncomingEvent.PeerFrame && event.frame.type == FrameType.TEXT) {
                    received.add(event.frame)
                }
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

            a.sendText(bobInA.id, "你好 Bob，我是 Alice")
            b.sendText(aliceInB.id, "收到！我是 Bob")

            val deadline = System.currentTimeMillis() + 8_000
            while (received.size < 1 && System.currentTimeMillis() < deadline) {
                delay(200)
            }

            assertEquals("你好 Bob，我是 Alice", received.firstOrNull()?.body)
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
            receiveJob.cancel()
        }
    }
}
