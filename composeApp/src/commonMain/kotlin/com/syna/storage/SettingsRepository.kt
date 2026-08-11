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
import com.syna.shield.ShieldConfigGuard
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

    /** 字体大小：0=小 1=中 2=大 */
    var fontSizeLevel: Int
        get() = settings.getInt(KEY_FONT_SIZE, 1)
        set(value) {
            settings.putInt(KEY_FONT_SIZE, value)
        }

    /** 隐身模式：不广播自身（仍可发现他人、可被手动刷新发现） */
    var stealthMode: Boolean
        get() = settings.getBoolean(KEY_STEALTH_MODE, false)
        set(value) {
            settings.putBoolean(KEY_STEALTH_MODE, value)
        }

    /** 仅加密会话：密钥未就绪时拒发明文（防静默降级；v0.9.9 起默认开启） */
    var e2eOnlyEnabled: Boolean
        get() = settings.getBoolean(KEY_E2E_ONLY_ENABLED, true)
        set(value) {
            settings.putBoolean(KEY_E2E_ONLY_ENABLED, value)
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
        get() = readShieldValue(KEY_SHIELD_ENABLED, KEY_SHIELD_ENABLED_SIG, false)
        set(value) {
            writeShieldValue(KEY_SHIELD_ENABLED, KEY_SHIELD_ENABLED_SIG, value)
        }

    var shieldSelfDestruct: Boolean
        get() = readShieldValue(KEY_SHIELD_SELF_DESTRUCT, KEY_SHIELD_SELF_DESTRUCT_SIG, false)
        set(value) {
            writeShieldValue(KEY_SHIELD_SELF_DESTRUCT, KEY_SHIELD_SELF_DESTRUCT_SIG, value)
        }

    var shieldScreenProtection: Boolean
        get() = readShieldValue(KEY_SHIELD_SCREEN_PROTECTION, KEY_SHIELD_SCREEN_PROTECTION_SIG, true)
        set(value) {
            writeShieldValue(KEY_SHIELD_SCREEN_PROTECTION, KEY_SHIELD_SCREEN_PROTECTION_SIG, value)
        }

    var blockedPeerIds: List<String>
        get() {
            val raw = settings.getStringOrNull(KEY_BLOCKED_PEERS)
            return if (raw.isNullOrBlank()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
        }
        set(value) {
            settings.putString(KEY_BLOCKED_PEERS, value.distinct().joinToString("\n"))
        }

    /** 篡改检测结果：由 App 启动时检查 */
    var shieldTampered: Boolean = false
        private set

    private fun readShieldValue(key: String, sigKey: String, default: Boolean): Boolean {
        val raw = settings.getStringOrNull(key)
        if (raw == null) {
            // 无记录（首次运行）
            return default
        }
        val sig = settings.getStringOrNull(sigKey) ?: ""
        val valid = ShieldConfigGuard.verify(raw, sig)
        if (!valid) {
            // 设置被篡改或密钥被清除：强制启用保护
            shieldTampered = true
            return if (key == KEY_SHIELD_SCREEN_PROTECTION) true else default || key == KEY_SHIELD_ENABLED
        }
        return raw == "true"
    }

    private fun writeShieldValue(key: String, sigKey: String, value: Boolean) {
        val raw = value.toString()
        settings.putString(key, raw)
        settings.putString(sigKey, ShieldConfigGuard.sign(raw))
    }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_CONNECTION_MODE = "connection_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_E2E_ENABLED = "e2e_enabled"
        const val KEY_E2E_ONLY_ENABLED = "e2e_only_enabled"
        const val KEY_STEALTH_MODE = "stealth_mode"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_BURN_ENABLED = "burn_after_reading"
        const val KEY_TEMP_CHAT_ENABLED = "temp_chat_enabled"
        const val KEY_TEMP_CHAT_TTL_HOURS = "temp_chat_ttl_hours"
        const val KEY_BLOCKED_PEERS = "blocked_peers"
        const val KEY_SHIELD_ENABLED = "shield_enabled"
        const val KEY_SHIELD_ENABLED_SIG = "shield_enabled_sig"
        const val KEY_SHIELD_SCREEN_PROTECTION = "shield_screen_protection"
        const val KEY_SHIELD_SCREEN_PROTECTION_SIG = "shield_screen_protection_sig"
        const val KEY_SHIELD_SELF_DESTRUCT = "shield_self_destruct"
        const val KEY_SHIELD_SELF_DESTRUCT_SIG = "shield_self_destruct_sig"
    }
}
