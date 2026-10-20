package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** Private room presentation/attention policy, never a direct-message block. */
interface IgnoreOperations {
    val session: StateFlow<SessionState>
    suspend fun change(expected: SessionState, userId: Long, ignored: Boolean): Set<Long>
}

internal class SessionIgnoreRepository(private val sessions: SessionRepository) : IgnoreOperations {
    override val session = sessions.state
    override suspend fun change(expected: SessionState, userId: Long, ignored: Boolean) =
        sessions.setIgnored(userId, ignored, expected)
}
