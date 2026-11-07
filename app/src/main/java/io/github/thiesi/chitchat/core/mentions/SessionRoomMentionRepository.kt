package io.github.thiesi.chitchat.core.mentions

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

internal class SessionRoomMentionRepository(private val sessions: SessionRepository) : RoomMentionOperations {
    override val session = sessions.state

    override suspend fun suggestions(expected: SessionState, request: RoomMentionRequest): RoomMentionBatch {
        val room = request.roomId
        val limit = request.limit
        val prefix = normalizedRoomMentionPrefix(room,request.prefix,limit)
        val access = sessions.openAccess(expected)
        val users = sessions.authorized(access) { roomMentionUsers(room,prefix,access.userId,limit) }
        return RoomMentionBatch(request,prefix,users)
    }
}
