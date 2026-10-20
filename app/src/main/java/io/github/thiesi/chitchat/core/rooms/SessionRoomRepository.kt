package io.github.thiesi.chitchat.core.rooms

import io.github.thiesi.chitchat.core.session.SessionRepository

/** The existing origin-bound transport and generation fence own authorization. No separate jar. */
internal class SessionRoomRepository(private val sessions: SessionRepository) : RoomOperations {
    override val session = sessions.state
    override suspend fun list(): List<RoomInfo> {
        val access = sessions.openAccess()
        return sessions.authorized(access) { rooms() }
    }
    override suspend fun detail(roomId: Long): RoomInfo {
        val access = sessions.openAccess()
        return sessions.authorized(access) { room(roomId) }
    }
    override suspend fun join(roomId: Long): RoomInfo {
        val access = sessions.openAccess()
        return sessions.authorized(access) { joinRoom(roomId, access.csrf) }
    }
    override suspend fun leave(roomId: Long) {
        val access = sessions.openAccess()
        sessions.authorized(access) { leaveRoom(roomId, access.csrf) }
    }
    override suspend fun create(input: CreateRoom): RoomInfo {
        val access = sessions.openAccess()
        return sessions.authorized(access) { createRoom(input, access.csrf, access.userId) }
    }
}
