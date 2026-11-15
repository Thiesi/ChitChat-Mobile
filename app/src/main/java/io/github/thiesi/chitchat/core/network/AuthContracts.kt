package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.conversation.RoomMute
import io.github.thiesi.chitchat.core.direct.MessagePrivacy
import io.github.thiesi.chitchat.core.media.AttachmentPolicy

internal val apiJson = Json { ignoreUnknownKeys = true }

@Serializable
data class SessionUser(
    val id: Long,
    val username: String,
    val roles: List<String>,
    @SerialName("session_version") val sessionVersion: Int,
    val guest: Boolean,
)

@Serializable
data class SignInProvider(val id: String, val label: String)

@Serializable
data class Lockdown(val enabled: Boolean, val message: String? = null)

// Secret-bearing wire models deliberately do not generate data-class toString().
@Serializable
internal class SessionReply(
    @SerialName("csrf_token") val csrfToken: String,
    val user: SessionUser?,
    @SerialName("registration_enabled") val registrationEnabled: Boolean,
    @SerialName("sign_in_providers") val providers: List<SignInProvider>,
    val lockdown: Lockdown,
    @SerialName("ignored_user_ids") val ignoredUserIds: List<Long>? = null,
    @SerialName("muted_everywhere") val mutedEverywhere: RoomMute? = null,
    val preferences: DisplayPreferences? = null,
    val privacy: MessagePrivacy? = null,
    @SerialName("share_typing") @Serializable(with = StrictJsonBoolean::class) val shareTyping: Boolean? = null,
    @SerialName("attachments") @Serializable(with = AttachmentPolicySerializer::class)
    val attachmentPolicy: AttachmentPolicy = AttachmentPolicy.Unknown,
    @SerialName("web_push") @Serializable(with = io.github.thiesi.chitchat.core.push.WebPushDiscoverySerializer::class)
    val webPush: io.github.thiesi.chitchat.core.push.WebPushDiscovery = io.github.thiesi.chitchat.core.push.WebPushDiscovery.Unknown,
    /** Read leniently: a malformed `application` never fails the bootstrap; see [installationName]. */
    val application: kotlinx.serialization.json.JsonElement? = null,
    /** Read leniently (P-221, ChitChat v3.14.0); see [accessibilityFeedbackUrl]. */
    @SerialName("accessibility_feedback") val accessibilityFeedback: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * The server's accessibility feedback address (`accessibility_feedback.url`, P-221), presence-gated: an `https` URL
 * with a host and no credentials, or a `mailto` address, at most 2,048 characters with no whitespace or control
 * characters. A missing or null field, or anything else, is null: no row, no button, no fallback address.
 */
internal fun accessibilityFeedbackUrl(field: kotlinx.serialization.json.JsonElement?): String? {
    val url = ((field as? kotlinx.serialization.json.JsonObject)?.get("url") as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: return null
    if (url.isEmpty() || url.length > 2048 || url.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = try { java.net.URI(url) } catch (_: java.net.URISyntaxException) { return null }
    return when (uri.scheme) {
        "https" -> url.takeIf { !uri.host.isNullOrEmpty() && uri.rawUserInfo == null }
        "mailto" -> url.takeIf { !uri.rawSchemeSpecificPart.isNullOrEmpty() }
        else -> null
    }
}

/**
 * The installation's display name (`application.name`, ChitChat#149), for copy only: trimmed, 1–80
 * characters and free of control characters, else null so the UI uses the host.
 */
internal fun installationName(application: kotlinx.serialization.json.JsonElement?): String? {
    val name = ((application as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim() ?: return null
    return name.takeIf { it.length in 1..80 && it.none { c -> c.isISOControl() } }
}

@Serializable
internal class AuthReply(
    @SerialName("csrf_token") val csrfToken: String,
    val user: SessionUser? = null,
    @SerialName("mfa_required") val mfaRequired: Boolean = false,
    val methods: List<String> = emptyList(),
    @SerialName("recovery_codes_remaining") val recoveryCodesRemaining: Int? = null,
    val restored: Boolean = false,
    @SerialName("restoration_pending") val restorationPending: Boolean = false,
)

@Serializable
internal class RegistrationChallengeReply(val challenge: RegistrationChallenge?)

@Serializable
internal class RegistrationChallenge(val nonce: String, val bits: Int)

@Serializable
internal class RegistrationRequest(
    val username: String,
    val password: String,
    @SerialName("birth_date") val birthDate: String?,
    val website: String,
    @SerialName("challenge_nonce") val nonce: String?,
    @SerialName("challenge_solution") val solution: String?,
)

@Serializable
internal class PasswordRequest(val username: String, val password: String)

@Serializable
internal class RecoveryRequest(@SerialName("recovery_code") val code: String)

@Serializable
internal class ErrorEnvelope(val error: ServerError)

@Serializable
internal class ServerError(
    val code: String,
    val message: String,
    @SerialName("max_bytes") @Serializable(with = AdvisoryErrorInteger::class) val maxBytes: Long? = null,
    @SerialName("retry_after_seconds") @Serializable(with = AdvisoryErrorInteger::class) val retryAfterSeconds: Long? = null,
)

enum class FailureKind { NETWORK, TLS, TIMEOUT, REDIRECT, INCOMPATIBLE, HTTP, STORAGE, EXPIRED, TOO_LARGE }

/** Safe metadata only; never retain a response body or low-level exception containing secrets. */
class ApiFailure(
    val kind: FailureKind,
    val status: Int? = null,
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
    val maxBytes: Long? = null,
) : java.io.IOException(kind.name)

internal fun requireValidCsrf(value: String) {
    if (!value.matches(Regex("[0-9a-fA-F]{64}"))) throw ApiFailure(FailureKind.INCOMPATIBLE)
}

internal fun requireValidUser(user: SessionUser) {
    if (user.id <= 0 || user.username.isBlank() || user.username.length > 80 ||
        user.sessionVersion < 0 || user.roles.size > 50
    ) throw ApiFailure(FailureKind.INCOMPATIBLE)
}
