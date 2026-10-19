package io.github.thiesi.chitchat

import android.app.Application
import io.github.thiesi.chitchat.core.settings.SettingsRepository
import io.github.thiesi.chitchat.core.session.KeystoreSessionVault
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionRepository

class ChitChatApplication : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessions: SessionController by lazy { SessionRepository(KeystoreSessionVault(this)) }
}
