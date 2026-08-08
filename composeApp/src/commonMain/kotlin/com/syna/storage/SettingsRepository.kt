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
package com.syna.storage

import com.russhwolf.settings.Settings
import com.syna.core.ConnectionMode
import com.syna.ui.theme.ThemeMode

class SettingsRepository(private val settings: Settings = Settings()) {

    var userId: String
        get() = settings.getString(KEY_USER_ID, "")
        set(value) {
            settings.putString(KEY_USER_ID, value)
        }

    var username: String
        get() = settings.getString(KEY_USERNAME, "")
        set(value) {
            settings.putString(KEY_USERNAME, value)
        }

    var connectionMode: ConnectionMode
        get() = ConnectionMode.fromName(settings.getStringOrNull(KEY_CONNECTION_MODE))
        set(value) {
            settings.putString(KEY_CONNECTION_MODE, value.name)
        }

    var themeMode: ThemeMode
        get() = ThemeMode.fromName(settings.getStringOrNull(KEY_THEME_MODE))
        set(value) {
            settings.putString(KEY_THEME_MODE, value.name)
        }

    var e2eEnabled: Boolean
        get() = settings.getBoolean(KEY_E2E_ENABLED, true)
        set(value) {
            settings.putBoolean(KEY_E2E_ENABLED, value)
        }

    var burnAfterReadingEnabled: Boolean
        get() = settings.getBoolean(KEY_BURN_ENABLED, false)
        set(value) {
            settings.putBoolean(KEY_BURN_ENABLED, value)
        }

    var tempChatEnabled: Boolean
        get() = settings.getBoolean(KEY_TEMP_CHAT_ENABLED, false)
        set(value) {
            settings.putBoolean(KEY_TEMP_CHAT_ENABLED, value)
        }

    var tempChatTtlHours: Int
        get() = settings.getInt(KEY_TEMP_CHAT_TTL_HOURS, 24)
        set(value) {
            settings.putInt(KEY_TEMP_CHAT_TTL_HOURS, value)
        }

    var shieldEnabled: Boolean
        get() = settings.getBoolean(KEY_SHIELD_ENABLED, false)
        set(value) {
            settings.putBoolean(KEY_SHIELD_ENABLED, value)
        }

    var shieldScreenProtection: Boolean
        get() = settings.getBoolean(KEY_SHIELD_SCREEN_PROTECTION, true)
        set(value) {
            settings.putBoolean(KEY_SHIELD_SCREEN_PROTECTION, value)
        }

    var blockedPeerIds: List<String>
        get() {
            val raw = settings.getStringOrNull(KEY_BLOCKED_PEERS)
            return if (raw.isNullOrBlank()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
        }
        set(value) {
            settings.putString(KEY_BLOCKED_PEERS, value.distinct().joinToString("\n"))
        }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_CONNECTION_MODE = "connection_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_E2E_ENABLED = "e2e_enabled"
        const val KEY_BURN_ENABLED = "burn_after_reading"
        const val KEY_TEMP_CHAT_ENABLED = "temp_chat_enabled"
        const val KEY_TEMP_CHAT_TTL_HOURS = "temp_chat_ttl_hours"
        const val KEY_BLOCKED_PEERS = "blocked_peers"
        const val KEY_SHIELD_ENABLED = "shield_enabled"
        const val KEY_SHIELD_SCREEN_PROTECTION = "shield_screen_protection"
    }
}
