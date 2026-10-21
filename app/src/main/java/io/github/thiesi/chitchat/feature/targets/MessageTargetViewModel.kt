package io.github.thiesi.chitchat.feature.targets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MessageTargetState(val request: TargetRequest? = null, val result: TargetSnapshot? = null,
    val loading: Boolean = false, val waitingForSession: Boolean = false, val stale: Boolean = true,
    val error: ApiFailure? = null, val sourceEntryId: String? = null) { override fun toString() = "MessageTargetState" }

/** Shared cancellable GET selection; not a native screen or permission to adopt/read a chat window. */
class MessageTargetViewModel(private val operations: MessageTargetOperations) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(MessageTargetState())
    val state = mutable.asStateFlow()
    private var owner = session.value.targetOwner()
    private var active = false
    private var pending = false
    private var sequence = 0L
    private var job: Job? = null
    init { viewModelScope.launch { session.collect { current ->
        val latest = current.targetOwner()
        if (owner != latest) {
            retire(); owner = latest; pending = false; mutable.value = MessageTargetState()
        } else startIfNeeded()
    } } }

    fun load(expected: SessionState, target: MessageTarget, sourceEntryId: String? = null): TargetRequest? {
        val request = TargetRequest(expected, target)
        if (!request.matches(session.value) || !target.valid(expected.user?.id) || sourceEntryId?.isBlank() == true) return null
        retire(); owner = session.value.targetOwner(); pending = true
        mutable.value = MessageTargetState(request = request, sourceEntryId = sourceEntryId)
        startIfNeeded(); return request
    }
    fun foreground() {
        active = true
        if (mutable.value.request != null && mutable.value.stale && !mutable.value.loading && job?.isActive != true) pending = true
        startIfNeeded()
    }
    fun background() {
        active = false; pending = mutable.value.request != null; retire()
        mutable.value = mutable.value.copy(result = null, loading = false, waitingForSession = false, stale = true)
    }
    fun cancel(request: TargetRequest) {
        if (!selected(request)) return
        retire(); pending = false; mutable.value = MessageTargetState()
    }
    fun retry(request: TargetRequest) {
        if (!selected(request) || mutable.value.loading) return
        pending = true; startIfNeeded()
    }
    /** Bind action to this exact result/request; callers must still fence scope/policy on adoption. */
    fun consume(request: TargetRequest): TargetSnapshot? {
        var result: TargetSnapshot? = null
        adopt(request) { result = it; true }
        return result
    }
    /** Main-confined handoff. A busy/rejecting consumer must not lose the original selection. */
    fun adopt(request: TargetRequest, accept: (TargetSnapshot) -> Boolean): Boolean {
        val value = mutable.value
        val current = session.value
        val snapshot = value.result ?: return false
        if (!selected(request) || value.loading || value.stale || snapshot.request !== request ||
            !snapshot.matches(current) || current.busy || current.refreshRequired || current.storageWarning) return false
        val result = snapshot.withCurrentPolicy(current)
        if (result == null) {
            mutable.value = value.copy(result = null, stale = true, error = ApiFailure(FailureKind.INCOMPATIBLE)); return false
        }
        if (!result.available) { mutable.value = value.copy(result = result); return false }
        if (!accept(result)) return false
        // A consumer may replace/cancel its selection. Never clear its successor's work.
        if (selected(request)) { retire(); pending = false; mutable.value = MessageTargetState() }
        return true
    }
    private fun selected(request: TargetRequest) = mutable.value.request === request && request.matches(session.value)
    private fun current(attempt: Long, request: TargetRequest) = sequence == attempt && selected(request)
    private fun startIfNeeded() {
        val request = mutable.value.request ?: return
        if (!active || !pending || job?.isActive == true || !selected(request)) return
        if (session.value.busy) {
            mutable.value = mutable.value.copy(loading = true, waitingForSession = true); return
        }
        pending = false
        val attempt = ++sequence
        mutable.value = mutable.value.copy(result = null, loading = true, waitingForSession = false, stale = true, error = null)
        val work = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = operations.lookup(request)
                if (current(attempt, request)) {
                    if (result.request !== request || !result.matches(session.value)) throw ApiFailure(FailureKind.INCOMPATIBLE)
                    mutable.value = mutable.value.copy(result = result, stale = false, error = null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) { if (current(attempt, request))
                mutable.value = mutable.value.copy(result = null, stale = true, error = failure)
            } finally { if (current(attempt, request)) {
                job = null; mutable.value = mutable.value.copy(loading = false, waitingForSession = false)
            } }
        }
        job = work; work.start()
    }
    private fun retire() { sequence++; job?.cancel(); job = null }
    companion object {
        fun factory(operations: MessageTargetOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { MessageTargetViewModel(operations) }
        }
    }
}
