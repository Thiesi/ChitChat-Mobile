package io.github.thiesi.chitchat

import android.app.Application
import io.github.thiesi.chitchat.core.settings.SettingsRepository

class ChitChatApplication : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
