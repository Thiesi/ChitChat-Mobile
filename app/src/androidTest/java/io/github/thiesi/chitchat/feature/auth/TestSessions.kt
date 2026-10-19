package io.github.thiesi.chitchat.feature.auth

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow

/** UI-only fixture; no production fake or credential persistence. HTTP is tested separately. */
class TestSessions : SessionController {
    override val state = MutableStateFlow(SessionState())
    var failNextLogin = false
    var requireMfa = false
    var logins = 0
    var recoveries = 0
    override suspend fun connect(server: ServerAddress?) {
        state.value = SessionState(server, state.value.generation + 1,
            if (server == null) SessionPhase.NO_SERVER else SessionPhase.ANONYMOUS,
            public = PublicSession(false, emptyList(), Lockdown(false)))
    }
    override suspend fun signIn(username: String, password: String, restore: Boolean) {
        logins++
        if (failNextLogin) {
            failNextLogin = false
            state.value = state.value.copy(error = ApiFailure(FailureKind.HTTP, 401, "invalid_credentials"))
        } else if (requireMfa) state.value = state.value.copy(phase = SessionPhase.PENDING_MFA, error = null)
        else success(username)
    }
    override suspend fun completeRecovery(code: String) { recoveries++; success("Alice") }
    private fun success(username: String) {
        state.value = state.value.copy(phase = SessionPhase.AUTHENTICATED, error = null,
            user = SessionUser(7, username, listOf("member"), 1, false))
    }
    override suspend fun refresh() { state.value = state.value.copy(error = null, refreshRequired = false) }
    override suspend fun signOut() {
        state.value = SessionState(state.value.server, state.value.generation + 1, SessionPhase.SIGNED_OUT)
    }
}
