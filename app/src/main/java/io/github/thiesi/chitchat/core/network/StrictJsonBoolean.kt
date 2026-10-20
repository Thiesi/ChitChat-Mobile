package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** JSON contract flags must not silently accept quoted strings or numbers. */
internal object StrictJsonBoolean : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("JsonBoolean", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        if (value == null || value.isString) throw SerializationException("Expected a JSON Boolean")
        return value.booleanOrNull ?: throw SerializationException("Expected a JSON Boolean")
    }
}
