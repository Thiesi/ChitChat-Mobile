package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.mentions.ROOM_MENTION_LIMIT
import io.github.thiesi.chitchat.core.mentions.ROOM_COMPLETION_MEMBER_LIMIT
import io.github.thiesi.chitchat.core.mentions.RoomMentionUser
import kotlinx.serialization.Serializable

@Serializable internal class RoomMentionUserReply(val id: Long, val username: String)
@Serializable internal class RoomMentionReply(val users: List<RoomMentionUserReply>)

internal fun RoomMentionReply.validUsers(viewer: Long, limit: Int = ROOM_MENTION_LIMIT): List<RoomMentionUser> {
    incompatibleUnless(limit in 1..ROOM_COMPLETION_MEMBER_LIMIT && users.size <= limit && users.map { it.id }.distinct().size == users.size)
    users.forEach { incompatibleUnless(it.id > 0 && it.id != viewer && it.username.isNotBlank() && it.username.length <= 80) }
    // Preserve the server's order. Existing SQL LIKE treats '_' as a wildcard (backend issue152),
    // so literal startsWith(prefix) is not a wire invariant. These hints grant no send permission.
    return users.map { RoomMentionUser(it.id,it.username) }
}
