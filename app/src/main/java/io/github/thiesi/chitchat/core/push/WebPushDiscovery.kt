package io.github.thiesi.chitchat.core.push

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The installation's Web Push discovery from the existing session bootstrap (`web_push.enabled`
 * and `web_push.vapid_public_key`). Missing is unknown; an explicit off, a missing key or a
 * malformed value is unavailable, which disables only turning push on, never the session.
 */
sealed interface WebPushDiscovery {
    data object Unknown : WebPushDiscovery
    data object Unavailable : WebPushDiscovery
    /** [vapidKey] is base64url of the 65-byte uncompressed P-256 point, without padding. */
    class Available(val vapidKey: String) : WebPushDiscovery {
        override fun toString() = "WebPushDiscovery.Available"
    }
}

/** A VAPID public key in the form the UnifiedPush connector requires: unpadded base64url, 65 bytes, 0x04 prefix. */
internal fun validVapidKey(raw: String?): String? {
    val key = raw?.trim()?.trimEnd('=') ?: return null
    if (key.length != 87 || key.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) return null
    val bytes = try { Base64.getUrlDecoder().decode(key) } catch (_: IllegalArgumentException) { return null }
    return key.takeIf { bytes.size == 65 && bytes[0] == 4.toByte() }
}

internal object WebPushDiscoverySerializer : KSerializer<WebPushDiscovery> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("WebPushDiscovery")
    override fun deserialize(decoder: Decoder): WebPushDiscovery {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return WebPushDiscovery.Unavailable
        val value = element as? JsonObject ?: return WebPushDiscovery.Unavailable
        val enabled = (value["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: return WebPushDiscovery.Unavailable
        if (!enabled) return WebPushDiscovery.Unavailable
        val key = (value["vapid_public_key"] as? JsonPrimitive)?.takeIf { it.isString && it !is JsonNull }?.content
        return validVapidKey(key)?.let(WebPushDiscovery::Available) ?: WebPushDiscovery.Unavailable
    }
    override fun serialize(encoder: Encoder, value: WebPushDiscovery) = throw UnsupportedOperationException()
}
