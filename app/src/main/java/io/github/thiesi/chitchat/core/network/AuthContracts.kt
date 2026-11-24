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
    /** Read leniently (policies.md): missing means unknown; see [io.github.thiesi.chitchat.core.policies.policyDiscovery]. */
    val policies: kotlinx.serialization.json.JsonElement? = null,
) {
    /** The server-update facts of this body (server-update.md), attached by [SessionHttp.bootstrap]; never decoded. */
    @kotlinx.serialization.Transient
    var discovery: io.github.thiesi.chitchat.core.session.ServerDiscovery? = null
    /** `user.rules` (policies.md), attached from the raw body; never part of the user's identity. */
    @kotlinx.serialization.Transient
    var rules: io.github.thiesi.chitchat.core.policies.RulesStatus? = null
    /** `user.recovery_key` (password-recovery.md), attached from the raw body. */
    @kotlinx.serialization.Transient
    var recoveryKey: io.github.thiesi.chitchat.core.account.RecoveryKeyStatus = io.github.thiesi.chitchat.core.account.RecoveryKeyStatus.Unknown
}

/**
 * The server's accessibility feedback address (`accessibility_feedback.url`, P-221), presence-gated: an `https` URL
 * with a host and no credentials, or a `mailto` address (scheme compared case-insensitively), at most 2,048 characters with no whitespace or control
 * characters. A missing or null field, or anything else, is null: no row, no button, no fallback address.
 */
internal fun accessibilityFeedbackUrl(field: kotlinx.serialization.json.JsonElement?): String? {
    val url = ((field as? kotlinx.serialization.json.JsonObject)?.get("url") as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: return null
    if (url.isEmpty() || url.length > 2048 || url.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = try { java.net.URI(url) } catch (_: java.net.URISyntaxException) { return null }
    // Schemes are case-insensitive (RFC 3986); only the scheme's case is normalized, so intents resolve. Everything after
    // it, a mailto's subject or body included, passes through exactly as the server sent it.
    val scheme = uri.scheme?.lowercase() ?: return null
    val normalized = scheme + url.substring(scheme.length)
    return when (scheme) {
        "https" -> normalized.takeIf { !uri.host.isNullOrEmpty() && uri.rawUserInfo == null }
        "mailto" -> normalized.takeIf { !uri.rawSchemeSpecificPart.isNullOrEmpty() }
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
) {
    /** `user.rules` on a login or registration user (policies.md), attached from the raw body. */
    @kotlinx.serialization.Transient
    var rules: io.github.thiesi.chitchat.core.policies.RulesStatus? = null
    /** `user.recovery_key` on a login or registration user (password-recovery.md). */
    @kotlinx.serialization.Transient
    var recoveryKeyStatus: io.github.thiesi.chitchat.core.account.RecoveryKeyStatus = io.github.thiesi.chitchat.core.account.RecoveryKeyStatus.Unknown
    /** The top-level key `register.php` returns once, for a password account. Never printed. */
    @kotlinx.serialization.Transient
    var registrationRecoveryKey: io.github.thiesi.chitchat.core.account.RecoveryKey? = null
}

@Serializable
internal class RegistrationChallengeReply(val challenge: RegistrationChallenge?)

/** `POST password-reset.php` (password-recovery.md). The key is sent as typed after trimming; the server normalizes. */
@Serializable
internal class PasswordResetRequest(
    val username: String,
    @SerialName("recovery_key") val recoveryKey: String,
    @SerialName("new_password") val newPassword: String,
    @SerialName("challenge_nonce") val challengeNonce: String,
    @SerialName("challenge_solution") val challengeSolution: String,
) {
    override fun toString() = "PasswordResetRequest(redacted)"
}

/** The 202 `pending_mfa` reply of a reset: not signed in, only MFA or cancel can follow. */
@Serializable
internal class PendingResetReply(
    val status: String,
    @SerialName("mfa_required") val mfaRequired: Boolean = false,
    @SerialName("csrf_token") val csrfToken: String,
    val methods: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
internal class ResetCodeRequest(val code: String) {
    override fun toString() = "ResetCodeRequest(redacted)"
}

@Serializable
internal class ResetCancelReply(val status: String, @SerialName("csrf_token") val csrfToken: String)

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
    /** Sent only when the rendered session publishes rules (policies.md); omitted otherwise (a null default isn't encoded). */
    @SerialName("accepted_rules_version") val acceptedRulesVersion: Int? = null,
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
    /**
     * #323: a local refusal by a [io.github.thiesi.chitchat.core.session.RateBudget] gate during a known server wait.
     * No request was made. It reads like the server's `429 rate_limited` (with the remaining wait) but stays
     * distinguishable in code and tests.
     */
    val localGate: Boolean = false,
    /** #323b3: with [localGate], the budget whose known wait refused (a send can be gated by more than one). */
    val gatedBudget: io.github.thiesi.chitchat.core.session.RateBudget? = null,
) : java.io.IOException(kind.name)

internal fun requireValidCsrf(value: String) {
    if (!value.matches(Regex("[0-9a-fA-F]{64}"))) throw ApiFailure(FailureKind.INCOMPATIBLE)
}

internal fun requireValidUser(user: SessionUser) {
    if (user.id <= 0 || user.username.isBlank() || user.username.length > 80 ||
        user.sessionVersion < 0 || user.roles.size > 50
    ) throw ApiFailure(FailureKind.INCOMPATIBLE)
}
