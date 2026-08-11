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
package com.syna

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.syna.net.SynaEngine
import com.syna.shield.ShieldController
import com.syna.shield.clearNotifications
import com.syna.shield.clearOwnClipboard
import com.syna.shield.createShieldEngine
import com.syna.shield.requestUsageAccessPermission
import com.syna.storage.SettingsRepository
import com.syna.storage.clearReceivedFiles
import com.syna.util.notifyMessage
import com.syna.ui.SynaRoot
import com.syna.ui.screen.ShieldLockScreen
import com.syna.ui.theme.SynaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

@Composable
fun App() {
    val settings = remember { SettingsRepository() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val engine = remember {
        SynaEngine(
            settings = settings,
            scope = scope,
        )
    }
    // Mirtazapine Shield：实时安全监测 + 应用锁
    val shield = remember {
        ShieldController(enabled = settings.shieldEnabled)
    }
    val shieldState by shield.state.collectAsState()
    val shieldHealth by shield.health.collectAsState()
    val shieldEvents by shield.events.collectAsState()
    val shieldHoneypot by shield.honeypot.collectAsState()
    val shieldTotpEnabled by shield.totpEnabled.collectAsState()
    LaunchedEffect(Unit) {
        shield.start()
        // 启动全面权限自检：确保护盾所需权限全部就绪
        if (settings.shieldEnabled) {
            // 使用情况访问（前台感知增强）：未授权则引导开启（跳转已定位本应用）
            if (!com.syna.shield.shieldUsageAccessGranted()) {
                requestUsageAccessPermission()
            }
        }
        // 自我保护：启动时检测安全设置是否被篡改/存储被清除
        if (settings.shieldTampered) {
            shield.reportThreat(com.syna.shield.ShieldThreat.SHIELD_TAMPERED)
        }
        if (settings.shieldEnabled) {
            // 一键全开：启用即同步所有防护
            settings.shieldScreenProtection = true
            settings.shieldSelfDestruct = true
        }
        // 重装/恢复备份引导：设备身份变化且护盾关闭 → 提示开启（自保：重装不能静默关掉防护）
        try {
            val identityChanged = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.syna.storage.deviceIdentityChanged()
            }
            if (identityChanged && !settings.shieldEnabled) {
                com.syna.util.notifyMessage(
                    "Syna",
                    "检测到设备身份变化（可能是重新安装或恢复备份）。建议开启 ◇Mirtazapine Shield 以保护本地数据。",
                )
            }
        } catch (e: Exception) {
        }
        shield.setSecureScreen(settings.shieldScreenProtection)
        shield.setMemoryWipeCallbacks(
            wipe = { engine.chatStore.releaseMemory() },
            restore = { engine.chatStore.reloadFromPersistence() },
        )
        // 假锁主动污染：注入类威胁触发假锁时写入诱饵消息——
        // 攻击者最终解锁后面对的是被污染的数据流，无法区分真实记录与诱饵
        // 安全约束：内存为空（锁定已释放）时只写内存不重写磁盘，防覆盖真实历史
        shield.setHoneypotCallback {
            val decoyText = listOf(
                "[系统日志] 检测到异常访问，记录已归档",
                "[安全提示] 本次会话已被标记审查",
                "备份片段 #${(0..9999).random()}",
                "[内部] 密钥轮换指令已下发",
            ).random()
            try {
                engine.chatStore.addOutgoing(
                    peerId = engine.userId,
                    peerName = "系统",
                    msg = com.syna.chat.ChatMessage(
                        id = "decoy-${System.currentTimeMillis()}",
                        conversationId = engine.userId,
                        senderId = "system",
                        body = decoyText,
                        ts = System.currentTimeMillis(),
                        status = com.syna.chat.MessageStatus.READ,
                        burnAfterReading = false,
                        encrypted = false,
                        kind = com.syna.chat.MessageKind.TEXT,
                    ),
                )
                // 仅当内存还有真实消息时才重写磁盘（追加诱饵）；
                // 内存已被清空时跳过——防"仅诱饵"的全量重写覆盖真实历史
                if (engine.chatStore.hasMessagesInMemory()) {
                    engine.chatStore.rewriteNow()
                }
            } catch (e: Exception) {
            }
        }
        // 会话密钥轮换（前向安全）：解锁后换新密钥并全量迁移，旧密钥失效。
        // 时序：此回调在 setState(UNLOCKED) 之后触发（门禁已放行）——
        // 先恢复内存数据（解密旧密钥加密的盘上记录），再轮换+重写迁移
        shield.setSessionRotateCallback {
            engine.chatStore.reloadFromPersistence()
            com.syna.shield.SessionKeyStore.rotateSessionKey()
            engine.chatStore.rewriteNow()
            com.syna.shield.SessionKeyStore.clearMigration()
        }
        shield.configureSelfDestruct(enabled = settings.shieldSelfDestruct) {
            fullDestruct(engine, shield)
        }
    }
    LaunchedEffect(Unit) {
        engine.start()
        // 隐身模式：启动即应用（不广播自身）
        engine.setStealthMode(settings.stealthMode)
    }
    // 页面销毁（如 Android 旋转）时彻底释放引擎与护盾控制器，
    // 避免重复实例抢占端口/泄漏看门狗线程
    DisposableEffect(Unit) {
        onDispose {
            engine.stop()
            shield.stop()
            scope.coroutineContext[Job]?.cancel()
        }
    }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var connectionMode by remember { mutableStateOf(settings.connectionMode) }
    var username by remember { mutableStateOf(settings.username) }
    // 防抖：停止输入 1.2s 后再重启发现服务，避免每个按键都重建 socket
    // 仅用户名实际变化时重建 discovery（防 Android 旋转无条件重启发现服务）
    val prevUsername = remember { username }
    LaunchedEffect(username) {
        if (username != prevUsername) {
            delay(1_200)
            engine.refreshUsername()
        }
    }
    var e2eEnabled by remember { mutableStateOf(settings.e2eEnabled) }
    var e2eOnlyEnabled by remember { mutableStateOf(settings.e2eOnlyEnabled) }
    var stealthMode by remember { mutableStateOf(settings.stealthMode) }
    var burnAfterReading by remember { mutableStateOf(settings.burnAfterReadingEnabled) }
    var tempChatEnabled by remember { mutableStateOf(settings.tempChatEnabled) }
    var tempChatTtlHours by remember { mutableStateOf(settings.tempChatTtlHours) }

    // 自我保护：锁定 → 释放内存消息；解锁 → 从加密存储重载
    LaunchedEffect(shieldState) {
        when (shieldState) {
            com.syna.shield.ShieldState.LOCKED -> engine.chatStore.releaseMemory()
            com.syna.shield.ShieldState.UNLOCKED -> engine.chatStore.reloadFromPersistence()
            else -> Unit
        }
    }

    SynaTheme(themeMode = themeMode) {
        if (shieldState == com.syna.shield.ShieldState.LOCKED ||
            shieldState == com.syna.shield.ShieldState.AWAITING_TOTP
        ) {
            ShieldLockScreen(controller = shield)
            return@SynaTheme
        }
        SynaRoot(
            shieldHealth = shieldHealth,
            shieldEvents = shieldEvents,
            shieldHoneypot = shieldHoneypot,
            shieldTotpEnabled = shieldTotpEnabled,
            onShieldEnableTotp = { shield.enableTotp() },
            onShieldDisableTotp = { shield.disableTotp() },
            engine = engine,
            username = username,
            connectionMode = connectionMode,
            themeMode = themeMode,
            burnAfterReading = burnAfterReading,
            e2eEnabled = e2eEnabled,
            e2eOnlyEnabled = e2eOnlyEnabled,
            tempChatEnabled = tempChatEnabled,
            tempChatTtlHours = tempChatTtlHours,
            onUsernameChange = {
                username = it
                settings.username = it
            },
            onConnectionModeChange = {
                connectionMode = it
                settings.connectionMode = it
            },
            onThemeModeChange = {
                themeMode = it
                settings.themeMode = it
            },
            onBurnAfterReadingChange = {
                burnAfterReading = it
                settings.burnAfterReadingEnabled = it
            },
            onE2eEnabledChange = {
                e2eEnabled = it
                settings.e2eEnabled = it
            },
            onE2eOnlyEnabledChange = {
                e2eOnlyEnabled = it
                settings.e2eOnlyEnabled = it
            },
            stealthMode = stealthMode,
            onStealthModeChange = {
                stealthMode = it
                settings.stealthMode = it
                engine.setStealthMode(it)
            },
            onTempChatEnabledChange = {
                tempChatEnabled = it
                settings.tempChatEnabled = it
            },
            onTempChatTtlChange = {
                tempChatTtlHours = it
                settings.tempChatTtlHours = it
            },
            shieldEnabled = shield.enabled.collectAsState().value,
            onShieldEnabledChange = { on ->
                if (on) {
                    // 一键全开：Shield + 防截屏 + 自毁协议 全部启用
                    shield.setEnabled(true)
                    settings.shieldEnabled = true
                    settings.shieldScreenProtection = true
                    settings.shieldSelfDestruct = true
                    shield.setSecureScreen(true)
                    shield.configureSelfDestruct(enabled = true) {
                        fullDestruct(engine, shield)
                    }
                    // 引导授权使用情况访问（前台应用感知增强）
                    requestUsageAccessPermission()
                } else {
                    // 关闭：双因子验证（生物识别 + 若开启 2FA 则动态码）——
                    // 已开启的护盾无法被攻击者关闭
                    shield.requestDisableWithVerification {
                        settings.shieldEnabled = false
                        settings.shieldScreenProtection = false
                        settings.shieldSelfDestruct = false
                        shield.setSecureScreen(false)
                    }
                }
            },
        )
    }

}

/**
 * 全面自毁（防数据恢复增强）：
 * 1. 内存消息清空；2. 聊天记录/接收文件/审计/种子/blob/基准文件覆写删除；
 * 3. 销毁存储密钥（Android Keystore TEE 内删除——残留密文永久不可解）；
 * 4. 清剪贴板/通知/崩溃日志。
 * 顺序：审计事件先由 controller 写入，最后覆写审计文件本身。
 */
private fun fullDestruct(
    engine: com.syna.net.SynaEngine,
    shield: com.syna.shield.ShieldController,
) {
    try {
        engine.chatStore.clearAllHistory()
        clearReceivedFiles()
        clearOwnClipboard()
        clearNotifications()
        // 覆写删除审计日志（含失败计数）——自毁不留审计痕迹给攻击者
        try {
            com.syna.util.SecureWipe.wipeFile(com.syna.shield.shieldEventsPath())
            com.syna.util.SecureWipe.wipeFile(com.syna.shield.shieldEventsPath() + ".fails")
        } catch (e: Exception) {
        }
        // 平台附加痕迹：TOTP 种子/会话 blob/基准文件/崩溃日志（覆写删除）
        try {
            com.syna.storage.destructPlatformArtifacts()
        } catch (e: Exception) {
        }
        // 销毁存储密钥（最强手段：TEE 内删除，残留密文永久不可解）
        com.syna.shield.ShieldStorageKey.wipe()
        // 释放内存中的会话密钥
        com.syna.shield.SessionKeyStore.invalidateSession()
        // 停用护盾：防止自毁后审计事件/新密钥"复活"（wipe 后 Keystore 会静默重建新密钥）
        shield.setEnabled(false)
        notifyMessage("◇Mirtazapine Shield", "Detected possible compromise; local chats, keys and audit records were destroyed")
    } catch (e: Exception) {
    }
}