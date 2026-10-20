package io.github.thiesi.chitchat.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Names and pending intent remain in account-scoped VM memory only. */
class IgnorePersonTarget internal constructor(internal val account: IgnoreAccount,
    val roomId: Long, val userId: Long, val username: String?) {
    internal fun matches(session: SessionState) = account == session.ignoreAccount()
}

data class IgnorePersonState(val target: IgnorePersonTarget? = null, val busy: Boolean = false,
    val unconfirmed: Boolean = false, val error: ApiFailure? = null) {
    override fun toString() = "IgnorePersonState"
}

internal data class IgnoreAccount(val server: String?, val generation: Long, val user: Long, val version: Int)
internal fun SessionState.ignoreAccount() = user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
    ?.let { IgnoreAccount(server?.value, generation, it.id, it.sessionVersion) }

class IgnorePersonViewModel(private val operations: IgnoreOperations,
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(IgnorePersonState())
    val state = mutable.asStateFlow()
    private var account = session.value.ignoreAccount()
    private var sequence = 0L
    private var operation: Job? = null

    init { viewModelScope.launch { session.collect { current ->
        val next = current.ignoreAccount()
        if (account != next) { cancel(); account = next }
        else if (mutable.value.unconfirmed && !mutable.value.busy && !current.busy &&
            !current.refreshRequired && !current.storageWarning && current.chatPolicy != null)
            mutable.value = mutable.value.copy(unconfirmed = false, error = null)
    } } }

    fun open(expected: SessionState, roomId: Long, userId: Long, username: String?) {
        val scope = expected.ignoreAccount() ?: return
        if (scope != session.value.ignoreAccount() || roomId <= 0 || userId <= 0 || userId == scope.user ||
            session.value.busy || mutable.value.busy) return
        account = scope
        mutable.value = IgnorePersonState(IgnorePersonTarget(scope, roomId, userId, username))
    }

    fun change(target: IgnorePersonTarget, ignored: Boolean) {
        val expected = session.value
        val ids = expected.chatPolicy?.ignoredUserIds ?: return
        if (mutable.value.target !== target || !target.matches(expected) || mutable.value.busy ||
            mutable.value.unconfirmed || expected.busy || expected.refreshRequired || expected.storageWarning ||
            now() < expected.retryAtMillis || (target.userId in ids) == ignored) return
        val attempt = ++sequence
        mutable.value = mutable.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try {
                operations.change(expected, target.userId, ignored)
                if (sequence == attempt && target.matches(session.value))
                    mutable.value = IgnorePersonState(target)
            } catch (cancelled: CancellationException) {
                if (sequence == attempt) mutable.value = IgnorePersonState(target, unconfirmed = true)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (sequence == attempt && target.matches(session.value))
                    mutable.value = IgnorePersonState(target, unconfirmed = true, error = failure)
            }
        }
    }

    fun close(target: IgnorePersonTarget) { if (mutable.value.target === target) cancel() }
    fun leaveRoom(expected: SessionState, roomId: Long) {
        val target = mutable.value.target ?: return
        if (target.matches(expected) && target.roomId == roomId) close(target)
    }
    fun cancel() {
        sequence++; operation?.cancel(); operation = null
        mutable.value = IgnorePersonState()
    }

    companion object {
        fun factory(operations: IgnoreOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { IgnorePersonViewModel(operations) }
        }
    }
}
