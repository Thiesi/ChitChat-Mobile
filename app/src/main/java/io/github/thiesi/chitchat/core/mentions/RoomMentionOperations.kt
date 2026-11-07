package io.github.thiesi.chitchat.core.mentions

import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

internal const val ROOM_MENTION_LIMIT = 8
internal const val ROOM_COMPLETION_MEMBER_LIMIT = 25

/** One explicit member lookup, never a draft, send request or permission to notify these people. */
class RoomMentionRequest(val roomId: Long, val prefix: String, val limit: Int = ROOM_MENTION_LIMIT)

class RoomMentionUser(val id: Long, val username: String)

/** Ordered bounded server hints, bound to the original request reference and canonical query. */
class RoomMentionBatch(val request: RoomMentionRequest, val prefix: String, val users: List<RoomMentionUser>)

/** Original-session bounded GET only; no general user search, guest access, cache or POST retry. */
interface RoomMentionOperations {
    val session: StateFlow<SessionState>
    suspend fun suggestions(expected: SessionState, request: RoomMentionRequest): RoomMentionBatch
}

internal fun normalizedRoomMentionPrefix(roomId: Long, prefix: String, limit: Int = ROOM_MENTION_LIMIT): String {
    val value = prefix.phpTrim()
    if (roomId <= 0 || limit !in 1..ROOM_COMPLETION_MEMBER_LIMIT || value.length > 32 || !value.matches(Regex("[A-Za-z0-9_.-]*")))
        throw ApiFailure(FailureKind.HTTP,400,"validation_error")
    return value
}
