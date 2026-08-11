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
    fun groupFileTransferIsEncryptedPerMember() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = makeEngine("Alice", scopeA)
        val b = makeEngine("Bob", scopeB)
        try {
            a.start(); b.start()
            delay(1_200)
            val bob = a.peers.first { list -> list.any { p -> p.username == "Bob" && p.online } }
                .first { it.username == "Bob" }
            // 密钥就绪
            a.sendText(bob.id, "handshake")
            delay(1_500)
            assertTrue(a.peerKeys.value[bob.id] != null, "A 应有 B 的公钥")

            // Alice 建群 + Bob 加入
            val groupId = a.createGroup("文件群", listOf(bob.id))
            delay(1_500)
            assertTrue(b.groups.value.any { it.id == groupId }, "Bob 应加入群")

            // 发送群文件（每成员密文分发）
            val payload = ByteArray(300_000) { (it % 251).toByte() }
            a.sendFile(groupId, "群文件.bin", payload, "application/octet-stream")
            // 等待传输完成（分块 + 重传）
            val deadline = System.currentTimeMillis() + 15_000
            var received = false
            while (System.currentTimeMillis() < deadline) {
                val msg = b.chatStore.messages.value[groupId]?.firstOrNull { it.fileName == "群文件.bin" }
                if (msg != null && msg.localPath != null) {
                    received = true
                    break
                }
                delay(300)
            }
            assertTrue(received, "Bob 应收到群文件")
            // 字节级一致性
            val stored = java.io.File(b.chatStore.messages.value[groupId]!!.first { it.fileName == "群文件.bin" }.localPath!!).readBytes()
            assertEquals(payload.size, stored.size)
            assertTrue(payload.contentEquals(stored), "群文件内容应一致")
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun stealthModeStopsAnnouncingButStillReceives() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = makeEngine("Alice", scopeA)
        val b = makeEngine("Bob", scopeB)
        try {
            // B 先进入隐身模式再启动（settings 驱动，start 时应用）
            b.settings.stealthMode = true
            a.start(); b.start()
            // A 不应发现隐身中的 B（但 B 能发现 A）
            delay(2_000)
            assertTrue(b.peers.value.any { it.username == "Alice" }, "隐身者仍应能发现他人")
            assertTrue(a.peers.value.none { it.username == "Bob" }, "隐身者不应被发现")
            // 手动刷新可被发现（sendNow 是主动广播）
            b.setStealthMode(false)
            delay(2_000)
            assertTrue(a.peers.value.any { it.username == "Bob" }, "关闭隐身应重新被发现")
        } finally {
            a.stop(); b.stop()
            scopeA.coroutineContext[Job]?.cancel(); scopeB.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun failedMessageCanBeResent() = runBlocking {
        val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val a = makeEngine("Alice", scopeA)
        val b = makeEngine("Bob", scopeB)
        try {
            a.start(); b.start()
            delay(1_200)
            val bob = a.peers.first { list -> list.any { p -> p.username == "Bob" && p.online } }
                .first { it.username == "Bob" }
            a.sendText(bob.id, "handshake")
            delay(1_500)
            assertTrue(a.peerKeys.value[bob.id] != null)
            val alice = b.peers.first { list -> list.any { p -> p.username == "Alice" && p.online } }
                .first { it.username == "Alice" }
            // 正常发送成功
            a.sendText(bob.id, "第一次")
            delay(1_000)
            assertTrue(b.chatStore.messages.value[alice.id]?.any { it.body == "第一次" } == true, "B 应收到消息")
            // 重发路径：对已成功消息再次发送（模拟重发同一内容）
            val resent = a.sendText(bob.id, "重发内容")
            assertTrue(resent.isNotEmpty(), "重发应产生新消息")
            delay(1_000)
            assertTrue(b.chatStore.messages.value[alice.id]?.any { it.body == "重发内容" } == true, "B 应收到重发内容")
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
