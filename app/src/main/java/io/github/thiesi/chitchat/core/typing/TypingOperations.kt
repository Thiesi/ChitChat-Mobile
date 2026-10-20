package io.github.thiesi.chitchat.core.typing

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException

interface TypingOperations {
    val session: StateFlow<SessionState>
    suspend fun signal(expected: SessionState, target: TypingTarget): Boolean
}

internal class SessionTypingRepository(private val sessions: SessionRepository) : TypingOperations {
    override val session = sessions.state
    override suspend fun signal(expected: SessionState, target: TypingTarget): Boolean {
        val access = sessions.openAccess(expected)
        if (sessions.state.value.shareTyping != true) return false
        return sessions.authorized(access) {
            if (sessions.state.value.shareTyping != true) throw CancellationException("Typing is not shared")
            typingSignal(target, access.csrf)
        }
    }
}
