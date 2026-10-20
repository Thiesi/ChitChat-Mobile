package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.serverInstant
import io.github.thiesi.chitchat.core.realtime.RealtimeEvent
import io.github.thiesi.chitchat.core.realtime.RealtimeSignal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

@Serializable private class EventEnvelope(val id: Long, val type: String,
    @SerialName("room_id") val roomId: Long?, @SerialName("actor_user_id") val actorUserId: Long?,
    val payload: JsonElement, @SerialName("created_at") val createdAt: String)

/** OkHttp supplies SSE framing; this validates ChitChat's two control shapes and envelope. */
internal fun decodeRealtimeSignal(id: String?, type: String?, data: String): RealtimeSignal {
    incompatibleUnless(data.length <= 1_048_576 && data.toByteArray(Charsets.UTF_8).size <= 1_048_576)
    try {
        val root = apiJson.parseToJsonElement(data) as? JsonObject ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
        // EventSource carries lastId into unnumbered frames. Inspect the control shape FIRST,
        // including after an ordinary numbered event, not only when callback id == null.
        if (type == "forced_logout" && "id" !in root) {
            val reason = root["reason"] as? JsonPrimitive
            incompatibleUnless(reason?.isString == true && reason.content == "session_invalidated")
            return RealtimeSignal.SessionInvalidated
        }
        if (type == "error" && "id" !in root) {
            val code = root["code"] as? JsonPrimitive
            incompatibleUnless(code?.isString == true && code.content in setOf("stream_error", "internal_error"))
            throw ApiFailure(FailureKind.NETWORK, code = code!!.content)
        }
        val envelope = apiJson.decodeFromJsonElement<EventEnvelope>(root)
        incompatibleUnless(id != null && id.matches(Regex("[1-9][0-9]{0,18}")) &&
            id.toLongOrNull() == envelope.id && envelope.id > 0 && type == envelope.type &&
            envelope.type.isNotEmpty() && envelope.type.length <= 100 &&
            (envelope.roomId == null || envelope.roomId > 0) &&
            (envelope.actorUserId == null || envelope.actorUserId > 0))
        val created = serverInstant(envelope.createdAt) ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
        val payload = when (val value = envelope.payload) {
            is JsonObject -> value
            // PHP's empty array in RoomListSignal is encoded as [], not the documented {}.
            is JsonArray -> if (envelope.type == "rooms_changed" && value.isEmpty()) JsonObject(emptyMap())
                else throw ApiFailure(FailureKind.INCOMPATIBLE)
            else -> throw ApiFailure(FailureKind.INCOMPATIBLE)
        }
        if (envelope.type in setOf("room_message", "message_deleted", "room_broadcast", "presence_changed", "ping"))
            incompatibleUnless(envelope.roomId != null)
        if (envelope.type in setOf("global_broadcast", "direct_message", "forced_logout"))
            incompatibleUnless(envelope.roomId == null)
        val version = if (envelope.type == "forced_logout" && "session_version" in payload) {
            val value = payload["session_version"] as? JsonPrimitive
            incompatibleUnless(value != null && !value.isString && value.intOrNull != null && value.intOrNull!! > 0)
            value!!.intOrNull
        } else null
        return RealtimeSignal.Event(RealtimeEvent(envelope.id, envelope.type, envelope.roomId,
            envelope.actorUserId, payload, created, version))
    } catch (_: SerializationException) {
        throw ApiFailure(FailureKind.INCOMPATIBLE)
    }
}
