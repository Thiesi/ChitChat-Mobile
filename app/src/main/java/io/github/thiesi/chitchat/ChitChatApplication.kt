package io.github.thiesi.chitchat

import android.app.Application
import io.github.thiesi.chitchat.core.settings.SettingsRepository
import io.github.thiesi.chitchat.core.session.KeystoreSessionVault
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.SessionAccountRepository
import io.github.thiesi.chitchat.core.account.TypingPreferenceOperations
import io.github.thiesi.chitchat.core.account.SessionTypingPreferences
import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.account.SessionIgnoreRepository
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.rooms.SessionRoomRepository
import io.github.thiesi.chitchat.core.conversation.ConversationOperations
import io.github.thiesi.chitchat.core.conversation.SessionConversationRepository
import io.github.thiesi.chitchat.core.direct.DirectOperations
import io.github.thiesi.chitchat.core.direct.SessionDirectRepository
import io.github.thiesi.chitchat.core.targets.MessageTargetOperations
import io.github.thiesi.chitchat.core.targets.SessionMessageTargetRepository
import io.github.thiesi.chitchat.core.notifications.NotificationOperations
import io.github.thiesi.chitchat.core.notifications.SessionNotificationRepository
import io.github.thiesi.chitchat.core.search.MessageSearchOperations
import io.github.thiesi.chitchat.core.search.SessionMessageSearchRepository
import io.github.thiesi.chitchat.core.profile.ProfileOperations
import io.github.thiesi.chitchat.core.profile.SessionProfileRepository
import io.github.thiesi.chitchat.core.mentions.RoomMentionOperations
import io.github.thiesi.chitchat.core.mentions.SessionRoomMentionRepository
import io.github.thiesi.chitchat.core.mutations.SessionMessageMutationRepository
import io.github.thiesi.chitchat.core.reactions.SessionMessageReactionRepository
import io.github.thiesi.chitchat.core.typing.TypingController
import io.github.thiesi.chitchat.core.typing.TypingCoordinator
import io.github.thiesi.chitchat.core.typing.SessionTypingRepository
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.thiesi.chitchat.core.realtime.AndroidRealtimeRuntime
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.realtime.RealtimeCoordinator
import io.github.thiesi.chitchat.core.realtime.SessionRealtimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ChitChatApplication : Application() {
    // Numeric accounting survives retired sessions/workers; private payloads remain in draft owners.
    internal val attachmentMemory by lazy {
        io.github.thiesi.chitchat.core.media.AttachmentMemoryBudget(
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(1L, 96L * 1024 * 1024))
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    private val pushVault by lazy { io.github.thiesi.chitchat.core.push.KeystorePushVault(this) }
    internal val pushKeys by lazy { io.github.thiesi.chitchat.core.push.VaultKeyManager(pushVault) }
    private val pushConnector by lazy { io.github.thiesi.chitchat.core.push.UnifiedPushConnector(this, pushKeys) }
    private val pushLocal by lazy {
        io.github.thiesi.chitchat.core.push.PushLocal(pushVault, pushConnector) {
            getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
        }
    }
    private val sessionRepository by lazy { SessionRepository(KeystoreSessionVault(this), push = pushLocal) }
    internal val pushRegistrar by lazy {
        io.github.thiesi.chitchat.core.push.PushRegistrar(pushLocal, pushConnector, sessionRepository, processScope,
            poster = io.github.thiesi.chitchat.core.push.AndroidPushPoster(this) { foreground })
    }
    internal val pushDistributors by lazy { io.github.thiesi.chitchat.core.push.UnifiedPushDistributors(this) }
    val sessions: SessionController get() = sessionRepository
    val accounts: AccountOperations by lazy { SessionAccountRepository(sessionRepository) }
    val typingPreferences: TypingPreferenceOperations by lazy { SessionTypingPreferences(sessionRepository) }
    val ignores: IgnoreOperations by lazy { SessionIgnoreRepository(sessionRepository) }
    val rooms: RoomOperations by lazy { SessionRoomRepository(sessionRepository) }
    val conversations: ConversationOperations by lazy { SessionConversationRepository(sessionRepository) }
    internal val attachmentUploads by lazy { io.github.thiesi.chitchat.core.media.SessionAttachmentRepository(sessionRepository) }
    internal val messageMutations by lazy { SessionMessageMutationRepository(sessionRepository) }
    internal val messageReactions by lazy { SessionMessageReactionRepository(sessionRepository) }
    val directMessages: DirectOperations by lazy { SessionDirectRepository(sessionRepository) }
    val messageTargets: MessageTargetOperations by lazy { SessionMessageTargetRepository(sessionRepository) }
    val notifications: NotificationOperations by lazy { SessionNotificationRepository(sessionRepository) }
    val push: io.github.thiesi.chitchat.core.push.PushOperations by lazy { io.github.thiesi.chitchat.core.push.SessionPushRepository(sessionRepository) }
    val messageSearch: MessageSearchOperations by lazy { SessionMessageSearchRepository(sessionRepository) }
    val profiles: ProfileOperations by lazy { SessionProfileRepository(sessionRepository) }
    val roomMentions: RoomMentionOperations by lazy { SessionRoomMentionRepository(sessionRepository) }
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val realtimeCoordinator by lazy {
        RealtimeCoordinator(SessionRealtimeRepository(sessionRepository), sessions, processScope)
    }
    val realtime: RealtimeController get() = realtimeCoordinator
    private val typingCoordinator by lazy { TypingCoordinator(SessionTypingRepository(sessionRepository), realtime,
        processScope) }
    val typing: TypingController get() = typingCoordinator
    private lateinit var realtimeRuntime: AndroidRealtimeRuntime
    /** While the app is in the foreground, pushes post silently so nobody is interrupted twice. */
    @Volatile private var foreground = false

    override fun onCreate() {
        super.onCreate()
        realtimeRuntime = AndroidRealtimeRuntime(this, ProcessLifecycleOwner.get().lifecycle,
            { foreground -> this.foreground = foreground
                realtimeCoordinator.setForeground(foreground); typingCoordinator.setForeground(foreground) },
            realtimeCoordinator::setNetworkAvailable)
    }
}
