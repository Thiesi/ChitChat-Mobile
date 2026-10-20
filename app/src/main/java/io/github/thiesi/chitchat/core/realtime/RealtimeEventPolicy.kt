package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.conversation.RoomPing
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.apiJson
import io.github.thiesi.chitchat.core.network.incompatibleUnless
import io.github.thiesi.chitchat.core.network.requireValidAttention
import io.github.thiesi.chitchat.core.network.requireValidMessage
import io.github.thiesi.chitchat.core.network.requireValidPing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull

/** Validate before cursor acknowledgement. Events invalidate canonical reads, not permission checks. */
internal fun requireValidRealtimePayload(event: RealtimeEvent, viewerId: Long) {
    try {
        val payload = event.payload
        when (event.type) {
            "room_message" -> requireValidMessage(apiJson.decodeFromJsonElement<RoomMessage>(payload.required("message")),
                event.roomId ?: incompatible())
            "message_deleted" -> {
                payload.positiveId("message_id")
                incompatibleUnless(payload.positiveId("room_id") == event.roomId)
            }
            "message_reaction_changed" -> {
                payload.positiveId("message_id")
                when (payload.string("message_kind")) {
                    "room" -> incompatibleUnless(payload.positiveId("room_id") == event.roomId)
                    "direct" -> incompatibleUnless(event.roomId == null)
                    else -> incompatible()
                }
                // Room summaries are personalized to the actor, not this viewer. Re-read before display.
                requireValidAttention(emptyList(), apiJson.decodeFromJsonElement<List<MessageReaction>>(payload.required("reactions")))
            }
            "presence_changed" -> {
                incompatibleUnless(payload.positiveId("room_id") == event.roomId)
                payload.positiveId("user_id")
            }
            "ping" -> requireValidPing(apiJson.decodeFromJsonElement<RoomPing>(payload.required("ping")),
                event.roomId ?: incompatible(), viewerId)
            "typing" -> {
                val room = payload.required("room_id")
                incompatibleUnless(if (event.roomId == null) room == JsonNull else room.strictId() == event.roomId)
                (payload.required("user") as? JsonObject ?: incompatible()).positiveId("id")
            }
            "room_broadcast", "global_broadcast" -> {
                val message = payload.string("message")
                // Maintenance broadcasts include a prefix and do not have an ordinary sender object.
                incompatibleUnless(message.phpTrim().isNotEmpty() && message.codePointCount(0, message.length) <= 4096)
                if (event.roomId != null) incompatibleUnless(payload.positiveId("room_id") == event.roomId)
            }
            "direct_message" -> {
                // M4 owns the full DM model/reducer. For now this is only a typed invalidation hint.
                (payload.required("message") as? JsonObject ?: incompatible()).positiveId("id")
            }
            // rooms_changed really has an empty PHP array normalized by the transport.
            // forced_logout's version/control shape is handled by the session-bound transport.
            // Additive unknown event types trigger reconciliation rather than guessed semantics.
        }
    } catch (_: SerializationException) {
        throw ApiFailure(FailureKind.INCOMPATIBLE)
    }
}

private fun incompatible(): Nothing = throw ApiFailure(FailureKind.INCOMPATIBLE)
private fun JsonObject.required(key: String): JsonElement = this[key] ?: incompatible()
private fun JsonObject.positiveId(key: String): Long = required(key).strictId()
private fun JsonElement.strictId(): Long {
    val primitive = this as? JsonPrimitive ?: incompatible()
    if (primitive.isString) incompatible()
    return primitive.longOrNull?.takeIf { it > 0 } ?: incompatible()
}
private fun JsonObject.string(key: String): String {
    val primitive = required(key) as? JsonPrimitive ?: incompatible()
    if (!primitive.isString) incompatible()
    return primitive.content
}
