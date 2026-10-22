package io.github.thiesi.chitchat.feature.auth

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.AuthFlow
import io.github.thiesi.chitchat.core.session.RegistrationPreparation
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
    override suspend fun connect(server: ServerAddress?) {
        state.value = SessionState(server, state.value.generation + 1,
            if (server == null) SessionPhase.NO_SERVER else SessionPhase.ANONYMOUS,
            public = PublicSession(registrationEnabled, emptyList(), Lockdown(false)))
    }
    override suspend fun signIn(username: String, password: String, restore: Boolean) {
        check(password.isNotEmpty()) { "The fixture must receive the submitted password before field clearing" }
        logins++
        if (restore) restores++
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
    override suspend fun selectAuthFlow(flow: AuthFlow) { state.value = state.value.copy(authFlow = flow, error = null) }
    override suspend fun prepareRegistration() { state.value = state.value.copy(registration = RegistrationPreparation.READY) }
    override suspend fun register(username: String, password: String, birthDate: String?) {
        check(password == "fixture-password") { "The fixture must receive the captured registration password" }
        registrations++
        if (failNextRegistration) {
            failNextRegistration = false
            state.value = state.value.copy(registration = RegistrationPreparation.FAILED,
                error = ApiFailure(FailureKind.HTTP, 409, "username_taken"))
        } else success(username)
    }
    override suspend fun signOut() {
        state.value = SessionState(state.value.server, state.value.generation + 1, SessionPhase.SIGNED_OUT)
    }
}
