package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Recovery key (recovery-key.md, P-184 to P-188; ChitChat v3.10.0 docs/api/password-recovery.md). The key is shown once
 * and only lives in memory: it is never written to disk, saved state, logs, analytics or a debug string.
 */

/** One recovery key. Never printed: `toString` is redacted, and equality is identity. */
class RecoveryKey internal constructor(internal val value: String) {
    override fun toString() = "RecoveryKey(redacted)"
}

/** `user.recovery_key`: missing means unknown (every surface hidden); null means a provider-only account or a guest. */
sealed interface RecoveryKeyStatus {
    data object Unknown : RecoveryKeyStatus
    data object NotApplicable : RecoveryKeyStatus
    data class Known(val exists: Boolean, val createdAt: String?) : RecoveryKeyStatus
}

class CreatedRecoveryKey(val key: RecoveryKey, val createdAt: String) {
    override fun toString() = "CreatedRecoveryKey(createdAt=$createdAt)"
}

/** One Generate or Replace (`POST account/recovery-key.php`). Never resent. */
sealed interface RecoveryKeyResult {
    class Created(val created: CreatedRecoveryKey) : RecoveryKeyResult
    /** `409 password_account_required`: a provider-only account has no password to recover. */
    data object PasswordAccountRequired : RecoveryKeyResult
    /** `429`: the server's positive wait in seconds, or null when unknown. */
    data class RateLimited(val retryAfterSeconds: Long?) : RecoveryKeyResult
    /** The request may have committed; one session read compared `created_at`. */
    data class Unknown(val resolution: UnknownResolution) : RecoveryKeyResult
    data class Failed(val failure: ApiFailure) : RecoveryKeyResult
}

enum class UnknownResolution {
    /** A new key was created but never reached this phone: the old one no longer works. */
    CREATED_ELSEWHERE,
    /** Nothing changed: the current key still works. */
    UNCHANGED,
    /** The session read failed too: whether a key was created is unknown. */
    UNRESOLVED,
}

private val KEY = Regex("[A-Z0-9]{4}(-[A-Z0-9]{4}){5}")
private val DATE = Regex("[0-9T:.+\\-Z ]{10,40}")

/** Strict `user.recovery_key`: an object with a boolean `exists` and a `created_at` string or null. */
internal fun recoveryKeyStatus(user: JsonObject?): RecoveryKeyStatus {
    if (user == null || !user.containsKey("recovery_key")) return RecoveryKeyStatus.Unknown
    val field = user["recovery_key"]
    if (field == null || field is JsonNull) return RecoveryKeyStatus.NotApplicable
    val status = field as? JsonObject ?: return RecoveryKeyStatus.Unknown
    val exists = (status["exists"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return RecoveryKeyStatus.Unknown
    val createdAt = when (val value = status["created_at"]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content?.takeIf { DATE.matches(it) } ?: return RecoveryKeyStatus.Unknown
        else -> return RecoveryKeyStatus.Unknown
    }
    if (exists && createdAt == null) return RecoveryKeyStatus.Unknown
    return RecoveryKeyStatus.Known(exists, createdAt)
}

/** `user.recovery_key` from a session, login or registration body. */
internal fun userRecoveryKeyStatus(body: String): RecoveryKeyStatus = try {
    recoveryKeyStatus((Json.parseToJsonElement(body) as? JsonObject)?.get("user") as? JsonObject)
} catch (_: IllegalArgumentException) { RecoveryKeyStatus.Unknown }

/** A key value in the server's canonical shape, or null. A malformed value is dropped, never logged. */
internal fun recoveryKeyValue(field: JsonElement?): RecoveryKey? =
    (field as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { KEY.matches(it) }?.let(::RecoveryKey)

/** The top-level `recovery_key` that `register.php` returns once for a password account. */
internal fun registrationRecoveryKey(body: String): RecoveryKey? = try {
    recoveryKeyValue((Json.parseToJsonElement(body) as? JsonObject)?.get("recovery_key"))
} catch (_: IllegalArgumentException) { null }

/** `{recovery_key, created_at}` from `account/recovery-key.php`; a 200 without both means it may have committed. */
internal fun createdRecoveryKey(root: JsonObject?): CreatedRecoveryKey {
    val key = recoveryKeyValue(root?.get("recovery_key"))
    val createdAt = (root?.get("created_at") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { DATE.matches(it) }
    if (key == null || createdAt == null) throw ApiFailure(FailureKind.INCOMPATIBLE)
    return CreatedRecoveryKey(key, createdAt)
}

/** The `created_at` comparison after a request that may have committed; a status that couldn't be read is unresolved. */
internal fun unknownResolution(now: RecoveryKeyStatus?, before: String?): UnknownResolution = when (now) {
    is RecoveryKeyStatus.Known -> if (now.exists && now.createdAt != before) UnknownResolution.CREATED_ELSEWHERE
        else UnknownResolution.UNCHANGED
    else -> UnknownResolution.UNRESOLVED
}

private fun ApiFailure.ambiguous() = kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.INCOMPATIBLE,
    FailureKind.REDIRECT) || (status ?: 0) >= 500

/**
 * Generate or Replace (recovery-key.md): one POST through `withStepUp` (only `403 step_up_required` asks once and retries
 * once). An ambiguous result is resolved by one session read comparing `created_at` with [before]. It's never resent.
 */
internal suspend fun requestRecoveryKey(create: suspend () -> CreatedRecoveryKey, verify: suspend () -> Unit,
    before: String?, reread: suspend () -> RecoveryKeyStatus?): RecoveryKeyResult = try {
    RecoveryKeyResult.Created(withStepUp(create, verify))
} catch (failure: ApiFailure) {
    when {
        failure.status == 409 && failure.code == "password_account_required" -> RecoveryKeyResult.PasswordAccountRequired
        failure.status == 429 -> RecoveryKeyResult.RateLimited(failure.retryAfterSeconds?.takeIf { it > 0 })
        failure.ambiguous() -> RecoveryKeyResult.Unknown(unknownResolution(reread(), before))
        else -> RecoveryKeyResult.Failed(failure)
    }
}
