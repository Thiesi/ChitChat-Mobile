package io.github.thiesi.chitchat.feature.direct

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.direct.*
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

data class DirectAnchor(val id: Long, val offset: Int, val revision: Long = 0)
class DirectDraft(val peer: Long, val name: String?, val body: String, val uncertain: Boolean, val checked: Boolean)
class DirectAttention(val ids: Set<Long> = emptySet(), val additionalMayBeRead: Boolean = false)
data class DirectThreadState(val peerId: Long? = null, val context: DirectContext? = null,
    val messages: List<DirectMessage> = emptyList(), val draft: String = "", val loaded: Boolean = false,
    val busy: Boolean = false, val sending: Boolean = false, val stale: Boolean = true,
    val olderCursor: Long? = null, val mayHaveOlder: Boolean = false, val latestWindow: Boolean = true, val fixedUpper: Long? = null,
    val atLatest: Boolean = false, val scrollRequest: Long = 0, val anchor: DirectAnchor? = null,
    val newerAvailable: Boolean = false, val anchorLost: Boolean = false, val incompleteRange: Boolean = false,
    val error: ApiFailure? = null, val readError: ApiFailure? = null, val validation: String? = null,
    val uncertainSend: Boolean = false, val checkedUnknown: Boolean = false, val uncertainRead: Boolean = false,
    val uncertainBlock: Boolean = false, val confirmedSendId: Long? = null, val draftLimit: Boolean = false,
    val unreadDividerId: Long? = null) { override fun toString() = "DirectThreadState" }

/** One serialized operation per thread: hints queue behind writes, never cancel/replay a POST. */
class DirectThreadViewModel(private val operations: DirectOperations,
    private val realtime: RealtimeController? = null) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(DirectThreadState())
    val state = mutable.asStateFlow()
    private val savedDrafts = MutableStateFlow<Map<Long, DirectDraft>>(emptyMap())
    val drafts = savedDrafts.asStateFlow()
    private val localAttention = MutableStateFlow<Map<Long, DirectAttention>>(emptyMap())
    val attention = localAttention.asStateFlow()
    private var identity = session.value.directIdentity()
    private var sequence = 0L
    private var job: Job? = null
    private var active = false
    private var write: Write? = null
    private var requested = 0L
    private var attempted = 0L
    private var attemptedRead = 0L
    private var viewport: DirectAnchor? = null
    private var visibleNewest: Long? = null
    private var seen = emptySet<Long>()
    private var anchorRevision = 0L
    private enum class Write { SEND, READ, BLOCK }

    init { viewModelScope.launch { session.collect {
        if (it.directIdentity() != identity) {
            retire(); identity = it.directIdentity(); mutable.value = DirectThreadState()
            savedDrafts.value = emptyMap(); localAttention.value = emptyMap(); seen = emptySet()
        } else drain()
    } } }

    fun open(peer: Long) {
        if (!currentIdentity() || peer <= 0 || peer == identity?.user) return
        if (mutable.value.peerId != peer) {
            val foreground = active
            background(); active = foreground; seen = emptySet(); attemptedRead = 0
            viewport = null; visibleNewest = null
            val memo = savedDrafts.value[peer]
            mutable.value = DirectThreadState(peerId = peer, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, checkedUnknown = memo?.checked == true)
        }
        if (!mutable.value.loaded) invalidate() // Entry is not explicit uncertain-send reconciliation/consent.
    }
    fun foreground() { active = true; invalidate() }
    fun background() {
        active = false
        val current = mutable.value
        mutable.value = current.copy(uncertainSend = current.uncertainSend || write == Write.SEND,
            uncertainRead = current.uncertainRead || write == Write.READ,
            uncertainBlock = current.uncertainBlock || write == Write.BLOCK,
            checkedUnknown = false, busy = false, sending = false, stale = true, atLatest = false)
        rememberDraft(); retire(); visibleNewest = null; viewport = null
    }
    fun connectionLost() { mutable.value = mutable.value.copy(stale = true) }
    fun invalidate() { if (currentIdentity()) { requested++; drain() } }
    private fun currentIdentity() = identity != null && identity == session.value.directIdentity()
    private fun ready() = currentIdentity() && !session.value.busy && !session.value.refreshRequired &&
        !session.value.storageWarning && System.currentTimeMillis() >= session.value.retryAtMillis
    private fun current(seq: Long, peer: Long, expected: DirectIdentity?) = seq == sequence &&
        mutable.value.peerId == peer && expected == identity && currentIdentity()
    private suspend fun <T> checked(call: suspend () -> T): T {
        val seq = sequence; val peer = mutable.value.peerId ?: throw CancellationException("No thread")
        val expected = identity
        if (!current(seq, peer, expected)) throw CancellationException("Thread retired")
        return call().also { if (!current(seq, peer, expected)) throw CancellationException("Thread retired") }
    }
    private fun drain() {
        if (!active || !ready() || mutable.value.busy || mutable.value.peerId == null || requested <= attempted ||
            realtime?.state?.value?.phase == RealtimePhase.OFFLINE) return
        attempted = requested
        start { peer -> reload(peer) }
    }
    fun refresh() { start { peer -> reload(peer, latest = mutable.value.uncertainSend, consent = true) } }
    fun latest() { start { peer -> reload(peer, latest = true, consent = true) } }

    private fun start(operation: suspend (Long) -> Unit) {
        val peer = mutable.value.peerId ?: return
        if (!active || !ready() || mutable.value.busy) return
        val seq = ++sequence; val expected = identity
        mutable.value = mutable.value.copy(busy = true, error = null, validation = null)
        val pending = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!current(seq, peer, expected)) return@launch
                operation(peer)
            } catch (_: CancellationException) { if (current(seq, peer, expected)) mutable.value = mutable.value.copy(
                stale = true, uncertainSend = mutable.value.uncertainSend || write == Write.SEND,
                uncertainRead = mutable.value.uncertainRead || write == Write.READ,
                uncertainBlock = mutable.value.uncertainBlock || write == Write.BLOCK) }
            catch (_: IncompleteRange) { if (current(seq, peer, expected)) mutable.value = mutable.value.copy(
                stale = true, incompleteRange = true, newerAvailable = true) }
            catch (failure: ApiFailure) { if (current(seq, peer, expected)) {
                val pending = write
                val inaccessible = pending == null && (failure.status == 404 ||
                    failure.status == 403 && failure.code != "csrf_failed") || failure.kind == FailureKind.INCOMPATIBLE && pending == null
                mutable.value = mutable.value.copy(error = failure.takeUnless { pending == Write.READ }, stale = true,
                    context = mutable.value.context.takeUnless { inaccessible },
                    messages = if (inaccessible) emptyList() else mutable.value.messages,
                    loaded = mutable.value.loaded && !inaccessible,
                    uncertainSend = mutable.value.uncertainSend || pending == Write.SEND && failure.ambiguous(),
                    uncertainBlock = mutable.value.uncertainBlock || pending == Write.BLOCK && failure.ambiguous(),
                    uncertainRead = mutable.value.uncertainRead || pending == Write.READ && failure.ambiguous(),
                    readError = if (pending == Write.READ) failure else mutable.value.readError)
            } }
            finally { if (current(seq, peer, expected)) {
                write = null; job = null
                mutable.value = mutable.value.copy(busy = false, sending = false)
                rememberDraft(); drain(); maybeRead()
            } }
        }
        job = pending; pending.start()
    }

    /** Canonical history, not event payload reduction or a guessed around-message route. */
    private suspend fun reload(peer: Long, latest: Boolean = false, consent: Boolean = false) {
        attempted = maxOf(attempted, requested)
        val context = checked { operations.context(peer) }
        mutable.value = mutable.value.copy(context = context, uncertainBlock = false)
        val old = mutable.value
        val lower = old.olderCursor ?: old.messages.firstOrNull()?.id
        val upper = old.fixedUpper ?: old.messages.lastOrNull()?.id
        var live = latest || old.latestWindow
        val window = if (latest || !old.loaded || lower == null || live && old.messages.isEmpty()) {
            live = true
            val page = checked { operations.history(peer) }
            DirectWindow(page.messages, true, page.olderCursor, page.mayHaveOlder)
        } else {
            if (!live && upper == null) throw IncompleteRange()
            var value = checked { operations.window(peer, lower, if (live) null else upper) }
            if (live && (!value.covered || !old.atLatest && value.messages.size > 500)) {
                if (upper == null) throw IncompleteRange()
                value = checked { operations.window(peer, lower, upper) }
                live = false
            }
            value
        }
        if (!window.covered) throw IncompleteRange()
        val rows = window.messages.takeLast(500)
        val trimmed = window.messages.size > rows.size
        val previous = old.messages.lastOrNull()?.id
        if (old.loaded && previous != null) updateAttention(peer,
            rows.filter { !it.outgoing && it.id > previous && it.id !in seen }.map { it.id }.toSet())
        val anchor = viewport?.let { captured ->
            val survivor = rows.firstOrNull { it.id == captured.id } ?: rows.firstOrNull { it.id >= captured.id } ?: rows.lastOrNull()
            survivor?.let { DirectAnchor(it.id, captured.offset.takeIf { _ -> it.id == captured.id } ?: 0, ++anchorRevision) }
        }
        val scroll = latest || !old.loaded || old.atLatest && live
        mutable.value = mutable.value.copy(context = context, messages = rows, loaded = true, stale = false,
            olderCursor = if (trimmed) rows.firstOrNull()?.id else window.olderCursor,
            mayHaveOlder = trimmed || window.mayHaveOlder, latestWindow = live,
            fixedUpper = if (live) null else upper,
            newerAvailable = !live, incompleteRange = false, error = null,
            anchorLost = viewport != null && anchor?.id != viewport?.id,
            anchor = if (scroll) null else anchor,
            scrollRequest = mutable.value.scrollRequest + if (scroll) 1 else 0,
            checkedUnknown = mutable.value.checkedUnknown || consent && mutable.value.uncertainSend,
            unreadDividerId = old.unreadDividerId ?: rows.firstOrNull { !it.outgoing && it.readAt == null }?.id)
        seen = seen.intersect(rows.map { it.id }.toSet())
    }
    fun older() {
        val before = mutable.value.olderCursor ?: return
        if (!mutable.value.mayHaveOlder || mutable.value.stale) return
        start { peer ->
            val page = checked { operations.history(peer, before) }
            val existing = mutable.value
            val all = (page.messages + existing.messages).distinctBy { it.id }.sortedBy { it.id }
            val rows = all.take(500)
            mutable.value = existing.copy(messages = rows, olderCursor = page.olderCursor, mayHaveOlder = page.mayHaveOlder,
                latestWindow = existing.latestWindow && all.size <= 500, newerAvailable = existing.newerAvailable || all.size > 500,
                fixedUpper = if (existing.latestWindow && all.size <= 500) null else
                    if (all.size > 500) rows.lastOrNull()?.id else existing.fixedUpper ?: rows.lastOrNull()?.id,
                atLatest = false, anchor = viewport?.copy(revision = ++anchorRevision))
        }
    }

    fun viewport(anchor: DirectAnchor?, incoming: Set<Long>, newest: Long?, edge: Boolean) {
        if (!active || !currentIdentity() || mutable.value.peerId == null) return
        viewport = anchor; visibleNewest = newest
        val present = mutable.value.messages.filter { !it.outgoing }.map { it.id }.toSet()
        val visible = incoming.intersect(present)
        seen = (seen + visible).intersect(present)
        val peer = mutable.value.peerId!!
        localAttention.value[peer]?.let { attention ->
            setAttention(peer, DirectAttention(attention.ids - visible, attention.additionalMayBeRead))
        }
        mutable.value = mutable.value.copy(atLatest = edge && mutable.value.latestWindow)
        maybeRead()
    }
    private fun maybeRead() {
        val value = mutable.value; val newest = value.messages.lastOrNull()?.id ?: return
        if (!active || !ready() || value.busy || value.stale || !value.latestWindow || !value.atLatest ||
            visibleNewest != newest || attemptedRead >= newest || value.readError != null || value.uncertainRead ||
            value.messages.none { !it.outgoing && it.readAt == null }) return
        attemptedRead = newest
        val renderedUnread = value.messages.count { !it.outgoing && it.readAt == null && it.id in seen }
        val unrendered = value.messages.filter { !it.outgoing && it.readAt == null && it.id !in seen }.map { it.id }.toSet()
        start { peer ->
            write = Write.READ
            val updated = checked { operations.read(peer) }
            write = null
            if (updated > 0) updateAttention(peer, unrendered, updated > renderedUnread)
            mutable.value = mutable.value.copy(readError = null, uncertainRead = false)
            reload(peer) // Required even for updated=0; it never supplies a bounded rendered cursor.
        }
    }
    fun retryRead() {
        start { peer ->
            reload(peer)
            attemptedRead = 0
            mutable.value = mutable.value.copy(readError = null, uncertainRead = false)
        }
    }
    fun acknowledgeAdditionalRead() {
        val peer = mutable.value.peerId ?: return
        localAttention.value[peer]?.let { setAttention(peer, DirectAttention(it.ids)) }
    }
    private fun updateAttention(peer: Long, incoming: Set<Long>, extra: Boolean = false) {
        val previous = localAttention.value[peer] ?: DirectAttention()
        setAttention(peer, DirectAttention((previous.ids + incoming).filter { it !in seen }.takeLast(500).toSet(),
            previous.additionalMayBeRead || extra))
    }
    private fun setAttention(peer: Long, value: DirectAttention) {
        val others = localAttention.value - peer
        localAttention.value = if (value.ids.isEmpty() && !value.additionalMayBeRead) others else
            (others.entries.toList().takeLast(99).associate { it.toPair() } + (peer to value))
    }

    fun canEditDraft() = mutable.value.peerId in savedDrafts.value || savedDrafts.value.size < 100
    fun updateDraft(value: String) {
        if (mutable.value.sending || !currentIdentity()) return
        if (!canEditDraft() && value.isNotEmpty()) { mutable.value = mutable.value.copy(draftLimit = true); return }
        mutable.value = mutable.value.copy(draft = value, validation = null, draftLimit = false)
        rememberDraft()
    }
    fun discardSavedDraft(peer: Long) {
        if (!currentIdentity() || mutable.value.sending) return
        savedDrafts.value = savedDrafts.value - peer
        if (mutable.value.peerId == peer) mutable.value = mutable.value.copy(draft = "", uncertainSend = false,
            checkedUnknown = false, draftLimit = false)
    }
    private fun rememberDraft() {
        if (!currentIdentity()) return
        val value = mutable.value; val peer = value.peerId ?: return
        savedDrafts.value = if (value.draft.isEmpty() && !value.uncertainSend) savedDrafts.value - peer else
            savedDrafts.value + (peer to DirectDraft(peer, value.context?.peer?.username, value.draft,
                value.uncertainSend, value.checkedUnknown))
    }
    fun canSend() = active && ready() && mutable.value.let { !it.busy && !it.stale && it.loaded &&
        !it.uncertainSend && !it.uncertainBlock && it.context?.relationship?.messagingAvailable == true &&
        it.context.mutedEverywhere == null }
    fun send() {
        if (!canSend()) return
        val body = mutable.value.draft
        validateDirectBody(body)?.let { mutable.value = mutable.value.copy(validation = it); return }
        mutable.value = mutable.value.copy(sending = true, confirmedSendId = null)
        start { peer ->
            write = Write.SEND
            val confirmed = checked { operations.send(peer, body) }
            write = null
            mutable.value = mutable.value.copy(draft = "", sending = false, uncertainSend = false,
                checkedUnknown = false, confirmedSendId = confirmed)
            rememberDraft()
            reload(peer, latest = true)
        }
    }
    fun allowDuplicateRisk() {
        if (mutable.value.uncertainSend && mutable.value.checkedUnknown && !mutable.value.busy) {
            mutable.value = mutable.value.copy(uncertainSend = false, checkedUnknown = false)
            rememberDraft()
        }
    }
    fun setBlocked(blocked: Boolean) {
        if (mutable.value.context == null || mutable.value.uncertainBlock) return
        start { peer ->
            write = Write.BLOCK
            val relationship = checked { operations.setBlocked(peer, blocked) }
            write = null
            val context = mutable.value.context!!
            mutable.value = mutable.value.copy(context = DirectContext(context.peer, relationship, context.privacy,
                context.display, context.mutedEverywhere), uncertainBlock = false)
            reload(peer)
        }
    }
    private fun retire() { sequence++; job?.cancel(); job = null; write = null; requested = 0; attempted = 0 }
    private class IncompleteRange : Exception()
    private fun ApiFailure.ambiguous() = kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.TLS,
        FailureKind.REDIRECT, FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) || (status ?: 0) >= 500
    companion object {
        fun factory(operations: DirectOperations, realtime: RealtimeController? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { DirectThreadViewModel(operations, realtime) }
        }
    }
}
