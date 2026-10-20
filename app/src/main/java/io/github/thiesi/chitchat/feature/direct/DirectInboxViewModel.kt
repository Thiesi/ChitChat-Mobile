package io.github.thiesi.chitchat.feature.direct

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.direct.DirectInbox
import io.github.thiesi.chitchat.core.direct.DirectOperations
import io.github.thiesi.chitchat.core.direct.validateDirectSearch
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DirectInboxState(val inbox: DirectInbox? = null, val busy: Boolean = false, val stale: Boolean = true,
    val query: String = "", val results: List<MessagePerson> = emptyList(), val searched: Boolean = false,
    val searching: Boolean = false, val invalidQuery: Boolean = false, val error: ApiFailure? = null,
    val searchError: ApiFailure? = null) { override fun toString() = "DirectInboxState" }

class DirectInboxViewModel(private val operations: DirectOperations,
    private val realtime: RealtimeController? = null) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(DirectInboxState())
    val state = mutable.asStateFlow()
    private var identity = session.value.directIdentity()
    private var sequence = 0L
    private var searchSequence = 0L
    private var job: Job? = null
    private var searchJob: Job? = null
    private var active = false
    private var requested = 0L
    private var attempted = 0L

    init { viewModelScope.launch { session.collect {
        if (it.directIdentity() != identity) {
            retire(); identity = it.directIdentity(); mutable.value = DirectInboxState()
        } else drain()
    } } }

    fun foreground() { active = true; invalidate() }
    fun background() {
        active = false; retire()
        mutable.value = mutable.value.copy(busy = false, searching = false, stale = true)
    }
    fun invalidate() { if (currentIdentity()) { requested++; drain() } }
    fun connectionLost() { mutable.value = mutable.value.copy(stale = true) }
    fun refresh() { if (currentIdentity() && !mutable.value.busy) { requested++; drain(explicit = true) } }
    private fun ready() = currentIdentity() && !session.value.busy && !session.value.refreshRequired &&
        !session.value.storageWarning && System.currentTimeMillis() >= session.value.retryAtMillis
    private fun currentIdentity() = identity != null && identity == session.value.directIdentity()
    private fun current(seq: Long, expected: DirectIdentity?) = seq == sequence && expected == identity && currentIdentity()

    private fun drain(explicit: Boolean = false) {
        if (!active || !ready() || mutable.value.busy || requested <= attempted ||
            !explicit && realtime?.state?.value?.phase == RealtimePhase.OFFLINE) return
        attempted = requested
        val seq = ++sequence; val expected = identity
        mutable.value = mutable.value.copy(busy = true, error = null, stale = true)
        val pending = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!current(seq, expected)) return@launch
                val inbox = operations.inbox()
                if (current(seq, expected)) mutable.value = mutable.value.copy(inbox = inbox, stale = false)
            } catch (_: CancellationException) { /* Original account/request only. */ }
            catch (failure: ApiFailure) { if (current(seq, expected)) mutable.value = mutable.value.copy(
                error = failure, stale = true, inbox = mutable.value.inbox.takeUnless { failure.kind == FailureKind.INCOMPATIBLE }) }
            finally { if (current(seq, expected)) { mutable.value = mutable.value.copy(busy = false); job = null; drain() } }
        }
        job = pending; pending.start()
    }
    fun updateQuery(value: String) {
        searchJob?.cancel(); searchSequence++
        mutable.value = mutable.value.copy(query = value, results = emptyList(), searched = false,
            searching = false, invalidQuery = false, searchError = null)
    }
    fun search() {
        val state = mutable.value
        if (!ready() || state.inbox == null || state.stale || state.searching) return
        if (!validateDirectSearch(state.query)) { mutable.value = state.copy(invalidQuery = true); return }
        val token = ++searchSequence; val expected = identity; val query = state.query
        mutable.value = state.copy(searching = true, invalidQuery = false, searchError = null)
        searchJob = viewModelScope.launch {
            fun valid() = token == searchSequence && expected == identity && currentIdentity()
            try {
                if (!valid()) return@launch
                val users = operations.search(query)
                if (valid()) mutable.value = mutable.value.copy(results = users, searched = true)
            } catch (_: CancellationException) { /* No cross-account or obsolete-prefix results. */ }
            catch (failure: ApiFailure) { if (valid()) mutable.value = mutable.value.copy(searchError = failure) }
            finally { if (valid()) mutable.value = mutable.value.copy(searching = false) }
        }
    }
    private fun retire() { sequence++; searchSequence++; job?.cancel(); searchJob?.cancel(); requested = 0; attempted = 0 }
    companion object {
        fun factory(operations: DirectOperations, realtime: RealtimeController? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { DirectInboxViewModel(operations, realtime) }
        }
    }
}
