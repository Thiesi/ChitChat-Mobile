package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.validateRoomCreation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class RoomsReply(val rooms: List<RoomInfo>)
@Serializable internal class RoomReply(val room: RoomInfo)
@Serializable internal class RoomIdRequest(@SerialName("room_id") val roomId: Long)
@Serializable internal class RoomLeftReply(val status: String)

internal fun requireValidRoom(room: RoomInfo, expectedId: Long? = null) {
    val problem = validateRoomCreation(CreateRoom(room.key, room.name, room.infoLine, room.visibility,
        room.minimumAge, room.inactivityTimeoutSeconds, room.guestAccess))
    if (problem != null || room.id <= 0 || (expectedId != null && room.id != expectedId) || room.createdBy <= 0 ||
        room.memberRole !in setOf(null, "member", "moderator", "owner") || room.unreadCount !in 0..100 ||
        (room.lastReadMessageId != null && room.lastReadMessageId <= 0)) throw ApiFailure(FailureKind.INCOMPATIBLE)
}
