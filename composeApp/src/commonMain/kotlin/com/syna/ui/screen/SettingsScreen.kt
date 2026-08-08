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
package com.syna.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syna.core.ConnectionMode
import com.syna.net.SynaEngine
import com.syna.ui.MaxWidthContainer
import com.syna.ui.theme.ThemeMode
import com.syna.util.PermissionState
import com.syna.util.notificationPermissionState
import com.syna.storage.clearReceivedFiles
import com.syna.storage.receivedFilesSize
import com.syna.util.rememberNotificationPermissionRequester

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    engine: SynaEngine,
    username: String,
    connectionMode: ConnectionMode,
    themeMode: ThemeMode,
    burnAfterReading: Boolean,
    onUsernameChange: (String) -> Unit,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBurnAfterReadingChange: (Boolean) -> Unit,
    onE2eEnabledChange: (Boolean) -> Unit,
    onTempChatEnabledChange: (Boolean) -> Unit,
    onTempChatTtlChange: (Int) -> Unit,
    e2eEnabled: Boolean,
    tempChatEnabled: Boolean,
    tempChatTtlHours: Int,
) {
    MaxWidthContainer(modifier = modifier) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        SectionTitle("账户")
        OutlinedTextField(
            value = username,
            onValueChange = { onUsernameChange(it) },
            label = { Text("自定义用户名") },
            placeholder = { Text("输入显示给局域网其他用户的名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        SectionTitle("连接方式")
        ConnectionMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConnectionModeChange(mode) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (mode == connectionMode) "● " else "○ ",
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            "自动模式：默认使用 TCP 可靠连接，UDP 用于快速发现和低延迟传输。主机热点：当其他设备接入你开启的热点时使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        SectionTitle("外观")
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThemeModeChange(mode) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (mode == themeMode) "● " else "○ ",
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    when (mode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "明亮模式"
                        ThemeMode.DARK -> "暗黑模式"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        SectionTitle("增强防护")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onBurnAfterReadingChange(!burnAfterReading) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("阅后即焚（默认）", style = MaterialTheme.typography.bodyLarge)
                Text("新发送的消息默认阅后即焚，阅读一次后自动销毁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = burnAfterReading, onCheckedChange = onBurnAfterReadingChange)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onE2eEnabledChange(!e2eEnabled) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("端到端加密", style = MaterialTheme.typography.bodyLarge)
                Text("X25519 密钥交换 + AES-256-GCM，密钥仅存于本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = e2eEnabled, onCheckedChange = onE2eEnabledChange)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTempChatEnabledChange(!tempChatEnabled) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("临时聊天", style = MaterialTheme.typography.bodyLarge)
                Text("会话在无活动 TTL 后自动清除，双方记录同时销毁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = tempChatEnabled, onCheckedChange = onTempChatEnabledChange)
        }
        if (tempChatEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("清除周期", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                listOf(1, 24, 168).forEach { hours ->
                    Text(
                        text = if (tempChatTtlHours == hours) "● " else "○ ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tempChatTtlHours == hours) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when (hours) {
                            1 -> "1小时"
                            24 -> "24小时"
                            else -> "7天"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable { onTempChatTtlChange(hours) }
                            .padding(end = 14.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("临时聊天关闭时，会话记录将保留在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        SectionTitle("存储")
        val historySize = engine.chatStore.historyFileSize()
        val filesSize = receivedFilesSize()
        var confirmClear by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "聊天记录: ${formatSize(historySize)} · 接收文件: ${formatSize(filesSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "清除后本地聊天记录与接收的文件将被删除（不影响对方）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmClear = true }) {
                Text("清除本地记录", color = MaterialTheme.colorScheme.error)
            }
        }
        if (confirmClear) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清除本地记录") },
                text = { Text("将删除全部本地聊天记录与接收的文件（约 ${formatSize(historySize + filesSize)}），此操作不可恢复。确定继续？") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirmClear = false
                        engine.chatStore.clearAllHistory()
                        clearReceivedFiles()
                    }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("取消") }
                },
            )
        }
        Spacer(Modifier.height(16.dp))

        SectionTitle("权限")
        val permState = notificationPermissionState()
        val requestPermission = rememberNotificationPermissionRequester()
        when (permState) {
            PermissionState.NOT_APPLICABLE -> Text(
                "桌面端无需运行时权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PermissionState.GRANTED -> Text(
                "✅ 通知权限已授权",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            PermissionState.DENIED -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⚠️ 通知权限未授予，新消息将无法在通知栏提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { requestPermission() }) {
                    Text("重新请求")
                }
            }
        }
        Text(
            "若首次请求被拒绝，可在此手动重新触发系统权限弹窗；也可在系统设置中为 Syna 开启通知",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        SectionTitle("已屏蔽的联系人")
        val blocked by engine.blockedContacts.collectAsState()
        if (blocked.isEmpty()) {
            Text(
                "暂无（在联系人页点 ✕ 删除联系人后会出现在这里，可随时解除屏蔽）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            blocked.forEach { id ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = id,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { engine.unblockContact(id) }) {
                        Text("解除屏蔽")
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    bytes >= 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "$bytes B"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
