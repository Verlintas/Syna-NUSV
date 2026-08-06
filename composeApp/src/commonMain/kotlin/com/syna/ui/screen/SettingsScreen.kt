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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syna.core.ConnectionMode
import com.syna.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    username: String,
    connectionMode: ConnectionMode,
    themeMode: ThemeMode,
    burnAfterReading: Boolean,
    onUsernameChange: (String) -> Unit,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBurnAfterReadingChange: (Boolean) -> Unit,
    onE2eEnabledChange: (Boolean) -> Unit,
    e2eEnabled: Boolean,
) {
    Column(
        modifier = modifier
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
        Text("临时聊天将在后续版本启用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
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
