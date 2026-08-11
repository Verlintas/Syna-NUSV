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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syna.shield.ShieldController
import com.syna.shield.ShieldState
import com.syna.shield.ShieldThreat

private val ShieldBlack = Color(0xFF000000)
private val ShieldRed = Color(0xFFFF2D2D)
private val ShieldWhite = Color(0xFFFFFFFF)
private val ShieldWhiteDim = Color(0xFFB8B8B8)

/** ◇Mirtazapine Shield 锁定页：纯黑背景 + 红色◇ + 白色文字 + 白色解锁按钮 */
@Composable
fun ShieldLockScreen(controller: ShieldController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val threats by controller.threats.collectAsState()
    val disabling by controller.disabling.collectAsState()
    val threatDetails by controller.threatDetails.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ShieldBlack)
            // 自我保护：锁定期间拦截全部按键（含 ESC/返回），锁定页仅需触摸/点击交互
            .onPreviewKeyEvent { true }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 红色 ◇ 标识
        Text(
            text = "◇",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = ShieldRed,
        )
        Spacer(Modifier.height(8.dp))
        // 标题：◇ 红 + 文字白
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "◇",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = ShieldRed,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Mirtazapine Shield",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = ShieldWhite,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "检测到威胁，应用已锁定",
            fontSize = 15.sp,
            color = ShieldWhiteDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        // 威胁列表
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            threats.forEach { threat ->
                ThreatRow(threat, detail = threatDetails[threat])
            }
        }
        Spacer(Modifier.height(32.dp))

        // 双重验证（TOTP 第二因子）：生物识别通过后等待动态码输入
        if (state == ShieldState.AWAITING_TOTP) {
            var code by remember { mutableStateOf("") }
            Text(
                text = if (disabling) "生物识别已通过 · 输入动态码以关闭护盾"
                else "生物识别已通过 · 请输入第二因子动态码",
                fontSize = 14.sp,
                color = ShieldWhite,
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = code,
                onValueChange = { input ->
                    code = input.filter { it.isDigit() }.take(6)
                },
                singleLine = true,
                placeholder = { Text("6 位动态码", color = ShieldWhiteDim) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = ShieldBlack,
                    fontSize = 20.sp,
                    letterSpacing = 6.sp,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ShieldRed,
                    unfocusedBorderColor = ShieldWhite.copy(alpha = 0.4f),
                    focusedTextColor = ShieldBlack,
                    unfocusedTextColor = ShieldBlack,
                    cursorColor = ShieldRed,
                    focusedContainerColor = ShieldWhite,
                    unfocusedContainerColor = ShieldWhite,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { controller.verifyTotp(code) },
                enabled = code.length == 6,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ShieldWhite,
                    contentColor = ShieldBlack,
                    disabledContainerColor = ShieldWhite.copy(alpha = 0.3f),
                    disabledContentColor = ShieldBlack.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = "验证并解锁",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (disabling) {
                    "双重验证关闭：攻击者无法关闭已开启的护盾。动态码错误将计入暴力防护。"
                } else {
                    "动态码来自你导入种子的 TOTP 应用（如 Google Authenticator）。错误码将计入暴力防护。"
                },
                fontSize = 12.sp,
                color = ShieldWhiteDim.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        // 白色解锁按钮
        Button(
            onClick = { controller.requestUnlock() },
            enabled = state == ShieldState.LOCKED,
            colors = ButtonDefaults.buttonColors(
                containerColor = ShieldWhite,
                contentColor = ShieldBlack,
                disabledContainerColor = ShieldWhite.copy(alpha = 0.3f),
                disabledContentColor = ShieldBlack.copy(alpha = 0.5f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = ShieldBlack,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "生物识别验证解锁",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "仅在你本人确认威胁安全后可继续使用；解锁需通过系统生物识别（桌面端点击确认）。",
            fontSize = 12.sp,
            color = ShieldWhiteDim.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThreatRow(threat: ShieldThreat, detail: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ShieldWhite.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "◇",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = ShieldRed,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = threat.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ShieldWhite,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = threat.detail,
                fontSize = 12.sp,
                color = ShieldWhiteDim,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    color = ShieldRed.copy(alpha = 0.9f),
                )
            }
        }
    }
}
