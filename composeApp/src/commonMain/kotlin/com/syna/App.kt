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
import com.syna.shield.createShieldEngine
import com.syna.storage.SettingsRepository
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
    LaunchedEffect(Unit) {
        shield.start()
        shield.setSecureScreen(settings.shieldScreenProtection)
    }
    LaunchedEffect(Unit) {
        engine.start()
    }
    // 页面销毁（如 Android 旋转）时彻底释放引擎，避免重复实例抢占端口
    DisposableEffect(Unit) {
        onDispose {
            engine.stop()
            scope.coroutineContext[Job]?.cancel()
        }
    }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var connectionMode by remember { mutableStateOf(settings.connectionMode) }
    var username by remember { mutableStateOf(settings.username) }
    // 防抖：停止输入 1.2s 后再重启发现服务，避免每个按键都重建 socket
    LaunchedEffect(username) {
        delay(1_200)
        engine.refreshUsername()
    }
    var e2eEnabled by remember { mutableStateOf(settings.e2eEnabled) }
    var burnAfterReading by remember { mutableStateOf(settings.burnAfterReadingEnabled) }
    var tempChatEnabled by remember { mutableStateOf(settings.tempChatEnabled) }
    var tempChatTtlHours by remember { mutableStateOf(settings.tempChatTtlHours) }

    SynaTheme(themeMode = themeMode) {
        if (shieldState == com.syna.shield.ShieldState.LOCKED) {
            ShieldLockScreen(controller = shield)
            return@SynaTheme
        }
        SynaRoot(
            engine = engine,
            username = username,
            connectionMode = connectionMode,
            themeMode = themeMode,
            burnAfterReading = burnAfterReading,
            e2eEnabled = e2eEnabled,
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
            onTempChatEnabledChange = {
                tempChatEnabled = it
                settings.tempChatEnabled = it
            },
            onTempChatTtlChange = {
                tempChatTtlHours = it
                settings.tempChatTtlHours = it
            },
            shieldEnabled = shield.enabled.collectAsState().value,
            shieldScreenProtection = settings.shieldScreenProtection,
            onShieldEnabledChange = {
                shield.setEnabled(it)
                settings.shieldEnabled = it
            },
            onShieldScreenProtectionChange = {
                settings.shieldScreenProtection = it
                shield.setSecureScreen(it)
            },
        )
    }
}
