package io.github.thiesi.chitchat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.AuthFlow
import io.github.thiesi.chitchat.core.session.RegistrationPreparation
import io.github.thiesi.chitchat.core.session.SessionPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AuthViewModel(private val sessions: SessionController) : ViewModel() {
    val state = sessions.state
    private var selected: ServerAddress? = null
    private var initialized = false
    private var operation: Job? = null
    private var preparation: Job? = null
    private var discovery: Job? = null
    /**
     * #298's failure-only test scalars: how many Forgot password? taps reached this model and which branch each took.
     * Counts only, read by tests; never shown, logged or saved.
     */
    internal class ResetEntryCounts {
        @Volatile var taps = 0
        @Volatile var forwarded = 0
        @Volatile var droppedActiveOperation = 0
    }
    internal val resetEntry = ResetEntryCounts()

    /** The username typed on step 4a, carried into the reset form (O4b2). A username, not a secret; never saved. */
    var resetPrefill: String = ""
        private set

    fun selectServer(server: ServerAddress?) {
        if (initialized && selected == server) return
        initialized = true
        selected = server
        preparation?.cancel()
        operation?.cancel()
        operation = viewModelScope.launch { sessions.connect(server) }
    }

    fun reconnect() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.connect(selected) }
    }

    fun signIn(username: String, password: String, restore: Boolean = false) {
        if (operation?.isActive == true) return
        preparation?.cancel()
        operation = viewModelScope.launch { sessions.signIn(username, password, restore) }
    }

    fun selectForm(flow: AuthFlow) {
        if (operation?.isActive == true) {
            if (flow == AuthFlow.RESET) resetEntry.droppedActiveOperation++
            return
        }
        if (flow == AuthFlow.RESET) resetEntry.forwarded++
        preparation?.cancel()
        operation = viewModelScope.launch {
            sessions.selectAuthFlow(flow)
            if (state.value.authFlow == AuthFlow.REGISTER) prepareRegistration()
            if (state.value.authFlow == AuthFlow.RESET) prepareReset()
        }
    }

    fun prepareRegistration() {
        preparation?.cancel()
        preparation = viewModelScope.launch { sessions.prepareRegistration() }
    }

    fun pauseRegistration() { preparation?.cancel() }

    /** Step 4a opened: the documented discovery GET, once per visit (the screen keeps a per-visit flag). */
    fun discoverPasswordReset() {
        if (discovery?.isActive == true) return
        discovery = viewModelScope.launch { sessions.discoverPasswordReset() }
    }

    /** Forgot password?: the reset form, prefilled with what step 4a's username field held. */
    fun startReset(username: String) {
        resetEntry.taps++
        resetPrefill = username.trim()
        selectForm(AuthFlow.RESET)
    }

    /** A fresh challenge for the next attempt; every attempt needs its own. */
    fun prepareReset() {
        preparation?.cancel()
        preparation = viewModelScope.launch { sessions.prepareReset() }
    }

    /** One reset POST; the screen clears the key and passwords as it calls this. Never resent. */
    fun resetPassword(username: String, recoveryKey: String, newPassword: String) {
        if (operation?.isActive == true) return
        preparation?.cancel()
        operation = viewModelScope.launch { sessions.resetPassword(username, recoveryKey, newPassword) }
    }

    fun completeResetRecovery(code: String) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.completeResetRecovery(code) }
    }

    /** Stop on "Stop resetting your password?": `cancel.php` once. */
    fun cancelReset() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.cancelReset() }
    }

    fun register(username: String, password: String, birthDate: String?) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.register(username, password, birthDate) }
    }

    fun recovery(code: String) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.completeRecovery(code) }
    }

    fun refresh() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.refresh() }
    }

    fun foreground() {
        if (state.value.phase in setOf(SessionPhase.AUTHENTICATED, SessionPhase.GUEST)) refresh()
        else if (state.value.authFlow == AuthFlow.REGISTER && state.value.registration == RegistrationPreparation.IDLE) prepareRegistration()
        else if (state.value.authFlow == AuthFlow.RESET && state.value.reset.pending == null &&
            state.value.reset.preparation == RegistrationPreparation.IDLE) prepareReset()
    }

    /** "I've saved it" on the key display: the key is discarded; nothing records whether it was saved. */
    fun recoveryKeySaved(expected: io.github.thiesi.chitchat.core.session.SessionState) {
        viewModelScope.launch { sessions.dropRegistrationRecoveryKey(expected) }
    }

    fun signOut() {
        preparation?.cancel()
        operation?.cancel()
        operation = viewModelScope.launch { sessions.signOut() }
    }

    companion object {
        fun factory(sessions: SessionController): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(sessions) }
        }
    }
}
