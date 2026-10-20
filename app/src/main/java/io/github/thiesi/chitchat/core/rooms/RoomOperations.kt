package io.github.thiesi.chitchat.core.rooms

import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Account-scoped room metadata; never logged or persisted as plaintext. */
@Serializable
class RoomInfo(
    val id: Long,
    val key: String,
    val name: String,
    @SerialName("info_line") val infoLine: String,
    val visibility: String,
    @SerialName("minimum_age") val minimumAge: Int,
    @SerialName("inactivity_timeout_seconds") val inactivityTimeoutSeconds: Int,
    @SerialName("created_by") val createdBy: Long,
    @SerialName("member_role") val memberRole: String?,
    val invited: Boolean,
    @SerialName("guest_access") val guestAccess: String,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_read_message_id") val lastReadMessageId: Long? = null,
) {
    val isMember get() = memberRole != null
}

@Serializable
class CreateRoom(
    val key: String,
    val name: String,
    @SerialName("info_line") val infoLine: String,
    val visibility: String,
    @SerialName("minimum_age") val minimumAge: Int,
    @SerialName("inactivity_timeout_seconds") val inactivityTimeoutSeconds: Int,
    @SerialName("guest_access") val guestAccess: String,
) {
    internal fun normalized() = CreateRoom(key.phpTrim().map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString(""), name.phpTrim(), infoLine.phpTrim(),
        visibility, minimumAge, inactivityTimeoutSeconds, guestAccess)
}

// Match PHP trim()/mb_strlen(), including supplementary Unicode, not UTF-16 code units.
private fun String.phpTrim() = trim { it in " \t\n\r\u0000\u000B" }
private fun String.characters() = codePointCount(0, length)
private val roomKey = Regex("[a-z0-9][a-z0-9_-]{2,47}")

fun validateRoomCreation(input: CreateRoom): String? {
    val value = input.normalized()
    return when {
        !roomKey.matches(value.key) -> "invalid_room_key"
        value.name.characters() !in 1..120 -> "invalid_room_name"
        value.infoLine.characters() > 255 -> "invalid_room_info"
        value.visibility !in setOf("public", "unlisted", "private") -> "invalid_visibility"
        value.minimumAge !in 0..120 -> "invalid_minimum_age"
        value.inactivityTimeoutSeconds != 0 && value.inactivityTimeoutSeconds !in 120..86400 -> "invalid_inactivity_timeout"
        value.guestAccess !in setOf("none", "read", "write") -> "invalid_guest_access"
        value.guestAccess != "none" && (value.visibility != "public" || value.minimumAge != 0) -> "guest_access_scope"
        else -> null
    }
}

fun canCreateRooms(user: SessionUser?) = user != null && !user.guest &&
    user.roles.any { it in setOf("super_admin", "admin", "chat_admin") }

interface RoomOperations {
    val session: StateFlow<SessionState>
    suspend fun list(): List<RoomInfo>
    suspend fun detail(roomId: Long): RoomInfo
    suspend fun join(roomId: Long): RoomInfo
    suspend fun leave(roomId: Long)
    suspend fun create(input: CreateRoom): RoomInfo
}
