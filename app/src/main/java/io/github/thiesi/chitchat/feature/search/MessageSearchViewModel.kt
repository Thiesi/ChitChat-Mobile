package io.github.thiesi.chitchat.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.search.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.targets.TargetRequest
import io.github.thiesi.chitchat.core.direct.validatedDirectPrivacy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Root navigation/lifecycle owns entry creation; screen callbacks keep this exact reference. */
class MessageSearchEntry internal constructor(internal val original: SessionState, val navigationEntryId: String) {
    fun matches(current: SessionState) = original.searchOwner() != null && original.searchOwner() == current.searchOwner()
}
class MessageSearchAttempt internal constructor(val entry: MessageSearchEntry, val request: MessageSearchRequest)
class MessageSearchResults internal constructor(val attempt: MessageSearchAttempt, val page: MessageSearchPage,
    val accessFiltered: Boolean = false)
class MessageSearchSelection internal constructor(val results: MessageSearchResults, val hit: MessageSearchHit) {
    /** A hint for fresh exact-target lookup, never authorization or canonical adoption. */
    val target: MessageTarget get() = when (val row = hit) {
        is MessageSearchHit.Room -> MessageTarget.RoomMessage(row.room.id, row.messageId)
        is MessageSearchHit.Direct -> MessageTarget.DirectMessage(row.peer.id, row.messageId)
    }
}
/** Owned GET reference only. Release must reference the target actor, never an Activity/View. */
class MessageSearchTargetBinding internal constructor(val selection: MessageSearchSelection,
    val request: TargetRequest, internal val release: (TargetRequest) -> Unit)
data class MessageSearchState(
    val entry: MessageSearchEntry? = null,
    val query: String = "",
    val scope: MessageSearchScope = MessageSearchScope.ALL,
    val pending: MessageSearchAttempt? = null,
    val results: MessageSearchResults? = null,
    val selection: MessageSearchSelection? = null,
    val targetBinding: MessageSearchTargetBinding? = null,
    val failedAttempt: MessageSearchAttempt? = null,
    val error: ApiFailure? = null,
    val inputTooLong: Boolean = false,
) {
    val busy get() = pending != null
    override fun toString() = "MessageSearchState"
}

private data class SearchOwner(val server: String, val account: Long, val generation: Long, val version: Int)
private fun SessionState.searchOwner(): SearchOwner? {
    val current = user ?: return null
    val origin = server?.value ?: return null
    return if (phase == SessionPhase.AUTHENTICATED && !current.guest)
        SearchOwner(origin, current.id, generation, current.sessionVersion) else null
}

/** Main-confined, one bounded result page. Every POST is a new explicit user action. */
class MessageSearchViewModel(private val operations: MessageSearchOperations,
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(MessageSearchState())
    val state = mutable.asStateFlow()
    private var owner = session.value.searchOwner()
    private var active = false
    private var cleared = false
    private var sequence = 0L
    private var inputRevision = 0L
    private var job: Job? = null

    init { viewModelScope.launch { session.collect {
        val latest = session.value.searchOwner()
        if (owner != latest) {
            retire(); owner = latest; active = false; mutable.value = MessageSearchState()
        }
    } } }

    /** Call only from the actual current root entry, never a result/button callback. */
    fun open(expected: SessionState, navigationEntryId: String): MessageSearchEntry? {
        val latest = session.value.searchOwner()
        if (cleared || latest == null || expected.searchOwner() != latest || navigationEntryId.isBlank()) return null
        val old = mutable.value.entry
        if (owner == latest && old?.navigationEntryId == navigationEntryId) { active = true; return old }
        retire(); owner = latest; active = true
        val entry = MessageSearchEntry(expected, navigationEntryId)
        mutable.value = MessageSearchState(entry = entry)
        return entry
    }
    private fun belongs(entry: MessageSearchEntry) = !cleared && mutable.value.entry === entry &&
        owner != null && owner == entry.original.searchOwner() && owner == session.value.searchOwner()
    private fun usable(entry: MessageSearchEntry) = active && belongs(entry)
    private fun ready(entry: MessageSearchEntry) = usable(entry) && session.value.let {
        !it.busy && !it.refreshRequired && !it.storageWarning && now() >= it.retryAtMillis
    }
    private fun current(attempt: MessageSearchAttempt, token: Long) =
        token == sequence && usable(attempt.entry) && mutable.value.pending === attempt

    fun query(entry: MessageSearchEntry, value: String): Boolean {
        if (!usable(entry)) return false
        if (value.length > MAX_QUERY_UNITS) {
            inputRevision++
            retireSelection()
            mutable.value = mutable.value.copy(inputTooLong = true, selection = null, failedAttempt = null, error = null)
            return false
        }
        if (value == mutable.value.query && !mutable.value.inputTooLong) return true
        inputRevision++
        retireSelection()
        mutable.value = mutable.value.copy(query = value, inputTooLong = false, selection = null,
            failedAttempt = null, error = null)
        return true
    }
    fun scope(entry: MessageSearchEntry, value: MessageSearchScope) {
        if (usable(entry) && mutable.value.scope != value) {
            inputRevision++
            retireSelection()
            mutable.value = mutable.value.copy(scope = value, selection = null, failedAttempt = null, error = null)
        }
    }
    fun search(entry: MessageSearchEntry): MessageSearchAttempt? {
        val value = mutable.value
        if (value.query.isBlank() || value.inputTooLong) return null
        return start(entry, MessageSearchRequest(value.query, value.scope, PAGE_SIZE))
    }
    fun next(entry: MessageSearchEntry, results: MessageSearchResults): MessageSearchAttempt? {
        if (!browsing(entry, results) || !results.page.canLoadMore) return null
        val original = results.attempt.request
        return start(entry, MessageSearchRequest(original.query, original.scope, PAGE_SIZE, checkNotNull(results.page.nextOffset)))
    }
    fun previous(entry: MessageSearchEntry, results: MessageSearchResults): MessageSearchAttempt? {
        if (!browsing(entry, results) || results.attempt.request.offset == 0) return null
        val original = results.attempt.request
        return start(entry, MessageSearchRequest(original.query, original.scope, PAGE_SIZE, (original.offset - PAGE_SIZE).coerceAtLeast(0)))
    }
    private fun browsing(entry: MessageSearchEntry, results: MessageSearchResults) = ready(entry) &&
        !mutable.value.busy && mutable.value.results === results && results.attempt.entry === entry

    fun retry(attempt: MessageSearchAttempt): MessageSearchAttempt? {
        if (mutable.value.failedAttempt !== attempt || mutable.value.inputTooLong) return null
        return start(attempt.entry, attempt.request)
    }
    fun cancel(attempt: MessageSearchAttempt) {
        if (!belongs(attempt.entry) || mutable.value.pending !== attempt) return
        retire()
        mutable.value = mutable.value.copy(pending = null, selection = null, failedAttempt = null, error = null)
    }
    private fun start(entry: MessageSearchEntry, request: MessageSearchRequest): MessageSearchAttempt? {
        if (!ready(entry) || mutable.value.busy) return null
        val attempt = MessageSearchAttempt(entry, request)
        val token = ++sequence
        val originalInputRevision = inputRevision
        retireSelection()
        mutable.value = mutable.value.copy(pending = attempt, selection = null, failedAttempt = null, error = null)
        val work = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val reply = operations.search(entry.original, request)
                if (current(attempt, token)) mutable.value = mutable.value.copy(results = MessageSearchResults(attempt, reply))
            } catch (cancelled: CancellationException) {
                if (current(attempt, token)) mutable.value = mutable.value.copy(results = null,
                    failedAttempt = attempt.takeIf { inputRevision == originalInputRevision }, error = ApiFailure(FailureKind.EXPIRED))
                throw cancelled
            } catch (failure: ApiFailure) {
                if (current(attempt, token)) {
                    val denied = failure.kind in setOf(FailureKind.INCOMPATIBLE, FailureKind.EXPIRED) ||
                        failure.status in setOf(401, 403, 404) && failure.code != "csrf_failed"
                    mutable.value = mutable.value.copy(results = mutable.value.results.takeUnless { denied },
                        failedAttempt = attempt.takeIf { inputRevision == originalInputRevision }, error = failure)
                }
            } finally {
                if (current(attempt, token)) { job = null; mutable.value = mutable.value.copy(pending = null) }
            }
        }
        job = work; work.start()
        return attempt
    }

    fun select(entry: MessageSearchEntry, results: MessageSearchResults, hit: MessageSearchHit): MessageSearchSelection? {
        if (!browsing(entry, results) || results.page.results.none { it === hit }) return null
        retireSelection()
        val choice = MessageSearchSelection(results, hit)
        mutable.value = mutable.value.copy(selection = choice)
        return choice
    }
    fun cancelSelection(choice: MessageSearchSelection) {
        if (mutable.value.selection === choice) retireSelection()
    }
    private fun targetChoiceCurrent(choice: MessageSearchSelection) = mutable.value.selection === choice &&
        browsing(choice.results.attempt.entry, choice.results) &&
        (choice.results.attempt.request.scope == MessageSearchScope.ROOMS ||
            runCatching { validatedDirectPrivacy(session.value.privacy) }.isSuccess)

    /** Bind the root's original load before any asynchronous adoption effect can run. */
    fun bindTarget(choice: MessageSearchSelection, request: TargetRequest,
        release: (TargetRequest) -> Unit): MessageSearchTargetBinding? {
        if (!targetChoiceCurrent(choice) || request.original !== choice.results.attempt.entry.original ||
            !request.matches(session.value) || request.target != choice.target) return null
        val old = mutable.value.targetBinding
        if (old?.selection === choice && old.request === request) return old
        if (old != null) {
            mutable.value = mutable.value.copy(targetBinding = null)
            old.release(old.request)
            if (!targetChoiceCurrent(choice) || !request.matches(session.value)) return null
        }
        val binding = MessageSearchTargetBinding(choice, request, release)
        mutable.value = mutable.value.copy(targetBinding = binding)
        return binding
    }
    fun cancelTarget(binding: MessageSearchTargetBinding) {
        if (mutable.value.targetBinding === binding) retireSelection()
    }
    /** Confirmed resource denial retires its cached excerpts; missing IDs are not a denial. */
    fun targetDenied(binding: MessageSearchTargetBinding, request: TargetRequest, failure: ApiFailure) {
        if (mutable.value.targetBinding !== binding || binding.request !== request ||
            !request.matches(session.value) || !belongs(binding.selection.results.attempt.entry) ||
            failure.status !in setOf(403, 404) || failure.code == "csrf_failed") return
        val results = mutable.value.results ?: return
        val target = request.target
        val retained = results.page.results.filterNot { hit -> when (target) {
            is MessageTarget.RoomMessage -> hit is MessageSearchHit.Room && hit.room.id == target.roomId
            is MessageTarget.DirectMessage -> hit is MessageSearchHit.Direct && hit.peer.id == target.peerId
            is MessageTarget.RoomPing -> false
        } }
        retireSelection()
        mutable.value = mutable.value.copy(results = MessageSearchResults(results.attempt,
            MessageSearchPage(retained, results.page.hasMore, results.page.nextOffset), accessFiltered = true),
            failedAttempt = null, error = failure)
    }
    /** Called only inside the shared target actor's fresh, policy-rebound adoption callback. */
    fun adoptTarget(binding: MessageSearchTargetBinding, request: TargetRequest, accept: () -> Boolean): Boolean {
        if (mutable.value.targetBinding !== binding || binding.request !== request ||
            !request.matches(session.value) || !targetChoiceCurrent(binding.selection)) return false
        return adopt(binding.selection) { accept() }
    }
    /** Busy/rejecting native consumers keep their choice; successor selections remain untouched. */
    fun adopt(choice: MessageSearchSelection, accept: (MessageSearchSelection) -> Boolean): Boolean {
        if (mutable.value.selection !== choice || !browsing(choice.results.attempt.entry, choice.results)) return false
        if (!accept(choice)) return false
        if (mutable.value.selection === choice) retireSelection()
        return true
    }

    /** Root must skip this during configuration recreation. Resume never replays a POST. */
    fun background(entry: MessageSearchEntry) {
        if (!belongs(entry)) return
        active = false; retire(); mutable.value = MessageSearchState(entry = entry)
    }
    fun close(entry: MessageSearchEntry) {
        if (!belongs(entry)) return
        active = false; retire(); mutable.value = MessageSearchState()
    }
    private fun retireSelection() {
        val old = mutable.value.targetBinding
        mutable.value = mutable.value.copy(selection = null, targetBinding = null)
        old?.release?.invoke(old.request)
    }
    private fun retire() { sequence++; job?.cancel(); job = null; retireSelection() }
    override fun onCleared() {
        cleared = true; active = false; retire(); owner = null; mutable.value = MessageSearchState()
        super.onCleared()
    }
    companion object {
        const val MAX_QUERY_UNITS = 4096
        const val PAGE_SIZE = 25
        fun factory(operations: MessageSearchOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { MessageSearchViewModel(operations) }
        }
    }
}
