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
import io.github.thiesi.chitchat.core.conversation.ConversationOperations
import io.github.thiesi.chitchat.core.conversation.SessionConversationRepository
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.thiesi.chitchat.core.realtime.AndroidRealtimeRuntime
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.realtime.RealtimeCoordinator
import io.github.thiesi.chitchat.core.realtime.SessionRealtimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ChitChatApplication : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    private val sessionRepository by lazy { SessionRepository(KeystoreSessionVault(this)) }
    val sessions: SessionController get() = sessionRepository
    val accounts: AccountOperations by lazy { SessionAccountRepository(sessionRepository) }
    val rooms: RoomOperations by lazy { SessionRoomRepository(sessionRepository) }
    val conversations: ConversationOperations by lazy { SessionConversationRepository(sessionRepository) }
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val realtimeCoordinator by lazy {
        RealtimeCoordinator(SessionRealtimeRepository(sessionRepository), sessions, processScope)
    }
    val realtime: RealtimeController get() = realtimeCoordinator
    private lateinit var realtimeRuntime: AndroidRealtimeRuntime

    override fun onCreate() {
        super.onCreate()
        realtimeRuntime = AndroidRealtimeRuntime(this, ProcessLifecycleOwner.get().lifecycle,
            realtimeCoordinator::setForeground, realtimeCoordinator::setNetworkAvailable)
    }
}
