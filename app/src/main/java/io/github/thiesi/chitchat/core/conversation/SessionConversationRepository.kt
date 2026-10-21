package io.github.thiesi.chitchat.core.conversation

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionRepository

/** No independent cookie jar or authentication path. Bootstrap/policy remain session-owned. */
internal class SessionConversationRepository(private val sessions: SessionRepository) : ConversationOperations {
    override val session = sessions.state

    override suspend fun context(roomId: Long): ConversationContext {
        val access = sessions.refreshedAccess()
        val policy = session.value.chatPolicy ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
        return sessions.authorized(access) {
            val room = rooms().firstOrNull { it.id == roomId } ?: room(roomId)
            ConversationContext(room, policy)
        }
    }
    override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
        val access = sessions.openAccess()
        return sessions.authorized(access) { roomHistory(roomId, beforeId, limit) }
    }
    override suspend fun window(roomId: Long, lowerInclusive: Long, upperInclusive: Long?): WindowSnapshot {
        val access = sessions.openAccess()
        return sessions.authorized(access) {
            readCanonicalRoomWindow(roomId, lowerInclusive, upperInclusive) { before, limit ->
                sessions.ensureCurrent(access)
                roomHistory(roomId, before, limit).also { sessions.ensureCurrent(access) }
            }
        }
    }
    override suspend fun pings(roomId: Long): List<RoomPing> {
        val access = sessions.openAccess()
        return sessions.authorized(access) { roomPings(roomId, access.userId) }
    }
    override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
        val access = sessions.openAccess()
        return sessions.authorized(access) { sendRoomMessage(roomId, body, access.csrf, access.userId, replyId) }
    }
    override suspend fun read(roomId: Long, messageId: Long): Long {
        val access = sessions.openAccess()
        return sessions.authorized(access) { readRoom(roomId, messageId, access.csrf) }
    }
}
