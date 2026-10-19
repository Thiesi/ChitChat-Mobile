package io.github.thiesi.chitchat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AuthViewModel(private val sessions: SessionController) : ViewModel() {
    val state = sessions.state
    private var selected: ServerAddress? = null
    private var initialized = false
    private var operation: Job? = null

    fun selectServer(server: ServerAddress?) {
        if (initialized && selected == server) return
        initialized = true
        selected = server
        operation?.cancel()
        operation = viewModelScope.launch { sessions.connect(server) }
    }

    fun reconnect() {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.connect(selected) }
    }

    fun signIn(username: String, password: String) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch { sessions.signIn(username, password) }
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
    }

    fun signOut() {
        operation?.cancel()
        operation = viewModelScope.launch { sessions.signOut() }
    }

    companion object {
        fun factory(sessions: SessionController): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(sessions) }
        }
    }
}
