package io.github.thiesi.chitchat.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.thiesi.chitchat.app.ChitChatApp
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.app.EmptyRoomOperations
import io.github.thiesi.chitchat.app.EmptyConversationOperations
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.PersonalExport
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow

internal class TestAccounts(override val session: StateFlow<SessionState>) : AccountOperations {
    var methods = VerificationMethods(false, true, 8)
    var failPassword = false
    var exports = 0
    var passwordChecks = 0
    var recoveries = 0
    var recoveryGate: CompletableDeferred<Unit>? = null
    override suspend fun open(): AccountAccess {
        val generation = session.value.generation
        var verified = false
        return object : AccountAccess {
            override fun ensureCurrent() {
                if (session.value.generation != generation || session.value.phase != SessionPhase.AUTHENTICATED)
                    throw CancellationException("Fixture session changed")
            }
            override suspend fun export(): PersonalExport {
                exports++
                if (!verified) throw ApiFailure(FailureKind.HTTP, 403, "step_up_required")
                return PersonalExport("{\"format\":{\"name\":\"chitchat-personal-data-export\",\"version\":1},\"account\":{\"id\":7}}".encodeToByteArray())
            }
            override suspend fun verificationMethods() = methods
            override suspend fun verifyPassword(password: String) {
                passwordChecks++
                check(password.isNotEmpty())
                if (failPassword) throw ApiFailure(FailureKind.HTTP, 403, "step_up_invalid_credentials")
                verified = true
            }
            override suspend fun verifyRecovery(code: String): Int {
                recoveries++
                check(code.isNotBlank())
                recoveryGate?.await()
                verified = true
                return methods.recoveryCodesRemaining - 1
            }
        }
    }
}

@Composable
internal fun TestChitChatApp(settings: SettingsViewModel, auth: AuthViewModel, account: AccountViewModel? = null,
    roomModel: RoomsViewModel? = null, conversationModel: ConversationViewModel? = null,
    directInbox: DirectInboxViewModel? = null, directThread: DirectThreadViewModel? = null,
    realtime: RealtimeController? = null, messageTargets: MessageTargetViewModel? = null,
    notifications: NotificationCenterViewModel? = null) {
    val accountModel = account ?: remember(auth) { AccountViewModel(TestAccounts(auth.state)) }
    val rooms = roomModel ?: remember(auth) { RoomsViewModel(EmptyRoomOperations(auth.state)) }
    val conversations = conversationModel ?: remember(auth) { ConversationViewModel(EmptyConversationOperations(auth.state)) }
    ChitChatApp(settings, auth, accountModel, rooms, conversations, realtime = realtime, directInbox = directInbox,
        directThread = directThread, messageTargets = messageTargets, notifications = notifications)
}
