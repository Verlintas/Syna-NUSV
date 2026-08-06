package com.syna

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.syna.net.SynaEngine
import com.syna.storage.SettingsRepository
import com.syna.ui.SynaRoot
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
        )
    }
}
