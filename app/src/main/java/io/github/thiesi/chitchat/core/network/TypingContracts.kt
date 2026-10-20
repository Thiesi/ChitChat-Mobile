package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
internal class TypingPreferenceRequest(@SerialName("share_typing") val shared: Boolean)

@Serializable
internal class TypingPreferenceReply(@SerialName("share_typing")
    @Serializable(with = StrictTypingBoolean::class) val shared: Boolean)

@Serializable
internal class RoomTypingRequest(@SerialName("room_id") val roomId: Long)

@Serializable
internal class DirectTypingRequest(@SerialName("recipient_user_id") val recipient: Long)

@Serializable
internal class TypingSignalReply(@Serializable(with = StrictTypingBoolean::class) val signalled: Boolean)

/** Privacy consent must be a JSON Boolean, never a coerced string. */
internal object StrictTypingBoolean : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("TypingBoolean", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        if (value == null || value.isString) throw SerializationException("Invalid typing preference")
        return value.booleanOrNull ?: throw SerializationException("Invalid typing preference")
    }
}
