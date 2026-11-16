package io.github.thiesi.chitchat.core.policies

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Community-rules acceptance (ChitChat v3.10.0 docs/api/policies.md; play-readiness.md §1, P-170/171). Account status
 * is `user.rules` on the session, login and registration users. Missing or malformed means unknown: never "required".
 * Guests' acceptance is M7.
 */

/** `user.rules`: the version this account accepted (0 = none), when, and whether the current version still needs it. */
data class RulesStatus(val acceptedVersion: Int, val acceptedAt: String?, val acceptanceRequired: Boolean)

/** The account-level rules operations the guidelines screens use (SessionRepository). */
interface RulesOperations {
    val state: kotlinx.coroutines.flow.StateFlow<SessionState>
    /** One POST of the shown [version] for the rendered [expected] session; never resent. */
    suspend fun acceptRules(version: Int, expected: SessionState): RulesAcceptResult
    /** The ordinary session refresh (a GET): re-reads the rules status after Outdated or Unknown. */
    suspend fun refresh()
}

/** One `policies/accept.php` POST. Never resent: Unknown needs a session GET; Outdated needs a re-read and a new choice. */
sealed interface RulesAcceptResult {
    data class Accepted(val status: RulesStatus) : RulesAcceptResult
    /** `409 rules_version_outdated`: the shown version is no longer current (or rules were withdrawn). */
    data object Outdated : RulesAcceptResult
    /** The POST may have committed (timeout, lost connection, 5xx): settle with a session GET, never a resend. */
    data class Unknown(val failure: ApiFailure) : RulesAcceptResult
    data class Failed(val failure: ApiFailure) : RulesAcceptResult
}

/** Strict: `accepted_version` a non-negative JSON integer, `acceptance_required` a JSON boolean, `accepted_at` string or null. */
internal fun rulesStatus(field: JsonElement?): RulesStatus? {
    val rules = field as? JsonObject ?: return null
    val version = (rules["accepted_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return null
    val required = (rules["acceptance_required"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
    val at = when (val value = rules["accepted_at"]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content?.takeIf { it.length in 1..64 } ?: return null
        else -> return null
    }
    return RulesStatus(version, at, required)
}

/** `user.rules` from a session, login or registration body; null when there's no user or no valid status. */
internal fun userRulesStatus(body: String): RulesStatus? = try {
    ((Json.parseToJsonElement(body) as? JsonObject)?.get("user") as? JsonObject)?.get("rules").let(::rulesStatus)
} catch (_: IllegalArgumentException) { null }

/**
 * `403 rules_acceptance_required` (policies.md): the server checks rules acceptance under the content transaction's
 * lock and refuses before any commit, so this refusal is definite. Nothing is unknown and nothing resends.
 */
fun ApiFailure.isRulesRefusal(): Boolean = status == 403 && code == "rules_acceptance_required"
