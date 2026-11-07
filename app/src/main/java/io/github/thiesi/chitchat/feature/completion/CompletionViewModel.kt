package io.github.thiesi.chitchat.feature.completion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.mentions.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CompletionTarget {
    val id: Long
    class Room(override val id: Long) : CompletionTarget
    class Direct(override val id: Long, val username: String) : CompletionTarget
}
private fun CompletionTarget.same(other: CompletionTarget) = when {
    this is CompletionTarget.Room && other is CompletionTarget.Room -> id == other.id
    this is CompletionTarget.Direct && other is CompletionTarget.Direct -> id == other.id && username == other.username
    else -> false
}
private data class CompletionOwner(val server: String, val account: Long, val generation: Long, val version: Int)
private fun SessionState.completionOwner(): CompletionOwner? {
    val person = user ?: return null; val origin = server ?: return null
    return if (phase == SessionPhase.AUTHENTICATED && !person.guest)
        CompletionOwner(origin.value, person.id, generation, person.sessionVersion) else null
}

class CompletionPane internal constructor(internal val original: SessionState, val target: CompletionTarget, val entryId: String) {
    fun matches(session: SessionState) = original.completionOwner() != null && original.completionOwner() == session.completionOwner()
}
sealed interface CompletionCandidate {
    val username: String
    class Person(val id: Long, override val username: String) : CompletionCandidate
    class Broadcast(override val username: String) : CompletionCandidate
}
class CompletionSelection internal constructor(val pane: CompletionPane, val edit: ComposerEdit, val token: MentionToken)
data class CompletionState(val entryId: String? = null, val pane: CompletionPane? = null,
    val selection: CompletionSelection? = null, val candidates: List<CompletionCandidate> = emptyList(),
    val highlighted: Int = 0, val loading: Boolean = false, val failed: Boolean = false) {
    override fun toString() = "CompletionState"
}

/** One foreground pane, bounded ephemeral hints and safe GETs. No send/read/typing operations. */
class CompletionViewModel(private val operations: RoomMentionOperations,
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(CompletionState())
    val state = mutable.asStateFlow()
    private var active = false
    private var sequence = 0L
    private var queryJob: Job? = null
    private var membersJob: Job? = null
    private var members: List<RoomMentionUser> = emptyList()
    private var cycle: NameCompletionCycle? = null

    init { viewModelScope.launch { session.collect { current ->
        val pane = mutable.value.pane
        if (pane != null && (!pane.matches(current) || current.storageWarning)) retire()
    } } }

    fun foreground() { active = true }
    fun background() { active = false; retire(); mutable.value = CompletionState() }
    fun navigate(entryId: String) {
        if (!active || entryId.isBlank() || mutable.value.entryId == entryId) return
        retire(); mutable.value = CompletionState(entryId = entryId)
    }
    fun bind(expected: SessionState, target: CompletionTarget, entryId: String): CompletionPane? {
        if (!active || !ready() || mutable.value.entryId != entryId || expected.completionOwner() == null ||
            expected.completionOwner() != session.value.completionOwner() || target.id <= 0 ||
            target is CompletionTarget.Direct && (target.id == expected.user?.id || target.username.isBlank() || target.username.length > 80)) return null
        val previous = mutable.value.pane
        if (previous != null && previous.matches(expected) && previous.target.same(target) && previous.entryId == entryId) return previous
        retire()
        val pane = CompletionPane(expected, target, entryId)
        mutable.value = CompletionState(entryId = entryId, pane = pane)
        if (target is CompletionTarget.Room) loadMembers(pane, target.id)
        return pane
    }

    fun input(pane: CompletionPane, edit: ComposerEdit) {
        if (!current(pane) || !ready()) return
        clearChoice(); cycle = null
        val token = mentionToken(edit) ?: return
        val choice = CompletionSelection(pane, edit, token)
        val target = pane.target
        if (target is CompletionTarget.Direct) {
            val people = if (target.username.startsWith(token.prefix, ignoreCase = true))
                listOf(CompletionCandidate.Person(target.id, target.username)) else emptyList()
            mutable.value = mutable.value.copy(selection = choice, candidates = people)
            return
        }
        val room = target as CompletionTarget.Room
        val reserved = listOf("room", "here").filter { it.startsWith(token.prefix, ignoreCase = true) }
            .map { CompletionCandidate.Broadcast(it) }
        mutable.value = mutable.value.copy(selection = choice, candidates = reserved, loading = true)
        val attempt = sequence
        val request = RoomMentionRequest(room.id, token.prefix)
        val work = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(150)
                if (!selected(choice, attempt)) return@launch
                val batch = operations.suggestions(pane.original, request)
                validate(batch, request)
                if (selected(choice, attempt)) {
                    // In rooms these reserved tokens always resolve as broadcasts, even if a
                    // returned account hint has the same name. Never label that choice peer-only.
                    val hints=batch.users.map { person -> when (person.username.lowercase()) {
                        "room","here" -> CompletionCandidate.Broadcast(person.username.lowercase())
                        else -> CompletionCandidate.Person(person.id,person.username)
                    } }
                    mutable.value = mutable.value.copy(candidates = (reserved+hints).distinctBy { it.username.lowercase() }.take(ROOM_MENTION_LIMIT))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ApiFailure) { if (selected(choice, attempt)) mutable.value = mutable.value.copy(failed = true) }
            finally {
                if (selected(choice, attempt)) { queryJob = null; mutable.value = mutable.value.copy(loading = false) }
            }
        }
        queryJob = work; work.start()
    }

    fun retry(choice: CompletionSelection) {
        if (selected(choice, sequence) && !mutable.value.loading) input(choice.pane, choice.edit)
    }
    fun dismiss(choice: CompletionSelection) { if (mutable.value.selection === choice) { clearChoice(); cycle = null } }
    fun quiet(pane: CompletionPane) { if (mutable.value.pane === pane) { clearChoice(); cycle = null } }
    fun editorChanged(pane: CompletionPane, edit: ComposerEdit) {
        if (!current(pane)) return
        mutable.value.selection?.let { if (!it.edit.same(edit)) clearChoice() }
        if (cycle?.output?.same(edit) == false) cycle = null
    }
    fun move(choice: CompletionSelection, delta: Int): Boolean {
        val value = mutable.value
        if (!selected(choice, sequence) || value.candidates.isEmpty()) return false
        mutable.value = value.copy(highlighted = Math.floorMod(value.highlighted + delta, value.candidates.size))
        return true
    }
    fun choose(choice: CompletionSelection, candidate: CompletionCandidate, edit: ComposerEdit,
        commit: (ComposerEdit) -> Boolean): Boolean {
        if (!selected(choice, sequence) || !ready() || !choice.edit.same(edit) ||
            mutable.value.candidates.none { it === candidate }) return false
        val replacement = insertMention(edit, choice.token, candidate.username) ?: return false
        if (!commit(replacement)) return false
        // A rejecting/reentrant consumer may have installed another pane or choice.
        if (mutable.value.selection === choice) { clearChoice(); cycle = null }
        return true
    }
    fun tab(pane: CompletionPane, edit: ComposerEdit, recentAndPresent: List<String>, commit: (ComposerEdit) -> Boolean): Boolean {
        if (!current(pane) || !ready()) return false
        val value = mutable.value; val choice = value.selection
        if (choice != null && choice.edit.same(edit) && value.candidates.isNotEmpty())
            return choose(choice, value.candidates[value.highlighted], edit, commit)
        val names = when (val target = pane.target) {
            is CompletionTarget.Direct -> listOf(target.username)
            is CompletionTarget.Room -> recentAndPresent + members.map { it.username }
        }
        val next = cycle?.next(edit) ?: NameCompletionCycle.start(edit, names) ?: return false
        val attempt = sequence
        if (!commit(next.output)) return false
        if (current(pane) && sequence == attempt) { clearChoice(); cycle = next }
        return true
    }

    private fun loadMembers(pane: CompletionPane, room: Long) {
        val request = RoomMentionRequest(room, "", ROOM_COMPLETION_MEMBER_LIMIT)
        val work = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val batch = operations.suggestions(pane.original, request)
                validate(batch, request)
                if (current(pane)) members = batch.users
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ApiFailure) { /* Optional IRC fallback: recent speakers/presence still work. No retry. */ }
            finally { if (current(pane)) membersJob = null }
        }
        membersJob = work; work.start()
    }
    private fun validate(batch: RoomMentionBatch, request: RoomMentionRequest) {
        if (batch.request !== request || batch.prefix != request.prefix || batch.users.size > request.limit ||
            batch.users.map { it.id }.distinct().size != batch.users.size || batch.users.any {
                it.id <= 0 || it.id == session.value.user?.id || it.username.isBlank() || it.username.length > 80 })
            throw ApiFailure(FailureKind.INCOMPATIBLE)
    }
    private fun ready() = session.value.let { !it.busy && !it.refreshRequired && !it.storageWarning && now() >= it.retryAtMillis }
    private fun current(pane: CompletionPane) = active && !session.value.storageWarning && mutable.value.pane === pane && pane.matches(session.value) &&
        mutable.value.entryId == pane.entryId
    private fun selected(choice: CompletionSelection, attempt: Long) = sequence == attempt &&
        mutable.value.selection === choice && current(choice.pane)
    private fun clearChoice() {
        sequence++; queryJob?.cancel(); queryJob = null
        mutable.value = mutable.value.copy(selection = null, candidates = emptyList(), highlighted = 0, loading = false, failed = false)
    }
    private fun retire() {
        clearChoice(); membersJob?.cancel(); membersJob = null; members = emptyList(); cycle = null
        mutable.value = CompletionState(entryId = mutable.value.entryId)
    }
    override fun onCleared() { active = false; retire(); super.onCleared() }
    companion object {
        fun factory(operations: RoomMentionOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { CompletionViewModel(operations) }
        }
    }
}
