package com.syna.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syna.shield.ShieldController
import com.syna.shield.ShieldState

/** Mirtazapine Shield 锁定页：威胁消除前不可进入应用 */
@Composable
fun ShieldLockScreen(controller: ShieldController, modifier: Modifier = Modifier) {
    // 自我保护：桌面端拦截 ESC/返回键，防止绕过锁定页（Android 端由 MainActivity 拦截系统返回键）
    val state by controller.state.collectAsState()
    val threats by controller.threats.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A0E0E))
            .onPreviewKeyEvent {
                // 锁定期间拦截全部按键（含 ESC/返回），锁定页仅需触摸/点击交互
                true
            }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 盾牌标识
        Column(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Color(0xFF8B1E1E).copy(alpha = 0.35f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🛡", style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Mirtazapine Shield 检测到威胁",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "为保护你的会话安全，应用已锁定。请确认威胁后解锁。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        // 威胁列表
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            threats.forEach { threat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⚠️", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            threat.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                        Text(
                            threat.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { controller.requestUnlock() },
            enabled = state == ShieldState.LOCKED,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("生物识别验证解锁")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "仅在你本人确认威胁安全后可继续使用；解锁需通过系统生物识别（桌面端点击确认）。",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
    }
}
