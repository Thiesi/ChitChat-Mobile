package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** Account-wide, bilateral preference; not a device-only notification setting. */
interface TypingPreferenceOperations {
    val session: StateFlow<SessionState>
    suspend fun change(expected: SessionState, shared: Boolean)
}

internal class SessionTypingPreferences(private val sessions: SessionRepository) : TypingPreferenceOperations {
    override val session = sessions.state
    override suspend fun change(expected: SessionState, shared: Boolean) {
        sessions.setTypingPreference(shared, expected)
    }
}
