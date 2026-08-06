package com.syna

import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.SupervisorJob

@Composable
fun App() {
    val settings = remember { SettingsRepository() }
    val engine = remember {
        SynaEngine(
            settings = settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    LaunchedEffect(Unit) {
        engine.start()
    }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var connectionMode by remember { mutableStateOf(settings.connectionMode) }
    var username by remember { mutableStateOf(settings.username) }
    var burnAfterReading by remember { mutableStateOf(settings.burnAfterReadingEnabled) }

    SynaTheme(themeMode = themeMode) {
        SynaRoot(
            engine = engine,
            username = username,
            connectionMode = connectionMode,
            themeMode = themeMode,
            burnAfterReading = burnAfterReading,
            onUsernameChange = {
                username = it
                settings.username = it
                engine.refreshUsername()
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
        )
    }
}
