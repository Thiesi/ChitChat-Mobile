package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.realtime.PresenceStatus
import io.github.thiesi.chitchat.core.realtime.PresenceUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class PresenceHeartbeatRequest(
    @SerialName("connection_id") val connectionId: String,
    @SerialName("room_id") val roomId: Long?,
    val interacted: Boolean,
)

@Serializable internal class PresenceReply(val presence: PresenceStatus)
@Serializable internal class PresenceUsersReply(val users: List<PresenceUser>)

private val presenceUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

/** Match PresenceService's PHP trim + ASCII strtolower, not UUID.fromString's wider acceptance. */
internal fun presenceHeartbeatRequest(connectionId: String, roomId: Long?, interacted: Boolean): PresenceHeartbeatRequest {
    val normalized = connectionId.trim { it in " \t\n\r\u0000\u000B" }
        .map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    incompatibleUnless(presenceUuid.matches(normalized) && (roomId == null || roomId > 0))
    return PresenceHeartbeatRequest(normalized, roomId, interacted)
}

internal fun requireValidPresenceStatus(value: PresenceStatus, requestedRoomId: Long?) {
    incompatibleUnless((requestedRoomId == null || requestedRoomId > 0) &&
        value.idleSeconds >= 0 && value.leaseSeconds in 30..300)
    incompatibleUnless(value.warningSeconds == null || value.warningSeconds in 1..3600)
    if (value.expired) {
        // Expiring an occupied room changes room→null and therefore resets idle in this backend.
        incompatibleUnless(requestedRoomId != null && value.roomId == null && value.idleSeconds == 0 && value.warningSeconds == null)
    } else {
        incompatibleUnless(value.roomId == requestedRoomId && (value.roomId == null || value.roomId > 0))
        incompatibleUnless(value.roomId != null || value.warningSeconds == null)
    }
}

internal fun requireValidPresenceUsers(users: List<PresenceUser>) {
    incompatibleUnless(users.all { it.id > 0 && it.username.isNotEmpty() && it.idleSeconds >= 0 && it.connections > 0 } &&
        users.map { it.id }.distinct().size == users.size)
}
