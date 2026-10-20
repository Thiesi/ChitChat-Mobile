package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** Existing authentication/settings journeys have no live room backend. Test source set only. */
internal class EmptyRoomOperations(override val session: StateFlow<SessionState>) : RoomOperations {
    override suspend fun list() = emptyList<RoomInfo>()
    override suspend fun detail(roomId: Long): RoomInfo = error("Unexpected fixture room detail")
    override suspend fun join(roomId: Long): RoomInfo = error("Unexpected fixture membership write")
    override suspend fun leave(roomId: Long): Unit = error("Unexpected fixture membership write")
    override suspend fun create(input: CreateRoom): RoomInfo = error("Unexpected fixture creation")
}
