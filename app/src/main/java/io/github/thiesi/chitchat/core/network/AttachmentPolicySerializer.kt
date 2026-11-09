package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.media.AttachmentPolicy
import io.github.thiesi.chitchat.core.media.UploadRateLimit
import io.github.thiesi.chitchat.core.media.UploadRateLimits
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** Preserve missing/null/zero without accepting quoted numbers or inventing operator limits. */
internal object AttachmentPolicySerializer : KSerializer<AttachmentPolicy> {
    override val descriptor = buildClassSerialDescriptor("AttachmentPolicy")
    override fun serialize(encoder: Encoder, value: AttachmentPolicy): Unit =
        throw SerializationException("Attachment policy is read-only")

    override fun deserialize(decoder: Decoder): AttachmentPolicy {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: invalid()
        // Invalid JSON still invalidates the response. A valid JSON value with unusable
        // feature settings disables only uploads, never the rest of session bootstrap.
        return try { policy(element) } catch (_: SerializationException) { AttachmentPolicy.Invalid }
    }

    private fun policy(element: JsonElement): AttachmentPolicy {
        if (element === JsonNull) return AttachmentPolicy.Unavailable
        val policy = element as? JsonObject ?: invalid()
        val allowed = mimeTypes(policy["allowed_mime_types"])
        val previewable = mimeTypes(policy["previewable_mime_types"])
        if (!allowed.containsAll(previewable)) invalid()
        val rates = when (val value = policy["rate_limits"]) {
            null, JsonNull -> null
            is JsonObject -> UploadRateLimits(rate(value["room"]), rate(value["direct_message"]))
            else -> invalid()
        }
        return AttachmentPolicy.Known(integer(policy["max_bytes"], 0), allowed, previewable, rates)
    }

    private fun rate(element: JsonElement?): UploadRateLimit {
        val value = element as? JsonObject ?: invalid()
        return UploadRateLimit(integer(value["maximum_attempts"], 1), integer(value["window_seconds"], 1))
    }

    private fun integer(element: JsonElement?, minimum: Long): Long {
        val value = element as? JsonPrimitive ?: invalid()
        if (value.isString) invalid()
        return value.longOrNull?.takeIf { it >= minimum } ?: invalid()
    }

    private val mime = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private fun mimeTypes(element: JsonElement?): Set<String> {
        val values = element as? JsonArray ?: invalid()
        return values.map { element ->
            val value = element as? JsonPrimitive ?: invalid()
            if (!value.isString || !mime.matches(value.content)) invalid()
            value.content.lowercase(Locale.ROOT)
        }.toSet()
    }

    private fun invalid(): Nothing = throw SerializationException("Invalid attachment policy")
}
