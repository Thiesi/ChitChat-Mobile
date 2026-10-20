package io.github.thiesi.chitchat.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Every new account/lifecycle scope reconciles even if its predecessor dropped buffered hints. */
@Composable
internal fun RealtimeConsumers(realtime: RealtimeController?, session: SessionState,
    rooms: RoomsViewModel, conversations: ConversationViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(realtime, lifecycle, session.server, session.generation, session.phase,
        session.user?.id, session.user?.sessionVersion) {
        if (realtime == null || session.phase != SessionPhase.AUTHENTICATED) return@LaunchedEffect
        fun currentSession(): Boolean {
            val actual = conversations.session.value
            return actual.phase == SessionPhase.AUTHENTICATED && actual.server == session.server &&
                actual.generation == session.generation && actual.user?.id == session.user?.id &&
                actual.user?.sessionVersion == session.user?.sessionVersion
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                realtime.events.collect { event ->
                    if (!currentSession()) return@collect
                    // Payloads are validated upstream. They invalidate HTTP; never display a raw event
                    // as personalized message/reaction metadata or advance a message/read cursor.
                    when (event.type) {
                        "rooms_changed" -> { rooms.invalidate(); conversations.invalidate() }
                        "room_message", "message_deleted", "ping", "message_reaction_changed" -> if (event.roomId != null) {
                            rooms.invalidate()
                            if (conversations.state.value.roomId == event.roomId) conversations.invalidate()
                        }
                    }
                }
            }
            launch {
                var reconciledHint = -1L
                realtime.state.map { Hint(it.generation, it.reconcileVersion, it.phase, it.error != null) }
                    .distinctUntilChanged().collect { hint ->
                        if (currentSession() && hint.generation == session.generation) {
                            if (hint.phase == RealtimePhase.OFFLINE || hint.phase == RealtimePhase.ERROR || hint.failed) {
                                rooms.connectionLost(); conversations.connectionLost()
                            }
                            if (hint.phase != RealtimePhase.OFFLINE && hint.revision != reconciledHint) {
                                reconciledHint = hint.revision
                                rooms.invalidate(); conversations.invalidate()
                            }
                        }
                    }
            }
        }
    }
}

private data class Hint(val generation: Long, val revision: Long, val phase: RealtimePhase, val failed: Boolean)
