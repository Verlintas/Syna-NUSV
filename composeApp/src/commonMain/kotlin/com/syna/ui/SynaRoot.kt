package com.syna.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.syna.core.ConnectionMode
import com.syna.net.SynaEngine
import com.syna.ui.screen.ChatScreen
import com.syna.ui.screen.ChatsScreen
import com.syna.ui.screen.ContactsScreen
import com.syna.ui.screen.CreateGroupScreen
import com.syna.ui.screen.SettingsScreen
import com.syna.ui.theme.ThemeMode

private data class Tab(val label: String, val icon: @Composable () -> Unit)

@Composable
fun SynaRoot(
    engine: SynaEngine,
    username: String,
    connectionMode: ConnectionMode,
    themeMode: ThemeMode,
    burnAfterReading: Boolean,
    e2eEnabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBurnAfterReadingChange: (Boolean) -> Unit,
    onE2eEnabledChange: (Boolean) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var chatPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingGroup by rememberSaveable { mutableStateOf(false) }
    val tabs = remember {
        listOf(
            Tab("会话") { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            Tab("联系人") { Icon(Icons.Filled.Person, contentDescription = null) },
            Tab("设置") { Icon(Icons.Filled.Settings, contentDescription = null) },
        )
    }

    val chatTarget = chatPeerId
    if (chatTarget != null) {
        ChatScreen(
            peerId = chatTarget,
            engine = engine,
            onBack = { chatPeerId = null },
        )
        return
    }

    if (creatingGroup) {
        CreateGroupScreen(
            engine = engine,
            onBack = { creatingGroup = false },
            onCreated = { groupId ->
                creatingGroup = false
                chatPeerId = groupId
            },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> ChatsScreen(engine = engine, onOpenChat = { chatPeerId = it }, modifier = Modifier.padding(padding))
            1 -> ContactsScreen(
                engine = engine,
                onOpenChat = { chatPeerId = it },
                onCreateGroup = { creatingGroup = true },
                modifier = Modifier.padding(padding),
            )
            else -> SettingsScreen(
                modifier = Modifier.padding(padding),
                username = username,
                connectionMode = connectionMode,
                themeMode = themeMode,
                burnAfterReading = burnAfterReading,
                e2eEnabled = e2eEnabled,
                onUsernameChange = onUsernameChange,
                onConnectionModeChange = onConnectionModeChange,
                onThemeModeChange = onThemeModeChange,
                onBurnAfterReadingChange = onBurnAfterReadingChange,
                onE2eEnabledChange = onE2eEnabledChange,
            )
        }
    }
}
