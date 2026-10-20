package io.github.thiesi.chitchat.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.account.TypingPreferenceOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TypingPreferenceState(val busy: Boolean = false, val unconfirmed: Boolean = false,
    val error: ApiFailure? = null)

class TypingPreferenceViewModel(private val operations: TypingPreferenceOperations,
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val mutable = MutableStateFlow(TypingPreferenceState())
    val state = mutable.asStateFlow()
    private var identity = operations.session.value.identity()
    private var sequence = 0L
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            operations.session.collect { session ->
                val next = session.identity()
                if (next != identity) {
                    cancel(); identity = next; mutable.value = TypingPreferenceState()
                } else if (!mutable.value.busy && mutable.value.unconfirmed && session.shareTyping != null &&
                    !session.busy && !session.refreshRequired) mutable.value = TypingPreferenceState()
            }
        }
    }

    fun change(shared: Boolean) {
        val expected = operations.session.value
        val scope = expected.identity() ?: return
        if (expected.busy || expected.refreshRequired || expected.storageWarning || expected.retryAtMillis > now() ||
            mutable.value.busy || mutable.value.unconfirmed || expected.shareTyping == shared) return
        identity = scope
        val attempt = ++sequence
        mutable.value = TypingPreferenceState(busy = true)
        operation = viewModelScope.launch {
            try {
                operations.change(expected, shared)
                if (sequence == attempt && operations.session.value.identity() == scope)
                    mutable.value = TypingPreferenceState()
            } catch (cancelled: CancellationException) {
                if (sequence == attempt) mutable.value = TypingPreferenceState(unconfirmed = true)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (sequence == attempt && operations.session.value.identity() == scope)
                    mutable.value = TypingPreferenceState(unconfirmed = true, error = failure)
            }
        }
    }

    /** Never replay a preference POST on resume; reconcile through ordinary session GET. */
    fun cancel() {
        sequence++
        operation?.cancel(); operation = null
        if (mutable.value.busy) mutable.value = TypingPreferenceState(unconfirmed = true)
    }

    private data class Identity(val server: String?, val generation: Long, val user: Long, val version: Int)
    private fun SessionState.identity() = user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
        ?.let { Identity(server?.value, generation, it.id, it.sessionVersion) }

    companion object {
        fun factory(operations: TypingPreferenceOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { TypingPreferenceViewModel(operations) }
        }
    }
}
