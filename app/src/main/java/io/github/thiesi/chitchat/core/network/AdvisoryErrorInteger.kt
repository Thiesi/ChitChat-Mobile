package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A bad optional hint must not erase the HTTP refusal or its machine error code. */
internal object AdvisoryErrorInteger : KSerializer<Long?> {
    private val scalar = Long.serializer().nullable
    override val descriptor = scalar.descriptor
    override fun serialize(encoder: Encoder, value: Long?) = scalar.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Long? {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive ?: return null
        return if (value.isString) null else value.longOrNull?.takeIf { it >= 0 }
    }
}
