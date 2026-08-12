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
package com.syna.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.syna.ui.screen.ServerJoinScreen
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
    e2eOnlyEnabled: Boolean,
    tempChatEnabled: Boolean,
    tempChatTtlHours: Int,
    onUsernameChange: (String) -> Unit,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    fontSizeLevel: Int,
    onFontSizeChange: (Int) -> Unit,
    onBurnAfterReadingChange: (Boolean) -> Unit,
    onE2eEnabledChange: (Boolean) -> Unit,
    onE2eOnlyEnabledChange: (Boolean) -> Unit,
    stealthMode: Boolean,
    onStealthModeChange: (Boolean) -> Unit,
    onTempChatEnabledChange: (Boolean) -> Unit,
    onTempChatTtlChange: (Int) -> Unit,
    shieldEnabled: Boolean,
    onShieldEnabledChange: (Boolean) -> Unit,
    shieldWizardSeen: Boolean,
    onShieldWizardSeen: () -> Unit,
    shieldHealth: com.syna.shield.ShieldHealth,
    shieldEvents: List<com.syna.shield.ShieldEvent>,
    shieldHoneypot: Boolean,
    shieldTotpEnabled: Boolean,
    onShieldEnableTotp: () -> String?,
    onShieldDisableTotp: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val conversations by engine.chatStore.conversations.collectAsState()
    val totalUnread = conversations.sumOf { it.unreadCount }
    var chatPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingGroup by rememberSaveable { mutableStateOf(false) }
    var joiningServer by rememberSaveable { mutableStateOf(false) }
    val tabs = remember {
        listOf(
            Tab("会话") { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            Tab("联系人") { Icon(Icons.Filled.Person, contentDescription = null) },
            Tab("设置") { Icon(Icons.Filled.Settings, contentDescription = null) },
        )
    }

    val safePadding = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

    val chatTarget = chatPeerId
    if (chatTarget != null) {
        ChatScreen(
            peerId = chatTarget,
            engine = engine,
            onBack = { chatPeerId = null },
            modifier = safePadding,
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
            modifier = safePadding,
        )
        return
    }

    if (joiningServer) {
        ServerJoinScreen(
            engine = engine,
            onBack = { joiningServer = false },
            onJoined = { groupId ->
                joiningServer = false
                chatPeerId = groupId
            },
            modifier = safePadding,
        )
        return
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            if (index == 0 && totalUnread > 0) {
                                BadgedBox(badge = { Badge { Text(if (totalUnread > 99) "99+" else totalUnread.toString()) } }) {
                                    tab.icon()
                                }
                            } else {
                                tab.icon()
                            }
                        },
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
                onJoinServer = { joiningServer = true },
                modifier = Modifier.padding(padding),
            )
            else -> SettingsScreen(
                modifier = Modifier.padding(padding),
                engine = engine,
                username = username,
                connectionMode = connectionMode,
                themeMode = themeMode,
                burnAfterReading = burnAfterReading,
                e2eEnabled = e2eEnabled,
                e2eOnlyEnabled = e2eOnlyEnabled,
                tempChatEnabled = tempChatEnabled,
                tempChatTtlHours = tempChatTtlHours,
                onUsernameChange = onUsernameChange,
                onConnectionModeChange = onConnectionModeChange,
                onThemeModeChange = onThemeModeChange,
                fontSizeLevel = fontSizeLevel,
                onFontSizeChange = onFontSizeChange,
                onBurnAfterReadingChange = onBurnAfterReadingChange,
                onE2eEnabledChange = onE2eEnabledChange,
                onE2eOnlyEnabledChange = onE2eOnlyEnabledChange,
                stealthMode = stealthMode,
                onStealthModeChange = onStealthModeChange,
                onTempChatEnabledChange = onTempChatEnabledChange,
                onTempChatTtlChange = onTempChatTtlChange,
                shieldEnabled = shieldEnabled,
                onShieldEnabledChange = onShieldEnabledChange,
                shieldWizardSeen = shieldWizardSeen,
                onShieldWizardSeen = onShieldWizardSeen,
                shieldHealth = shieldHealth,
                shieldEvents = shieldEvents,
                shieldHoneypot = shieldHoneypot,
                shieldTotpEnabled = shieldTotpEnabled,
                onShieldEnableTotp = onShieldEnableTotp,
                onShieldDisableTotp = onShieldDisableTotp,
            )
        }
    }
}
