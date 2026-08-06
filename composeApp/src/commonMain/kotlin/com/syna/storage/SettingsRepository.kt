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

    var burnAfterReadingEnabled: Boolean
        get() = settings.getBoolean(KEY_BURN_ENABLED, false)
        set(value) {
            settings.putBoolean(KEY_BURN_ENABLED, value)
        }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_CONNECTION_MODE = "connection_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_BURN_ENABLED = "burn_after_reading"
    }
}
