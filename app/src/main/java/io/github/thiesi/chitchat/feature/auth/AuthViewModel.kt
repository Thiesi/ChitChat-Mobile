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
        if (operation?.isActive == true) return
        preparation?.cancel()
        operation = viewModelScope.launch {
            sessions.selectAuthFlow(flow)
            if (state.value.authFlow == AuthFlow.REGISTER) prepareRegistration()
        }
    }

    fun prepareRegistration() {
        preparation?.cancel()
        preparation = viewModelScope.launch { sessions.prepareRegistration() }
    }

    fun pauseRegistration() { preparation?.cancel() }

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
