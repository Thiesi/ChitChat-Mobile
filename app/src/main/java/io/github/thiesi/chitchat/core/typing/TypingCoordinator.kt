package io.github.thiesi.chitchat.core.typing

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.realtime.RealtimeEvent
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Confined to the supplied main-dispatcher scope. No background transport or retained message text. */
class TypingCoordinator(private val operations: TypingOperations, private val realtime: RealtimeController,
    private val scope: CoroutineScope, private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = System::currentTimeMillis) : TypingController {
    private val mutable = MutableStateFlow(TypingState())
    override val state = mutable.asStateFlow()
    private var identity = operations.session.value.typingAccount()
    private var selection: Selection? = null
    private var foreground = false
    private var seen = 0L
    private var lastSignal: Long? = null
    private var post: Job? = null
    private var postSequence = 0L
    private var expiration: Job? = null
    private val people = mutableMapOf<Long, Entry>()

    init {
        scope.launch { operations.session.collect { session ->
            val next = session.typingAccount()
            if (identity != next) {
                identity = next; selection = null; seen = 0; lastSignal = null
                clear()
            } else if (!allowed()) clear()
            else {
                val ignored = session.chatPolicy?.ignoredUserIds.orEmpty()
                if (selection?.target is TypingTarget.Room && people.keys.removeAll(ignored)) publish()
            }
        } }
        scope.launch { realtime.state.collect { if (!allowed()) clear() } }
        scope.launch { operations.session.map { it.typingAccount() }.distinctUntilChanged().collectLatest { account ->
            if (account == null) return@collectLatest
            // Retire the old subscriber's queue. Cached hints are never fresh evidence for a new identity.
            val cached = realtime.events.replayCache.toSet()
            realtime.events.collect { event ->
                if (event !in cached && account == operations.session.value.typingAccount()) receive(event)
            }
        } }
    }

    fun setForeground(value: Boolean) { scope.launch {
        foreground = value
        if (!value) clear()
    } }

    override fun select(expected: SessionState, target: TypingTarget?, viewing: Boolean, publishing: Boolean) {
        scope.launch {
            if (expected.typingAccount() == null || expected.typingAccount() != operations.session.value.typingAccount()) return@launch
            val next = target?.let { Selection(expected.typingAccount()!!, it, viewing, publishing) }
            if (selection?.identity != next?.identity || selection?.target != next?.target) {
                clear(); lastSignal = null
            }
            selection = next
            if (!allowed()) clear() else publish()
            if (next?.publishing != true) cancelPost()
        }
    }

    override fun input(expected: SessionState, target: TypingTarget, hasContent: Boolean) { scope.launch {
        val selected = selection ?: return@launch
        val session = operations.session.value
        if (!hasContent || !matches(expected, target) || !selected.publishing || !allowed() || session.busy ||
            session.chatPolicy?.mutedEverywhere != null || wall() < session.retryAtMillis || post?.isActive == true) return@launch
        val now = elapsed()
        if (lastSignal?.let { now - it < 4_000 } == true) return@launch
        lastSignal = now // Count attempts, including suppression/failure; never schedule an automatic replay.
        val ticket = ++postSequence
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try { operations.signal(session, target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ApiFailure) { /* Best effort. Shared-session invalidation/backoff still applies. */ }
            finally { if (ticket == postSequence) post = null }
        }
        post = pending; pending.start()
    } }

    override fun sent(expected: SessionState, target: TypingTarget) { scope.launch {
        if (matches(expected, target)) { lastSignal = null; cancelPost() }
    } }

    override fun release(expected: SessionState, target: TypingTarget) { scope.launch {
        if (matches(expected, target)) { selection = null; lastSignal = null; clear() }
    } }

    private fun receive(event: RealtimeEvent) {
        if (operations.session.value.typingAccount() != identity || event.id <= seen) return
        seen = event.id
        if (!allowed()) return
        val selected = selection ?: return
        if (event.type == "typing") {
            val person = try { typingPerson(event) } catch (_: ApiFailure) { return }
            if (person.id == identity?.user || selected.target is TypingTarget.Room &&
                person.id in operations.session.value.chatPolicy!!.ignoredUserIds) return
            if (when (val target = selected.target) {
                    is TypingTarget.Room -> event.roomId != target.id
                    is TypingTarget.Direct -> event.roomId != null || person.id != target.peer
                }) return
            // Local receipt time bounds queued hints without assuming server/device wall clocks agree.
            val age = elapsed() - event.receivedAtMillis
            if (age !in 0 until 6_500) return
            if (people.size >= 100 && person.id !in people) return
            people[person.id] = Entry(person, event.receivedAtMillis + 6_500)
            publish(); expire()
        } else if ((event.type == "room_message" && selected.target is TypingTarget.Room &&
                event.roomId == selected.target.id) || (event.type == "direct_message" &&
                selected.target is TypingTarget.Direct && event.roomId == null &&
                event.actorUserId == selected.target.peer)) {
            event.actorUserId?.let { if (people.remove(it) != null) publish() }
        }
    }

    private fun allowed(): Boolean {
        val session = operations.session.value
        val selected = selection ?: return false
        val connection = realtime.state.value
        return foreground && selected.viewing && selected.identity == session.typingAccount() &&
            session.shareTyping == true && (selected.target is TypingTarget.Direct || session.chatPolicy != null) &&
            !session.refreshRequired && !session.storageWarning &&
            connection.generation == session.generation && connection.phase == RealtimePhase.LIVE && connection.error == null
    }
    private fun matches(expected: SessionState, target: TypingTarget) = expected.typingAccount() != null &&
        selection?.identity == expected.typingAccount() && operations.session.value.typingAccount() == expected.typingAccount() && selection?.target == target
    private fun cancelPost() { postSequence++; post?.cancel(); post = null }
    private fun clear() {
        cancelPost(); expiration?.cancel(); expiration = null; people.clear(); publish()
    }
    private fun publish() {
        mutable.value = TypingState(selection?.target, people.values.map { it.person }.sortedBy { it.username.lowercase() }, identity)
        if (people.isEmpty()) { expiration?.cancel(); expiration = null }
    }
    private fun expire() {
        if (expiration?.isActive == true) return
        expiration = scope.launch {
            while (people.isNotEmpty()) {
                delay((people.values.minOf { it.until } - elapsed()).coerceAtLeast(1))
                if (!allowed()) { clear(); return@launch }
                people.entries.removeAll { it.value.until <= elapsed() }; publish()
            }
        }
    }
    private data class Selection(val identity: TypingAccount, val target: TypingTarget, val viewing: Boolean, val publishing: Boolean)
    private class Entry(val person: TypingPerson, val until: Long)
}
