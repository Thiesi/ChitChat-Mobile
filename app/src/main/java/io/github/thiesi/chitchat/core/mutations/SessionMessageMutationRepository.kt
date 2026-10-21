package io.github.thiesi.chitchat.core.mutations

import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.direct.validatedDirectPrivacy
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

/** Original-session capabilities, not a second cookie jar or a retry/step-up transport. */
internal class SessionMessageMutationRepository(private val sessions: SessionRepository) : MessageMutationOperations {
    override suspend fun editRoom(expected: SessionState, original: RoomMessage, body: String): RoomMessage {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { editRoomMessage(original, body, access.csrf, access.userId) }
    }
    override suspend fun deleteRoom(expected: SessionState, original: RoomMessage): RoomMessage {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { deleteOwnRoomMessage(original, access.csrf, access.userId) }
    }
    override suspend fun editDirect(expected: SessionState, original: DirectMessage, body: String): DirectMessage {
        val access = sessions.openAccess(expected)
        validatedDirectPrivacy(sessions.state.value.privacy)
        return sessions.authorized(access) { editDirectMessage(original, body, access.csrf, access.userId) }
    }
    override suspend fun deleteDirect(expected: SessionState, original: DirectMessage): DirectMessage {
        val access = sessions.openAccess(expected)
        validatedDirectPrivacy(sessions.state.value.privacy)
        return sessions.authorized(access) { deleteOwnDirectMessage(original, access.csrf, access.userId) }
    }
}
