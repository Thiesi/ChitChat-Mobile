package io.github.thiesi.chitchat.feature.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.rooms.CreateRoom
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.RoomOperations
import io.github.thiesi.chitchat.core.rooms.canCreateRooms
import io.github.thiesi.chitchat.core.rooms.validateRoomCreation
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Deliberately only VM memory: room drafts and lookup IDs never enter saved-instance state. */
data class RoomDraft(
    val key: String = "", val name: String = "", val info: String = "",
    val visibility: String = "public", val minimumAge: String = "0",
    val inactivitySeconds: String = "0", val guestAccess: String = "none",
) {
    fun request(): CreateRoom? {
        val age = minimumAge.toIntOrNull() ?: return null
        val inactivity = inactivitySeconds.toIntOrNull() ?: return null
        return CreateRoom(key, name, info, visibility, age, inactivity, guestAccess)
    }
}

data class RoomsUiState(
    val rooms: List<RoomInfo> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val stale: Boolean = true,
    val selected: RoomInfo? = null, val detailOpen: Boolean = false,
    val leaveConfirmation: Boolean = false, val lookup: String = "",
    val draft: RoomDraft? = null, val error: ApiFailure? = null,
    val validation: String? = null, val mutationUncertain: Boolean = false,
    val notice: String? = null,
)

class RoomsViewModel(private val operations: RoomOperations) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(RoomsUiState())
    val state = mutable.asStateFlow()
    private var identity = session.value.identity()
    private var sequence = 0L
    private var job: Job? = null
    private var mutationInFlight = false
    private var foreground = false

    init {
        viewModelScope.launch {
            session.collect { current ->
                if (current.identity() != identity) {
                    retire()
                    identity = current.identity()
                    mutable.value = RoomsUiState()
                    if (foreground && identity != null) refresh()
                }
            }
        }
    }

    fun foreground() { foreground = true; refresh() }

    fun background() {
        foreground = false
        cancelRequest()
    }

    fun cancelRequest() {
        val uncertain = mutationInFlight || mutable.value.mutationUncertain
        retire()
        mutable.value = mutable.value.copy(busy = false, stale = true, leaveConfirmation = false,
            mutationUncertain = uncertain, notice = if (uncertain) "unconfirmed" else "cancelled")
    }

    /** Navigation uses the same cancellation rule as a real background transition. */
    fun cancel() = background()

    fun refresh() {
        if (mutable.value.busy || session.value.identity() == null) return
        start(false) { current -> reconcile(current) }
    }

    fun updateLookup(value: String) {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(lookup = value, validation = null)
    }

    fun lookup() {
        val id = mutable.value.lookup.trim().toLongOrNull()
        if (id == null || id <= 0) { mutable.value = mutable.value.copy(validation = "invalid_room_id"); return }
        select(id)
    }

    fun select(id: Long) {
        if (mutable.value.busy || id <= 0) return
        mutable.value = mutable.value.copy(selected = null, detailOpen = true, validation = null, error = null)
        start(false) { current ->
            val room = operations.detail(id)
            current()
            mutable.value = mutable.value.copy(selected = room)
        }
    }

    fun closeDetail() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(selected = null, detailOpen = false, leaveConfirmation = false, error = null)
    }

    fun confirmLeave(show: Boolean) {
        if (!mutable.value.busy && mutable.value.selected?.memberRole != "owner")
            mutable.value = mutable.value.copy(leaveConfirmation = show)
    }

    fun join() {
        val room = mutable.value.selected ?: return
        if (room.isMember || !canMutate()) return
        start(true) { current ->
            val joined = operations.join(room.id)
            current()
            mutable.value = mutable.value.copy(selected = joined, notice = "joined")
            mutationInFlight = false
            reconcile(current)
        }
    }

    fun leave() {
        val room = mutable.value.selected ?: return
        if (!room.isMember || room.memberRole == "owner" || !mutable.value.leaveConfirmation || !canMutate()) return
        mutable.value = mutable.value.copy(leaveConfirmation = false)
        start(true) { current ->
            operations.leave(room.id)
            current()
            mutationInFlight = false
            mutable.value = mutable.value.copy(selected = null, detailOpen = false, notice = "left")
            reconcile(current)
        }
    }

    fun showCreate() {
        if (!mutable.value.busy && canCreateRooms(session.value.user))
            mutable.value = mutable.value.copy(draft = mutable.value.draft ?: RoomDraft(), validation = null, error = null)
    }

    fun updateDraft(value: RoomDraft) {
        if (!mutable.value.busy && mutable.value.draft != null) mutable.value = mutable.value.copy(draft = value, validation = null)
    }

    fun closeCreate() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(draft = null, validation = null, error = null)
    }

    fun create() {
        if (!canMutate() || !canCreateRooms(session.value.user)) return
        val request = mutable.value.draft?.request()
        val invalid = if (request == null) "invalid_room_numbers" else validateRoomCreation(request)
        if (invalid != null) { mutable.value = mutable.value.copy(validation = invalid); return }
        start(true) { current ->
            val created = operations.create(checkNotNull(request))
            current()
            mutationInFlight = false
            mutable.value = mutable.value.copy(draft = null, selected = created, detailOpen = true, notice = "created")
            reconcile(current)
        }
    }

    private fun canMutate(): Boolean {
        val current = session.value
        return current.identity() != null && !current.busy && !current.refreshRequired && !current.storageWarning &&
            current.retryAtMillis <= System.currentTimeMillis() && !mutable.value.busy &&
            !mutable.value.stale && !mutable.value.mutationUncertain
    }

    private suspend fun reconcile(current: () -> Unit) {
        val rooms = operations.list()
        current()
        val selectedId = mutable.value.selected?.id
        var selected = mutable.value.selected
        if (mutable.value.detailOpen && selectedId != null) {
            try { selected = operations.detail(selectedId) }
            catch (failure: ApiFailure) {
                if (failure.status !in setOf(403, 404)) throw failure
                selected = null
            }
        }
        current()
        mutable.value = mutable.value.copy(rooms = rooms, loaded = true, stale = false, selected = selected,
            detailOpen = mutable.value.detailOpen && selected != null, mutationUncertain = false)
    }

    private fun start(mutation: Boolean, action: suspend (() -> Unit) -> Unit) {
        if (mutable.value.busy) return
        val expected = session.value.identity() ?: return
        identity = expected
        val attempt = ++sequence
        mutable.value = mutable.value.copy(busy = true, error = null, validation = null, notice = null)
        mutationInFlight = false
        job = viewModelScope.launch {
            try {
                // App-level foreground validation owns the session. Never race a POST against it.
                val ready = withTimeoutOrNull(30_000) { session.first { !it.busy || it.identity() != expected } }
                    ?: throw ApiFailure(FailureKind.TIMEOUT)
                if (ready.identity() != expected || attempt != sequence) throw CancellationException("Session changed")
                if (ready.refreshRequired || ready.storageWarning) throw ApiFailure(FailureKind.HTTP, code = "csrf_failed")
                if (ready.retryAtMillis > System.currentTimeMillis()) throw ApiFailure(FailureKind.HTTP, 429)
                mutationInFlight = mutation
                action { ensureCurrent(expected, attempt) }
            } catch (cancelled: CancellationException) {
                if (attempt == sequence) mutable.value = mutable.value.copy(stale = true,
                    mutationUncertain = mutable.value.mutationUncertain || mutationInFlight)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (attempt == sequence) mutable.value = mutable.value.copy(error = failure, stale = true,
                    mutationUncertain = mutable.value.mutationUncertain || (mutationInFlight && failure.isAmbiguous()))
            } finally {
                if (attempt == sequence) { mutationInFlight = false; mutable.value = mutable.value.copy(busy = false) }
            }
        }
    }

    private fun ensureCurrent(expected: Identity, attempt: Long) {
        if (attempt != sequence || session.value.identity() != expected) throw CancellationException("Session changed")
    }

    private fun retire() { sequence++; job?.cancel(); job = null; mutationInFlight = false }

    private data class Identity(val server: String?, val generation: Long, val id: Long, val version: Int)
    private fun SessionState.identity(): Identity? = user?.takeIf { phase == SessionPhase.AUTHENTICATED }
        ?.let { Identity(server?.value, generation, it.id, it.sessionVersion) }
    private fun ApiFailure.isAmbiguous() = kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT,
        FailureKind.TLS, FailureKind.REDIRECT, FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) || (status ?: 0) >= 500

    companion object {
        fun factory(operations: RoomOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { RoomsViewModel(operations) }
        }
    }
}
