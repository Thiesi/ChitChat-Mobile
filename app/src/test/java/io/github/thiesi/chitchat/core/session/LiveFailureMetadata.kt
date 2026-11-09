package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.ApiFailure

internal enum class LiveStep {
    BOOTSTRAP, LOGIN, SESSION_RESTORATION, REVALIDATION, ROOM_LIST, ROOM_DETAIL,
    ROOM_CONTEXT, HISTORY, PINGS, LOGOUT, LOGOUT_VERIFICATION,
}

/** Never format a Throwable, a response, or an unrecognized server-supplied string. */
internal fun liveFailureMetadata(step: LiveStep, thrown: Throwable, sessionError: ApiFailure?): String {
    val direct = thrown as? ApiFailure
    val failure = direct ?: sessionError
    val source = if (direct != null) "thrown" else if (sessionError != null) "session" else "unavailable"
    val status = failure?.status?.takeIf { it in 100..599 }?.toString() ?: "unknown"
    val code = failure?.code?.takeIf { it in liveFailureCodes } ?: "unknown"
    val kind = failure?.kind?.name ?: "unknown"
    return "Live failure: step=${step.name} source=$source kind=$kind status=$status code=$code"
}

// Public machine-code vocabulary only. Unknown codes stay unknown, even if token-shaped.
private val liveFailureCodes = setOf(
    "authentication_required", "auth_required", "session_expired", "invalid_credentials",
    "login_throttled", "rate_limited", "rules_acceptance_required", "lockdown_active",
    "lockdown_enabled", "account_locked", "account_disabled", "account_banned",
    "account_deletion_pending", "csrf_invalid", "csrf_token_invalid", "invalid_csrf_token",
    "room_not_found", "room_forbidden", "membership_required", "age_restricted",
    "minimum_age_required", "validation_error", "not_found", "method_not_allowed",
    "forbidden", "internal_error", "server_error",
)
