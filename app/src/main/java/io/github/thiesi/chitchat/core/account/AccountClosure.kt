package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * In-app account deletion (play-readiness.md §2; account.md "Account closure"). The API calls it closure; the app says
 * "Delete account". The account becomes `closure_pending` for 14 days, then is finalized; restore is explicit.
 */
sealed interface ClosureResult {
    /** A confirmed 200: scheduled, with the server's deadline. The session is gone. */
    data class Closed(val finalizesAt: String) : ClosureResult
    /** `409 last_super_admin`: nothing changed. */
    data object LastSuperAdmin : ClosureResult
    /** `409 account_not_active`: already in a closure lifecycle. */
    data object NotActive : ClosureResult
    /** A 429: close has no limiter of its own, so it never reached PHP; definite (TL C6). */
    data class RateLimited(val retryAfterSeconds: Long?) : ClosureResult
    /** The request may have committed: never resent; only the explicit Check resolves it (TL C2). */
    data object Unknown : ClosureResult
    /** Any other definite refusal. */
    data class Failed(val failure: ApiFailure) : ClosureResult
}

/**
 * What the app shows about a deletion, fenced to its origin and account (TL C1, C4). Memory only: process death loses it.
 */
sealed interface ClosureState {
    val origin: String
    val userId: Long
    /** The session ended while the deletion request was in flight: wait for its result, not plain sign-in. */
    data class Waiting(override val origin: String, override val userId: Long) : ClosureState
    /** A confirmed deletion: the confirmation screen, with the server's deadline. */
    data class Scheduled(override val origin: String, override val userId: Long, val finalizesAt: String) : ClosureState
    /** It may have gone through: Check (a session read) is the only way forward, never a resend. */
    data class Unknown(override val origin: String, override val userId: Long) : ClosureState
    /** Check found no signed-in session: "most likely went through", never the definite confirmation. */
    data class LikelyClosed(override val origin: String, override val userId: Long) : ClosureState
    /** Check found the session still signed in: "Your account wasn't deleted." */
    data class NotClosed(override val origin: String, override val userId: Long) : ClosureState
    /** The session ended for another reason while a definite refusal came back: the ordinary outcome plus the refusal line. */
    data class EndedWithRefusal(override val origin: String, override val userId: Long, val result: ClosureResult) : ClosureState
}

private val DEADLINE = Regex("[0-9T:.+\\-Z ]{10,40}")

/** `closure.finalizes_at` of a confirmed closure, or null when the reply isn't one (then it's Unknown). */
internal fun closureDeadline(body: String): String? = try {
    val closure = (Json.parseToJsonElement(body) as? JsonObject)?.get("closure") as? JsonObject
    val state = (closure?.get("state") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val deadline = (closure?.get("finalizes_at") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { DEADLINE.matches(it) }
    deadline.takeIf { state == "closure_pending" }
} catch (_: IllegalArgumentException) { null }

/** Lost connection, timeout, a 5xx (a proxy 502/503/504 can follow a commit), a malformed reply or a redirect. */
internal fun ApiFailure.closureMayHaveCommitted(): Boolean =
    kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.INCOMPATIBLE, FailureKind.REDIRECT) || (status ?: 0) >= 500

/**
 * Delete account through `withStepUp` (only `403 step_up_required` asks, once, then one retry). A verification failure
 * is Failed, never Unknown: nothing was sent. The POST itself is never resent.
 */
internal suspend fun requestClosure(close: suspend () -> ClosureResult, verify: suspend () -> Unit): ClosureResult {
    try { return close() } catch (failure: ApiFailure) {
        if (failure.status != 403 || failure.code != "step_up_required") return ClosureResult.Failed(failure)
    }
    try { verify() } catch (failure: ApiFailure) { return ClosureResult.Failed(failure) }
    return try { close() } catch (failure: ApiFailure) { ClosureResult.Failed(failure) }
}

/** A failure as the typed result; a `403 step_up_required` is rethrown by the caller for `withStepUp`, never typed here. */
internal fun closureResult(failure: ApiFailure): ClosureResult = when {
    failure.closureMayHaveCommitted() -> ClosureResult.Unknown
    failure.status == 409 && failure.code == "last_super_admin" -> ClosureResult.LastSuperAdmin
    failure.status == 409 && failure.code == "account_not_active" -> ClosureResult.NotActive
    failure.status == 429 -> ClosureResult.RateLimited(failure.retryAfterSeconds?.takeIf { it > 0 })
    else -> ClosureResult.Failed(failure)
}
