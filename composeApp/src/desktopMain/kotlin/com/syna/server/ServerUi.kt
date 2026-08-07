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
package com.syna.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServerController {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var server: SynaServer? = null
    private var linkJob: kotlinx.coroutines.Job? = null

    val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val members = kotlinx.coroutines.flow.MutableStateFlow<List<com.syna.net.ServerMember>>(emptyList())
    val messageCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val logs = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val boundPort = kotlinx.coroutines.flow.MutableStateFlow(0)
    val addressesText = kotlinx.coroutines.flow.MutableStateFlow("")
    val bannedUsers = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())

    fun start(port: Int, password: String, groupName: String, dataDir: String) {
        if (server != null) return
        val s = SynaServer(port, password, groupName, Path.of(dataDir))
        server = s
        linkJob = scope.launch {
            launch { s.members.collect { members.value = it } }
            launch { s.messageCount.collect { messageCount.value = it } }
            launch { s.logs.collect { logs.value = it } }
            launch { s.isRunning.collect { isRunning.value = it } }
            launch { s.bannedUsers.collect { bannedUsers.value = it } }
        }
        s.start()
        boundPort.value = s.boundPort
        addressesText.value = s.localAddressesText()
    }

    fun stop() {
        server?.stop()
        server = null
        linkJob?.cancel()
        isRunning.value = false
    }

    fun kick(userId: String) = server?.kickUser(userId)

    fun unban(userId: String) = server?.unbanUser(userId)

    fun announce(text: String) = server?.setAnnouncement(text)
}

@Composable
fun ServerUiScreen(controller: ServerController, initialConfig: ServerConfig) {
    val running by controller.isRunning.collectAsState()
    val members by controller.members.collectAsState()
    val messageCount by controller.messageCount.collectAsState()
    val logs by controller.logs.collectAsState()
    val bannedUsers by controller.bannedUsers.collectAsState()
    val boundPort by controller.boundPort.collectAsState()
    val addressesText by controller.addressesText.collectAsState()
    val logListState = rememberLazyListState()

    var port by remember { mutableStateOf(initialConfig.port.toString()) }
    var password by remember { mutableStateOf(initialConfig.password) }
    var groupName by remember { mutableStateOf(initialConfig.groupName) }
    var dataDir by remember { mutableStateOf(initialConfig.dataDir) }
    var announcementInput by remember { mutableStateOf("") }

    // 日志自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.scrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (running) Color(0xFF00A05A) else MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(8.dp))
            Text("Syna 私人聊天服务器", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (running) "运行中 · $boundPort" else "已停止",
                style = MaterialTheme.typography.bodyMedium,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ===== 配置卡片 =====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("服务器配置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("端口") },
                        enabled = !running,
                        modifier = Modifier.width(110.dp),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("连接密码") },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("群名称") },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = dataDir,
                        onValueChange = { dataDir = it },
                        label = { Text("数据目录") },
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    if (running) {
                        OutlinedButton(onClick = { controller.stop() }) {
                            Text("停止服务器")
                        }
                    } else {
                        Button(
                            onClick = {
                                val p = port.toIntOrNull() ?: 45880
                                controller.start(p, password.ifEmpty { "syna" }, groupName.ifEmpty { "Syna 私服" }, dataDir)
                            },
                        ) {
                            Text("启动服务器")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ===== 状态卡片 =====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("连接信息", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                if (running) {
                    Text("局域网访问: $addressesText", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "公网访问: 用 frp / ngrok / Tailscale 将端口 $boundPort 映射到公网，客户端输入穿透后的地址:端口 + 密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("启动服务器后显示访问地址", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Text(
                        "成员 (${members.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "历史消息: $messageCount",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (members.isEmpty()) {
                    Text("暂无成员", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.height(90.dp)) {
                        items(members, key = { it.id }) { member ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00A05A)),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("${member.name} (${member.id.take(8)}…)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(
                                    text = "踢出",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .clickable { controller.kick(member.id) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                // 封禁列表
                val banned = bannedUsers
                if (banned.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("封禁列表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    banned.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(
                                text = "解除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { controller.unban(id) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                // 群公告
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("群公告:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = announcementInput,
                        onValueChange = { announcementInput = it },
                        placeholder = { Text("输入公告内容，回车或点击发布") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (announcementInput.isNotBlank()) {
                            controller.announce(announcementInput)
                            announcementInput = ""
                        }
                    }) { Text("发布") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ===== 日志卡片 =====
        Card(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(14.dp)) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = logListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(8.dp),
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
