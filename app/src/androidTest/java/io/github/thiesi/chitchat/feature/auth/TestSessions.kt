package io.github.thiesi.chitchat.feature.auth

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.AuthFlow
import io.github.thiesi.chitchat.core.session.RegistrationPreparation
import io.github.thiesi.chitchat.core.session.ResetState
import io.github.thiesi.chitchat.core.session.ResetSupport
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred

/** UI-only fixture; no production fake or credential persistence. HTTP is tested separately. */
class TestSessions : SessionController {
    override val state = MutableStateFlow(SessionState())
    var failNextLogin = false
    var requireMfa = false
    @Volatile var logins = 0
    var nextLoginCompletion: CompletableDeferred<Unit>? = null
    var recoveries = 0
    var registrationEnabled = false
    var failNextRegistration = false
    var registrations = 0
    var restores = 0
    /** What the step-4a discovery GET finds (O4b2); [discoveryHold] keeps it CHECKING until completed. */
    var resetSupport = ResetSupport.UNSUPPORTED
    var discoveryHold: CompletableDeferred<Unit>? = null
    @Volatile var discoveries = 0
    @Volatile var resets = 0
    /** The last reset submission, for assertions only (a fixture key, never a real secret). */
    var lastReset: Triple<String, String, String>? = null
    /** Shapes the next reset reply; by default the reset completes with a replacement key. */
    var nextReset: ((SessionState) -> SessionState)? = null
    var nextResetCode: ((SessionState) -> SessionState)? = null
    var nextCancel: ((SessionState) -> SessionState)? = null
    @Volatile var cancels = 0
    /** One-shot: shapes the next connect to a server (a failed bootstrap, an outdated server, lockdown, a public field).
     *  The app's start-up connect(null) never consumes it. */
    @Volatile var nextConnect: ((SessionState) -> SessionState)? = null
    override suspend fun connect(server: ServerAddress?) {
        val connected = SessionState(server, state.value.generation + 1,
            if (server == null) SessionPhase.NO_SERVER else SessionPhase.ANONYMOUS,
            public = PublicSession(registrationEnabled, emptyList(), Lockdown(false)))
        val shape = if (server != null) nextConnect?.also { nextConnect = null } else null
        state.value = shape?.invoke(connected) ?: connected
    }
    override suspend fun signIn(username: String, password: String, restore: Boolean) {
        check(password.isNotEmpty()) { "The fixture must receive the submitted password before field clearing" }
        logins++
        if (restore) restores++
        state.value = state.value.copy(reset = state.value.reset.copy(outcome = null))
        val completion = nextLoginCompletion
        nextLoginCompletion = null
        completion?.await()
        if (failNextLogin) {
            failNextLogin = false
            state.value = state.value.copy(error = ApiFailure(FailureKind.HTTP, 401, "invalid_credentials"))
        } else if (requireMfa) state.value = state.value.copy(phase = SessionPhase.PENDING_MFA, error = null,
            authFlow = if (restore) AuthFlow.RESTORE else AuthFlow.SIGN_IN)
        else { success(username); state.value = state.value.copy(accountRestored = restore) }
    }
    override suspend fun completeRecovery(code: String) {
        recoveries++
        val restored = state.value.authFlow == AuthFlow.RESTORE
        success("Alice")
        state.value = state.value.copy(accountRestored = restored)
    }
    private fun success(username: String) {
        state.value = state.value.copy(phase = SessionPhase.AUTHENTICATED, error = null,
            user = SessionUser(7, username, listOf("member"), 1, false))
    }
    override suspend fun refresh() { state.value = state.value.copy(error = null, refreshRequired = false) }
    /** #298: reset-flow requests and how this fake's guards handled each (counts only). */
    @Volatile var resetFlowRequests = 0
    @Volatile var resetRefusedPending = 0
    @Volatile var resetRefusedSupport = 0
    @Volatile var resetAccepted = 0
    override suspend fun selectAuthFlow(flow: AuthFlow) {
        val reset = flow == AuthFlow.RESET
        if (reset) resetFlowRequests++
        if (state.value.reset.pending != null) { if (reset) resetRefusedPending++; return }
        if (reset && state.value.reset.support != ResetSupport.SUPPORTED) { resetRefusedSupport++; return }
        if (reset) resetAccepted++
        state.value = state.value.copy(authFlow = flow, error = null,
            reset = state.value.reset.copy(preparation = RegistrationPreparation.IDLE, outcome = null))
    }
    override suspend fun discoverPasswordReset() {
        // The real repository's guards (#298): a busy, non-anonymous or already-checking session drops the ask silently.
        val current = state.value
        if (current.phase != SessionPhase.ANONYMOUS || current.busy || current.reset.support == ResetSupport.CHECKING ||
            current.reset.pending != null) return
        discoveries++
        state.value = state.value.copy(reset = state.value.reset.copy(support = ResetSupport.CHECKING))
        discoveryHold?.await()
        state.value = state.value.copy(reset = state.value.reset.copy(support = resetSupport))
    }
    override suspend fun prepareReset() {
        state.value = state.value.copy(reset = state.value.reset.copy(preparation = RegistrationPreparation.READY))
    }
    override suspend fun resetPassword(username: String, recoveryKey: String, newPassword: String) {
        resets++
        lastReset = Triple(username, recoveryKey, newPassword)
        // The prepared challenge is consumed by every attempt, as in SessionRepository.
        state.value = state.value.copy(error = null, reset = state.value.reset.copy(preparation = RegistrationPreparation.IDLE))
        state.value = (nextReset?.also { nextReset = null } ?: ::resetCompleted)(state.value)
    }
    override suspend fun completeResetRecovery(code: String) {
        recoveries++
        state.value = (nextResetCode?.also { nextResetCode = null } ?: ::resetCompleted)(state.value)
    }
    override suspend fun cancelReset() {
        cancels++
        state.value = (nextCancel?.also { nextCancel = null } ?: { it.copy(authFlow = AuthFlow.SIGN_IN,
            reset = it.reset.copy(pending = null, preparation = RegistrationPreparation.IDLE,
                outcome = io.github.thiesi.chitchat.core.session.ResetOutcome.CANCELLED)) })(state.value)
    }
    /** A completed reset: signed in, with the replacement key in the one-shot slot (origin RESET). */
    fun resetCompleted(current: SessionState): SessionState = current.copy(phase = SessionPhase.AUTHENTICATED, error = null,
        user = SessionUser(7, lastReset?.first ?: "Alice", listOf("member"), 1, false),
        registrationRecoveryKey = io.github.thiesi.chitchat.core.account.RecoveryKey("A7K2-B9QX-C4MZ-D8RT-E2WN-F6HP"),
        recoveryKeyOrigin = io.github.thiesi.chitchat.core.account.KeyOrigin.RESET,
        recoveryKey = io.github.thiesi.chitchat.core.account.RecoveryKeyStatus.Known(true, "2026-11-18T10:00:00+00:00"),
        reset = ResetState(support = current.reset.support))
    override suspend fun prepareRegistration() { state.value = state.value.copy(registration = RegistrationPreparation.READY) }
    override suspend fun register(username: String, password: String, birthDate: String?) {
        check(password == "fixture-password") { "The fixture must receive the captured registration password" }
        registrations++
        // Like SessionRepository: the submission stays ANONYMOUS and busy until the reply (register never needs MFA).
        state.value = state.value.copy(busy = true, error = null)
        kotlinx.coroutines.delay(50)
        state.value = state.value.copy(busy = false)
        if (failNextRegistration) {
            failNextRegistration = false
            state.value = state.value.copy(registration = RegistrationPreparation.FAILED,
                error = ApiFailure(FailureKind.HTTP, 409, "username_taken"))
        } else success(username)
    }
    override suspend fun dropRegistrationRecoveryKey(expected: SessionState) {
        if (state.value.generation == expected.generation) state.value = state.value.copy(registrationRecoveryKey = null)
    }
    override suspend fun signOut() {
        state.value = SessionState(state.value.server, state.value.generation + 1, SessionPhase.SIGNED_OUT)
    }
}
