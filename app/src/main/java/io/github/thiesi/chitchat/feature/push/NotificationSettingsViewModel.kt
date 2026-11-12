package io.github.thiesi.chitchat.feature.push

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.push.PushDevice
import io.github.thiesi.chitchat.core.push.PushOperations
import io.github.thiesi.chitchat.core.push.PushPreferences
import io.github.thiesi.chitchat.core.push.PushSettings
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Private, memory-only notification settings for one signed-in account generation. */
data class NotificationSettingsState(
    val loading: Boolean = false,
    val settings: PushSettings? = null,
    val error: ApiFailure? = null,
    val writing: Boolean = false,
    /** A write whose outcome isn't known. A new choice needs an explicit Refresh (GET) first. */
    val unconfirmed: Boolean = false,
) {
    val busy: Boolean get() = loading || writing
    override fun toString() = "NotificationSettingsState"
}

/** Explicit GETs and confirmed POSTs only: no optimistic flip, no replay of an uncertain write. */
class NotificationSettingsViewModel(private val operations: PushOperations) : ViewModel() {
    private val mutable = MutableStateFlow(NotificationSettingsState())
    val state: StateFlow<NotificationSettingsState> = mutable.asStateFlow()
    val session = operations.session
    private var owner: Identity? = null
    private var job: Job? = null

    private data class Identity(val server: String?, val generation: Long, val user: Long, val version: Int)
    private fun SessionState.identity() = user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
        ?.let { Identity(server?.value, generation, it.id, it.sessionVersion) }

    /** Rebinds to the current account; another account or generation never sees earlier facts. */
    private fun bind(expected: SessionState): Boolean {
        val identity = expected.identity() ?: return false
        if (identity != operations.session.value.identity()) return false
        if (owner != identity) { job?.cancel(); job = null; owner = identity; mutable.value = NotificationSettingsState() }
        return true
    }
    fun belongsTo(expected: SessionState) = expected.identity() != null && expected.identity() == owner

    fun open(expected: SessionState) {
        if (!bind(expected)) return
        val value = mutable.value
        if (value.settings == null && !value.busy && value.error == null) load(expected)
    }

    /** The explicit reconciliation after an unconfirmed write, and an ordinary manual refresh. */
    fun refresh(expected: SessionState) { if (bind(expected) && !mutable.value.busy) load(expected) }

    private fun load(expected: SessionState) {
        val identity = owner
        mutable.value = mutable.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            try {
                val loaded = operations.settings(expected)
                if (owner == identity) mutable.value = NotificationSettingsState(settings = loaded)
            } catch (cancelled: CancellationException) {
                if (owner == identity) mutable.value = mutable.value.copy(loading = false)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (owner == identity) mutable.value = mutable.value.copy(loading = false, error = failure)
            }
        }
    }

    fun canChoose(): Boolean = mutable.value.let { it.settings != null && !it.busy && !it.unconfirmed }

    fun choose(expected: SessionState, preferences: PushPreferences): Boolean {
        if (!bind(expected) || !canChoose()) return false
        val identity = owner
        mutable.value = mutable.value.copy(writing = true, error = null)
        job = viewModelScope.launch {
            try {
                val confirmed = operations.update(expected, preferences)
                if (owner == identity) mutable.value = mutable.value.copy(writing = false,
                    settings = mutable.value.settings?.let { PushSettings(confirmed, it.devices) })
            } catch (cancelled: CancellationException) {
                if (owner == identity) mutable.value = mutable.value.copy(writing = false, unconfirmed = true)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (owner == identity) mutable.value = mutable.value.copy(writing = false, error = failure,
                    unconfirmed = failure.ambiguous())
            }
        }
        return true
    }

    fun remove(expected: SessionState, device: PushDevice): Boolean {
        if (!bind(expected) || !canChoose() || mutable.value.settings?.devices?.none { it === device } != false) return false
        val identity = owner
        mutable.value = mutable.value.copy(writing = true, error = null)
        job = viewModelScope.launch {
            try {
                val remaining = operations.revoke(expected, device.id)
                if (owner == identity) mutable.value = mutable.value.copy(writing = false,
                    settings = mutable.value.settings?.let { PushSettings(it.preferences, remaining) })
            } catch (cancelled: CancellationException) {
                if (owner == identity) mutable.value = mutable.value.copy(writing = false, unconfirmed = true)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (owner == identity) mutable.value = mutable.value.copy(writing = false, error = failure,
                    unconfirmed = failure.ambiguous())
            }
        }
        return true
    }

    /** Genuine background or sign-out clears private facts. An in-flight write becomes unconfirmed. */
    fun retire() {
        job?.cancel(); job = null
        owner = null
        mutable.value = NotificationSettingsState()
    }

    private fun ApiFailure.ambiguous() = kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.TLS,
        FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) || (status ?: 0) >= 500

    companion object {
        internal fun factory(operations: PushOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotificationSettingsViewModel(operations) }
        }
    }
}
