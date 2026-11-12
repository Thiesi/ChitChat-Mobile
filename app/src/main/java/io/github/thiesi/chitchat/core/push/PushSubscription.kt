package io.github.thiesi.chitchat.core.push

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The fixed label this app sends; never the device model or name (push-presentation.md, Devices). */
internal const val PUSH_DEVICE_LABEL = "ChitChat for Android"

/** What the connector gives for a registration, validated before it leaves the phone. */
class PushEndpointKeys(val endpoint: String, val p256dh: String, val auth: String) {
    override fun toString() = "PushEndpointKeys"
}

/** The server's subscription identity: [id] for revocation, [binding] to match incoming pushes. */
class PushRegistration(val id: Long, val binding: String?) {
    override fun toString() = "PushRegistration(id=$id)"
}

/** Turn-on refusals the spec names; each is definite and never retried automatically. */
enum class PushSubscribeRefusal { CONFLICT, LIMIT, PROVIDER_UNAVAILABLE, DESTINATION, RATE_LIMITED, OTHER }

internal fun ApiFailure.subscribeRefusal(): PushSubscribeRefusal? = when {
    status == 429 -> PushSubscribeRefusal.RATE_LIMITED
    status == 409 && code == "push_subscription_conflict" -> PushSubscribeRefusal.CONFLICT
    status == 409 && code == "push_subscription_limit" -> PushSubscribeRefusal.LIMIT
    status == 503 && code == "push_provider_unavailable" -> PushSubscribeRefusal.PROVIDER_UNAVAILABLE
    status == 400 && code == "validation_error" -> PushSubscribeRefusal.DESTINATION
    kind == FailureKind.HTTP && status != null && status in 400..499 -> PushSubscribeRefusal.OTHER
    else -> null // Network, timeout, 5xx other than the named one, or a malformed reply: outcome unknown.
}

/**
 * Checks the connector's values against the server's subscribe rules before sending: an
 * ASCII HTTPS endpoint of at most 2048 bytes without credentials or a fragment, a 65-byte
 * uncompressed P-256 point and a 16-byte auth secret (both unpadded base64url).
 */
internal fun validEndpointKeys(endpoint: String, p256dh: String, auth: String): PushEndpointKeys? {
    if (endpoint.length > 2048 || endpoint.any { it.code !in 0x21..0x7e }) return null
    val uri = try { java.net.URI(endpoint) } catch (_: java.net.URISyntaxException) { return null }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
        uri.rawFragment != null) return null
    fun decode(value: String) = try { Base64.getUrlDecoder().decode(value.trimEnd('=')) } catch (_: IllegalArgumentException) { null }
    val key = decode(p256dh) ?: return null
    val secret = decode(auth) ?: return null
    if (key.size != 65 || key[0] != 4.toByte() || secret.size != 16) return null
    return PushEndpointKeys(endpoint, p256dh.trimEnd('='), auth.trimEnd('='))
}

internal fun pushSubscribeBody(keys: PushEndpointKeys, replacesId: Long?): String = buildJsonObject {
    put("endpoint", keys.endpoint); put("p256dh", keys.p256dh); put("auth", keys.auth)
    if (replacesId != null) put("replaces_id", JsonPrimitive(replacesId))
    put("device_label", PUSH_DEVICE_LABEL)
}.toString()

@Serializable internal class PushSubscribeReply(
    @Serializable(with = io.github.thiesi.chitchat.core.network.StrictJsonBoolean::class) val subscribed: Boolean = false,
    val id: Long? = null,
    val binding: String? = null,
) {
    fun domain(): PushRegistration {
        val identity = id?.takeIf { subscribed && it > 0 } ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
        // Opaque, case-sensitive printable ASCII of 1–64 bytes; anything else is not a binding we can match.
        val bound = binding?.also { if (it.length !in 1..64 || it.any { c -> c.code !in 0x21..0x7e }) throw ApiFailure(FailureKind.INCOMPATIBLE) }
        return PushRegistration(identity, bound)
    }
}
