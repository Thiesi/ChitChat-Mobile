package io.github.thiesi.chitchat.core.typing

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimeEvent
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.session.SessionPhase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface TypingTarget {
    data class Room(val id: Long) : TypingTarget { init { require(id > 0) } }
    data class Direct(val peer: Long) : TypingTarget { init { require(peer > 0) } }
}

/** No generated string representation of another person's name. */
class TypingPerson(val id: Long, val username: String)

data class TypingAccount(val server: String?, val generation: Long, val user: Long, val version: Int)

internal fun SessionState.typingAccount() = user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
    ?.let { TypingAccount(server?.value, generation, it.id, it.sessionVersion) }

internal fun hasTypingContent(value: String): Boolean = value.trim().let { it.isNotEmpty() && !it.startsWith('/') }

/** Validate the exact existing event before either cursor acknowledgement or ephemeral display. */
internal fun typingPerson(event: RealtimeEvent): TypingPerson {
    fun invalid(): Nothing = throw ApiFailure(FailureKind.INCOMPATIBLE)
    if (event.type != "typing") invalid()
    val rawRoom = event.payload["room_id"] ?: invalid()
    val room = rawRoom as? JsonPrimitive
    if (event.roomId == null) { if (rawRoom != JsonNull) invalid() }
    else if (event.roomId <= 0 || room == null || room.isString || room.longOrNull != event.roomId) invalid()
    val user = event.payload["user"] as? JsonObject ?: invalid()
    val rawId = user["id"] as? JsonPrimitive ?: invalid()
    val id = rawId.longOrNull ?: invalid()
    val name = user["username"] as? JsonPrimitive ?: invalid()
    if (rawId.isString || id <= 0 || event.actorUserId != id || !name.isString ||
        name.content.isBlank() || name.content.length > 80) invalid()
    return TypingPerson(id, name.content)
}
