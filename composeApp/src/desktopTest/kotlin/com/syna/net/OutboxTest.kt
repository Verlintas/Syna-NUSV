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

class OutboxTest {

    @Test
    fun offlineMessageQueuedAndDeliveredOnReconnect() = runBlocking {
        val settingsA = SettingsRepository(MapSettings())
        settingsA.username = "Alice"
        val settingsB = SettingsRepository(MapSettings())
        settingsB.username = "Bob"

        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val a = SynaEngine(settingsA, scopeA, discoveryIntervalMs = 1_000, peerTimeoutMs = 3_000, sweepIntervalMs = 1_000, chatPersistence = null)
        val b = SynaEngine(settingsB, scopeB, discoveryIntervalMs = 1_000, peerTimeoutMs = 3_000, sweepIntervalMs = 1_000, chatPersistence = null)

        try {
            a.start()
            b.start()

            val bob = a.peers.first { list -> list.any { it.username == "Bob" && it.online } }
                .first { it.username == "Bob" }

            // 密钥交换后 B 下线
            a.sendText(bob.id, "handshake")
            val keyDeadline = System.currentTimeMillis() + 8_000
            while (a.peerKeys.value[bob.id] == null && System.currentTimeMillis() < keyDeadline) {
                delay(200)
            }
            b.stop()

            // 等待 A 判定 B 离线
            val offlineDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < offlineDeadline) {
                if (a.peers.value.first { it.id == bob.id }.let { !it.online }) break
                delay(200)
            }
            assertTrue(!a.peers.value.first { it.id == bob.id }.online, "A 应判定 B 离线")

            // 离线发送 → 进入待发送队列
            a.sendText(bob.id, "离线期间的消息")
            assertEquals(1, a.outbox.value[bob.id]?.size, "消息应进入待发送队列")

            // B 重启（同一身份）
            val b2 = SynaEngine(settingsB, scopeB, discoveryIntervalMs = 1_000, peerTimeoutMs = 3_000, sweepIntervalMs = 1_000, chatPersistence = null)
            b2.start()

            // A 发现 B 重新上线 → 自动补发
            val deliverDeadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deliverDeadline) {
                val received = b2.chatStore.messages.value[a.userId]?.any { it.body == "离线期间的消息" } == true
                val flushed = (a.outbox.value[bob.id]?.size ?: 0) == 0
                if (received && flushed) break
                delay(300)
            }

            assertEquals(
                "离线期间的消息",
                b2.chatStore.messages.value[a.userId]?.firstOrNull { it.body == "离线期间的消息" }?.body,
                "B 重启后应收到达离线消息",
            )
            assertEquals(0, a.outbox.value[bob.id]?.size ?: 0, "待发送队列应清空")

            b2.stop()
        } finally {
            a.stop()
            b.stop()
            scopeA.coroutineContext[Job]?.cancel()
            scopeB.coroutineContext[Job]?.cancel()
        }
    }
}
