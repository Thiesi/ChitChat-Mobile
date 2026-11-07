package io.github.thiesi.chitchat.core.profile

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

internal class SessionProfileRepository(private val sessions: SessionRepository) : ProfileOperations {
    override val session = sessions.state

    override suspend fun load(expected: SessionState, request: ProfileRequest): ProfileSnapshot {
        val userId = request.userId
        val roomId = request.roomId
        validateProfileTarget(userId, roomId)
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { profile(userId, roomId) }
    }
}
