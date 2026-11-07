package io.github.thiesi.chitchat.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.profile.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A rendered choice, never saved state or authorization to mutate the person. */
class ProfileSelection internal constructor(internal val original: SessionState,
    val request: ProfileRequest, val sourceEntryId: String) {
    fun matches(session: SessionState) = original.profileOwner() != null && original.profileOwner() == session.profileOwner()
}

data class ProfileState(val selection: ProfileSelection? = null, val snapshot: ProfileSnapshot? = null,
    val loading: Boolean = false, val error: ApiFailure? = null) {
    override fun toString() = "ProfileState"
}

private data class ProfileOwner(val server: String, val account: Long, val generation: Long, val version: Int)
private fun SessionState.profileOwner(): ProfileOwner? {
    val person = user ?: return null
    val origin = server ?: return null
    return if (phase == SessionPhase.AUTHENTICATED && !person.guest)
        ProfileOwner(origin.value, person.id, generation, person.sessionVersion) else null
}

/** One explicit read-only selection. Genuine background drops it; foreground never replays it. */
class ProfileViewModel(private val operations: ProfileOperations,
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(ProfileState())
    val state = mutable.asStateFlow()
    private var active = false
    private var sequence = 0L
    private var job: Job? = null

    init { viewModelScope.launch { session.collect { current ->
        val choice = mutable.value.selection
        if (choice != null && (!choice.matches(current) || current.storageWarning)) clear()
    } } }

    fun foreground() { active = true }
    fun background() { active = false; clear() }
    fun open(expected: SessionState, userId: Long, roomId: Long?, sourceEntryId: String): ProfileSelection? {
        if (!active || expected.profileOwner() == null || expected.profileOwner() != session.value.profileOwner() ||
            userId <= 0 || roomId != null && roomId <= 0 || sourceEntryId.isBlank() || !ready()) return null
        val choice = ProfileSelection(expected, ProfileRequest(userId, roomId), sourceEntryId)
        clear()
        mutable.value = ProfileState(choice, loading = true)
        val attempt = sequence
        val work = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = operations.load(choice.original, choice.request)
                if (current(choice, attempt)) {
                    if (result.profile.id != choice.request.userId || result.roomId != choice.request.roomId)
                        throw ApiFailure(FailureKind.INCOMPATIBLE)
                    mutable.value = ProfileState(choice, snapshot = result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (current(choice, attempt)) mutable.value = ProfileState(choice, error = failure)
            } finally {
                if (current(choice, attempt)) {
                    job = null
                    mutable.value = mutable.value.copy(loading = false)
                }
            }
        }
        job = work; work.start()
        return choice
    }

    fun refresh(choice: ProfileSelection): ProfileSelection? {
        if (!selected(choice) || mutable.value.loading) return null
        return open(choice.original, choice.request.userId, choice.request.roomId, choice.sourceEntryId)
    }
    fun close(choice: ProfileSelection) { if (mutable.value.selection === choice) clear() }
    private fun selected(choice: ProfileSelection) = active && mutable.value.selection === choice && choice.matches(session.value)
    private fun current(choice: ProfileSelection, attempt: Long) = sequence == attempt && selected(choice) && !session.value.storageWarning
    private fun ready() = session.value.let { !it.busy && !it.refreshRequired && !it.storageWarning && now() >= it.retryAtMillis }
    private fun clear() { sequence++; job?.cancel(); job = null; mutable.value = ProfileState() }
    override fun onCleared() { active = false; clear(); super.onCleared() }

    companion object {
        fun factory(operations: ProfileOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(operations) }
        }
    }
}
