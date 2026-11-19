package io.github.thiesi.chitchat.feature.account

import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.ClosureResult
import io.github.thiesi.chitchat.core.account.ClosureState
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.auth.TestSessions

/**
 * Delete account against the test session, shaped like D1's repository: a confirmed deletion ends the session with the
 * server's deadline; an Unknown stays on the live session or lands on the ended one; Check is a session read. Counts only.
 */
internal class TestDeletion(private val sessions: TestSessions) : AccountOperations {
    enum class Reply { CONFIRMED, UNKNOWN_LIVE, UNKNOWN_ENDED, LAST_SUPER_ADMIN, FORCED_LOGOUT_THEN_CONFIRMED }
    override val session get() = sessions.state
    @Volatile var posts = 0
    @Volatile var checks = 0
    @Volatile var cancelled = false
    /** FORCED_LOGOUT_THEN_CONFIRMED: the server's forced_logout ends the session, then the 200 waits for this. */
    val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
    var stepUp = false
    var verified = false
    var reply = Reply.CONFIRMED
    val deadline = "2026-12-02T10:00:00+00:00"
    /** What Check finds; by default it doesn't get through (the outcome stays Unknown). */
    var check: ((SessionState) -> SessionState)? = null

    private fun SessionState.ids() = Pair(server?.value.orEmpty(), user?.id ?: 7L)

    override suspend fun checkClosure() { checks++; check?.let { sessions.state.value = it(sessions.state.value) } }
    override suspend fun dismissClosure() { sessions.state.value = sessions.state.value.copy(closure = null) }
    override suspend fun open(): AccountAccess = object : AccountAccess {
        override fun ensureCurrent() = Unit
        override suspend fun export() = error("No export here")
        override suspend fun verificationMethods() = VerificationMethods(false, true, 8)
        override suspend fun verifyPassword(password: String) { verified = true }
        override suspend fun verifyRecovery(code: String) = error("No recovery code here")
        override suspend fun createRecoveryKey() = error("No recovery key here")
        override suspend fun closeAccount(): ClosureResult {
            posts++
            if (stepUp && !verified) throw ApiFailure(FailureKind.HTTP, 403, "step_up_required")
            val now = sessions.state.value
            val (origin, user) = now.ids()
            return when (reply) {
                Reply.CONFIRMED -> {
                    sessions.state.value = SessionState(now.server, now.generation + 1, SessionPhase.INVALIDATED,
                        installationName = now.installationName, closure = ClosureState.Scheduled(origin, user, deadline))
                    ClosureResult.Closed(deadline)
                }
                Reply.UNKNOWN_LIVE -> { sessions.state.value = now.copy(closure = ClosureState.Unknown(origin, user)); ClosureResult.Unknown }
                Reply.UNKNOWN_ENDED -> {
                    sessions.state.value = SessionState(now.server, now.generation + 1, SessionPhase.INVALIDATED,
                        installationName = now.installationName, closure = ClosureState.Unknown(origin, user))
                    ClosureResult.Unknown
                }
                Reply.LAST_SUPER_ADMIN -> ClosureResult.LastSuperAdmin
                Reply.FORCED_LOGOUT_THEN_CONFIRMED -> {
                    // As D1: the ended session waits on the marker, and the 200 lands there as Scheduled.
                    sessions.state.value = SessionState(now.server, now.generation + 1, SessionPhase.INVALIDATED,
                        installationName = now.installationName, closure = ClosureState.Waiting(origin, user))
                    try { answer.await() } catch (cancel: kotlinx.coroutines.CancellationException) { cancelled = true; throw cancel }
                    val waiting = sessions.state.value
                    if (waiting.closure == ClosureState.Waiting(origin, user))
                        sessions.state.value = waiting.copy(closure = ClosureState.Scheduled(origin, user, deadline))
                    ClosureResult.Closed(deadline)
                }
            }
        }
    }
}
