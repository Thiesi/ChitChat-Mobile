package io.github.thiesi.chitchat

import android.app.Application
import io.github.thiesi.chitchat.core.settings.SettingsRepository
import io.github.thiesi.chitchat.core.session.KeystoreSessionVault
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.SessionAccountRepository
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.rooms.SessionRoomRepository

class ChitChatApplication : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    private val sessionRepository by lazy { SessionRepository(KeystoreSessionVault(this)) }
    val sessions: SessionController get() = sessionRepository
    val accounts: AccountOperations by lazy { SessionAccountRepository(sessionRepository) }
    val rooms: RoomOperations by lazy { SessionRoomRepository(sessionRepository) }
}
