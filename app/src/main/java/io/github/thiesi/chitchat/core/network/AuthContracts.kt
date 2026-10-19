package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
)

@Serializable
internal class AuthReply(
    @SerialName("csrf_token") val csrfToken: String,
    val user: SessionUser? = null,
    @SerialName("mfa_required") val mfaRequired: Boolean = false,
    val methods: List<String> = emptyList(),
    @SerialName("recovery_codes_remaining") val recoveryCodesRemaining: Int? = null,
    val restored: Boolean = false,
)

@Serializable
internal class PasswordRequest(val username: String, val password: String)

@Serializable
internal class RecoveryRequest(@SerialName("recovery_code") val code: String)

@Serializable
internal class ErrorEnvelope(val error: ServerError)

@Serializable
internal class ServerError(val code: String, val message: String)

enum class FailureKind { NETWORK, TLS, TIMEOUT, REDIRECT, INCOMPATIBLE, HTTP, STORAGE, EXPIRED }

/** Safe metadata only; never retain a response body or low-level exception containing secrets. */
class ApiFailure(
    val kind: FailureKind,
    val status: Int? = null,
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
) : java.io.IOException(kind.name)

internal fun requireValidCsrf(value: String) {
    if (!value.matches(Regex("[0-9a-fA-F]{64}"))) throw ApiFailure(FailureKind.INCOMPATIBLE)
}

internal fun requireValidUser(user: SessionUser) {
    if (user.id <= 0 || user.username.isBlank() || user.username.length > 80 ||
        user.sessionVersion < 0 || user.roles.size > 50
    ) throw ApiFailure(FailureKind.INCOMPATIBLE)
}
