package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.rooms.RoomInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class RoomsReply(val rooms: List<RoomInfo>)
@Serializable internal class RoomReply(val room: RoomInfo)
@Serializable internal class RoomIdRequest(@SerialName("room_id") val roomId: Long)
@Serializable internal class RoomLeftReply(val status: String)

internal fun requireValidRoom(room: RoomInfo, expectedId: Long? = null) {
    // Displayed metadata is not a new creation request. Imported/existing strings need not
    // satisfy today's input limits; authorization and effective policy remain server-owned.
    if (room.id <= 0 || (expectedId != null && room.id != expectedId) || room.createdBy <= 0 ||
        room.visibility !in setOf("public", "unlisted", "private") ||
        room.guestAccess !in setOf("none", "read", "write") ||
        room.minimumAge < 0 || room.inactivityTimeoutSeconds < 0 ||
        room.memberRole !in setOf(null, "member", "moderator", "owner") || room.unreadCount < 0 ||
        (room.lastReadMessageId != null && room.lastReadMessageId <= 0)) throw ApiFailure(FailureKind.INCOMPATIBLE)
}
