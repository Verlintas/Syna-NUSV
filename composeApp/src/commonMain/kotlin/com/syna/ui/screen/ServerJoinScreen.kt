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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syna.net.ServerState
import com.syna.net.SynaEngine
import kotlinx.coroutines.launch

@Composable
fun ServerJoinScreen(
    engine: SynaEngine,
    onBack: () -> Unit,
    onJoined: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val serverState by engine.serverState.collectAsState()
    val serverError by engine.serverError.collectAsState()
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("45880") }
    var password by remember { mutableStateOf("") }
    var joined by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "←",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text("加入服务器", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "输入服务器地址（局域网 IP、公网 IP 或内网穿透域名均可）与密码，即可加入服务器群聊。消息经密码加密通道传输，并由服务器持久化，离线成员上线后自动补拉历史。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("服务器地址 (IP 或域名)") },
            placeholder = { Text("例如 192.168.1.100 或 frp.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("服务器密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val hostTrim = host.trim()
                val portNum = port.toIntOrNull()
                if (hostTrim.isNotEmpty() && portNum != null && password.isNotEmpty()) {
                    scope.launch {
                        val result = engine.joinServer(hostTrim, portNum, password)
                        result.onSuccess { groupId ->
                            joined = true
                            onJoined(groupId)
                        }
                    }
                }
            },
            enabled = serverState != ServerState.CONNECTING && !joined,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (serverState) {
                    ServerState.CONNECTING -> "正在连接…"
                    ServerState.CONNECTED -> "已连接"
                    else -> "加入服务器"
                },
            )
        }

        when (serverState) {
            ServerState.CONNECTED -> Text(
                "✅ 已加入服务器，正在打开群聊…",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            ServerState.ERROR -> Text(
                "❌ 连接失败：${serverError ?: "未知错误"}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            else -> Unit
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("内网穿透提示", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "服务器在路由器后时，用 frp / ngrok / Tailscale 等工具将服务器端口映射到公网，然后把映射后的地址填入上方即可远程加入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
