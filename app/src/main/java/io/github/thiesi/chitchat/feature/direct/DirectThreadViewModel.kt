package io.github.thiesi.chitchat.feature.direct

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.mutations.MessageMutationOperations
import io.github.thiesi.chitchat.core.mutations.validateEditedBody
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.typing.TypingController
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.targets.TargetSnapshot
import io.github.thiesi.chitchat.core.targets.TargetRequest
import io.github.thiesi.chitchat.core.targets.withCurrentPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DirectAnchor(val id: Long, val offset: Int, val revision: Long = 0)
class DirectDraft(val peer: Long, val name: String?, val body: String, val uncertain: Boolean, val checked: Boolean,
    val composerReply: ComposerReply? = null)
data class DirectAttention(val ids: Set<Long> = emptySet(), val additionalMayBeRead: Boolean = false) {
    override fun toString() = "DirectAttention"
}
data class DirectThreadState(val peerId: Long? = null, val context: DirectContext? = null,
    val messages: List<DirectMessage> = emptyList(), val draft: String = "", val loaded: Boolean = false,
    val busy: Boolean = false, val sending: Boolean = false, val stale: Boolean = true,
    val olderCursor: Long? = null, val mayHaveOlder: Boolean = false, val latestWindow: Boolean = true, val fixedUpper: Long? = null,
    val highlightedMessageId: Long? = null,
    val atLatest: Boolean = false, val scrollRequest: Long = 0, val anchor: DirectAnchor? = null,
    val newerAvailable: Boolean = false, val anchorLost: Boolean = false, val incompleteRange: Boolean = false,
    val error: ApiFailure? = null, val readError: ApiFailure? = null, val validation: String? = null,
    val uncertainSend: Boolean = false, val checkedUnknown: Boolean = false, val uncertainRead: Boolean = false,
    val uncertainBlock: Boolean = false, val confirmedSendId: Long? = null, val draftLimit: Boolean = false,
    val unreadDividerId: Long? = null, val composerReply: ComposerReply? = null,
    val authorDialog: DirectAuthorDialog? = null, val authorWriting: Boolean = false,
    val authorUnknown: DirectAuthorUnknown? = null, val authorLimit: Boolean = false,
    val authorNotice: String? = null) { override fun toString() = "DirectThreadState" }

/** One serialized operation per thread: hints queue behind writes, never cancel/replay a POST. */
class DirectThreadViewModel internal constructor(private val operations: DirectOperations,
    val realtime: RealtimeController? = null,
    val typing: TypingController? = null,
    private val mutations: MessageMutationOperations? = null) : ViewModel() {
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
    private enum class Write { SEND, READ, BLOCK, AUTHOR }
    private var authorPosting: DirectAuthorRequest? = null
    private val unknownAuthors = mutableMapOf<Long, DirectAuthorUnknown>()

    init { viewModelScope.launch { session.collect {
        if (it.directIdentity() != identity) {
            retire(); identity = it.directIdentity(); mutable.value = DirectThreadState()
            savedDrafts.value = emptyMap(); localAttention.value = emptyMap(); unknownAuthors.clear(); seen = emptySet()
        } else drain()
    } } }

    fun open(peer: Long, expected: SessionState = session.value) {
        val owner = expected.directIdentity() ?: return
        if (owner != session.value.directIdentity() || peer <= 0 || peer == owner.user) return
        if (owner != identity) {
            retire(); identity = owner; mutable.value = DirectThreadState()
            savedDrafts.value = emptyMap(); localAttention.value = emptyMap(); unknownAuthors.clear(); seen = emptySet()
        }
        if (mutable.value.peerId != peer) {
            val foreground = active
            background(); active = foreground; seen = emptySet(); attemptedRead = 0
            viewport = null; visibleNewest = null
            val memo = savedDrafts.value[peer]
            mutable.value = DirectThreadState(peerId = peer, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, checkedUnknown = memo?.checked == true, composerReply = memo?.composerReply,
                authorUnknown = unknownAuthors[peer])
        }
        if (!mutable.value.loaded) invalidate() // Entry is not explicit uncertain-send reconciliation/consent.
    }
    fun foreground() { active = true; invalidate() }
    fun background() {
        active = false
        authorPosting?.let(::rememberUnknownAuthor)
        val current = mutable.value
        mutable.value = current.copy(uncertainSend = current.uncertainSend || write == Write.SEND,
            uncertainRead = current.uncertainRead || write == Write.READ,
            uncertainBlock = current.uncertainBlock || write == Write.BLOCK,
            checkedUnknown = false, busy = false, sending = false, stale = true, atLatest = false,
            authorDialog = null, authorWriting = false)
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

    /** Same-peer reply adoption; it is neither a whole-peer read nor an unknown-send history check. */
    fun canAdoptTarget(snapshot: TargetSnapshot.DirectMessage): Boolean = active && ready() &&
        snapshot.matches(session.value) && snapshot.available && snapshot.context.peer.id == mutable.value.peerId &&
        !mutable.value.busy && write == null && mutable.value.authorDialog == null && authorPosting == null

    fun belongsTo(expected: SessionState) = expected.directIdentity() != null && expected.directIdentity() == identity &&
        currentIdentity()

    private fun selected(expected: SessionState, peer: Long?) = belongsTo(expected) && peer != null &&
        mutable.value.peerId == peer

    fun targetDenied(request: TargetRequest, error: ApiFailure) {
        val target = request.target as? MessageTarget.DirectMessage ?: return
        if (!request.matches(session.value) || !selected(request.original, target.peerId) || authorPosting != null ||
            error.status !in setOf(403, 404) || error.code == "csrf_failed") return
        val old = mutable.value; val pending = write
        retire()
        mutable.value = old.copy(context = null, messages = emptyList(), loaded = false, stale = true,
            busy = false, sending = false, atLatest = false, anchor = null, fixedUpper = null, highlightedMessageId = null,
            olderCursor = null, mayHaveOlder = false, error = error, checkedUnknown = false,
            uncertainSend = old.uncertainSend || pending == Write.SEND,
            uncertainRead = old.uncertainRead || pending == Write.READ,
            uncertainBlock = old.uncertainBlock || pending == Write.BLOCK, authorDialog = null, authorWriting = false)
        rememberDraft()
    }

    fun adoptTarget(snapshot: TargetSnapshot.DirectMessage): Boolean {
        val fresh = snapshot.withCurrentPolicy(session.value) as? TargetSnapshot.DirectMessage ?: return false
        if (!canAdoptTarget(fresh)) return false
        return installTarget(fresh)
    }

    /** Root must bind preparation to the original entry and consume/navigate in the same main-thread call. */
    fun canPrepareTarget(snapshot: TargetSnapshot.DirectMessage): Boolean = ready() &&
        snapshot.matches(session.value) && snapshot.available && write == null &&
        mutable.value.authorDialog == null && authorPosting == null

    fun prepareTarget(snapshot: TargetSnapshot.DirectMessage): Boolean {
        val fresh = snapshot.withCurrentPolicy(session.value) as? TargetSnapshot.DirectMessage ?: return false
        if (!canPrepareTarget(fresh)) return false
        background() // Never cancel a send/read/block POST to serve a notification link.
        if (mutable.value.peerId != fresh.context.peer.id) {
            seen = emptySet(); attemptedRead = 0; viewport = null; visibleNewest = null
            val memo = savedDrafts.value[fresh.context.peer.id]
            mutable.value = DirectThreadState(peerId = fresh.context.peer.id, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, composerReply = memo?.composerReply,
                authorUnknown = unknownAuthors[fresh.context.peer.id]) // A target GET is never checkedUnknown consent.
        }
        return installTarget(fresh)
    }

    private fun installTarget(fresh: TargetSnapshot.DirectMessage): Boolean {
        val target = fresh.request.target as? MessageTarget.DirectMessage ?: return false
        val value = mutable.value
        sequence++; job?.cancel(); job = null // No active write: preserve queued invalidation counters.
        viewport = DirectAnchor(target.id, 0, ++anchorRevision); visibleNewest = null; attemptedRead = 0
        mutable.value = value.copy(context = fresh.context, messages = fresh.page.messages, loaded = true,
            stale = false, latestWindow = false, fixedUpper = target.id, highlightedMessageId = target.id,
            olderCursor = fresh.page.olderCursor, mayHaveOlder = fresh.page.mayHaveOlder,
            atLatest = false, anchor = viewport, anchorLost = false, newerAvailable = true, incompleteRange = false,
            checkedUnknown = false, error = value.error.takeIf { value.uncertainSend || value.uncertainBlock }, validation = null)
        seen = seen.intersect(fresh.page.messages.map { it.id }.toSet())
        rememberDraft(); drain()
        return true
    }

    private fun authorRowCurrent(request: DirectAuthorRequest): Boolean {
        val value = mutable.value
        val user = session.value.user ?: return false
        val context = value.context ?: return false
        val row = request.original
        val policy = session.value.chatPolicy
        val mute = if (policy != null) policy.mutedEverywhere else context.mutedEverywhere
        return mutations != null && active && ready() && selected(request.expected, request.peerId) &&
            value.loaded && !value.stale && context.peer.id == request.peerId &&
            row.id > 0 && row.sender.id == user.id && row.recipient.id == request.peerId && row.outgoing &&
            !row.deleted && row.body != null && value.messages.any { it === row } && when (request.kind) {
                AuthorChangeKind.EDIT -> row.canEdit && context.relationship.messagingAvailable && mute == null
                AuthorChangeKind.DELETE -> row.canDelete // Blocking/mute does not revoke author deletion.
            }
    }

    fun canBeginAuthor(expected: SessionState, peer: Long?, message: DirectMessage, kind: AuthorChangeKind): Boolean =
        peer != null && mutable.value.authorDialog == null && !mutable.value.busy &&
            unknownAuthors[peer] == null && unknownAuthors.size < 100 &&
            authorRowCurrent(DirectAuthorRequest(expected, peer, message, kind))

    fun beginAuthor(expected: SessionState, peer: Long?, message: DirectMessage, kind: AuthorChangeKind): Boolean {
        if (!canBeginAuthor(expected, peer, message, kind)) {
            if (selected(expected, peer) && unknownAuthors.size >= 100) mutable.value = mutable.value.copy(authorLimit = true)
            return false
        }
        val request = DirectAuthorRequest(expected, checkNotNull(peer), message, kind)
        visibleNewest = null
        mutable.value = mutable.value.copy(authorDialog = DirectAuthorDialog(request,
            if (kind == AuthorChangeKind.EDIT) message.body.orEmpty() else ""), atLatest = false,
            authorLimit = false, authorNotice = null)
        return true
    }

    fun authorAvailable(request: DirectAuthorRequest) = mutable.value.authorDialog?.request === request &&
        mutable.value.authorDialog?.checked != true && unknownAuthors[request.peerId] == null && authorRowCurrent(request)

    fun authorTargetCurrent(request: DirectAuthorRequest) = mutable.value.authorDialog?.request === request &&
        active && selected(request.expected, request.peerId) && mutable.value.messages.any { it === request.original && !it.deleted }

    fun authorCapacityFull() = unknownAuthors.size >= 100 && mutable.value.peerId !in unknownAuthors

    private fun currentAuthorRow(request: DirectAuthorRequest) = mutable.value.messages.firstOrNull {
        active && selected(request.expected, request.peerId) && it.id == request.original.id &&
            it.sender.id == session.value.user?.id && it.recipient.id == request.peerId && !it.deleted
    }
    fun authorCurrentPreview(request: DirectAuthorRequest): String? = currentAuthorRow(request)?.body

    fun canReviewAuthor(request: DirectAuthorRequest): Boolean {
        val dialog = mutable.value.authorDialog ?: return false
        val current = currentAuthorRow(request) ?: return false
        return dialog.request === request && dialog.checked && request.kind == AuthorChangeKind.EDIT &&
            !mutable.value.busy && unknownAuthors[request.peerId] == null && !authorCapacityFull() &&
            authorRowCurrent(DirectAuthorRequest(request.expected, request.peerId, current, AuthorChangeKind.EDIT))
    }
    fun reviewAuthorProposal(request: DirectAuthorRequest): Boolean {
        if (!canReviewAuthor(request)) return false
        val body = checkNotNull(mutable.value.authorDialog).body
        mutable.value = mutable.value.copy(authorDialog = DirectAuthorDialog(DirectAuthorRequest(request.expected,
            request.peerId, checkNotNull(currentAuthorRow(request)), AuthorChangeKind.EDIT), body), error = null)
        return true
    }

    private fun rebindUnchangedAuthor(rows: List<DirectMessage>) {
        val request = mutable.value.authorDialog?.request ?: return
        if (!selected(request.expected, request.peerId)) return
        val original = request.original
        val current = rows.firstOrNull { it.id == original.id && it.sender.id == original.sender.id &&
            it.recipient.id == original.recipient.id } ?: return
        if (!current.deleted && current.body == original.body && current.createdAt == original.createdAt &&
            current.editedAt == original.editedAt && current.outgoing == original.outgoing) request.original = current
    }

    fun updateAuthorBody(request: DirectAuthorRequest, body: String) {
        if (!mutable.value.busy && authorAvailable(request) && request.kind == AuthorChangeKind.EDIT)
            mutable.value = mutable.value.copy(authorDialog = DirectAuthorDialog(request, body))
    }
    fun dismissAuthor(request: DirectAuthorRequest) {
        if (mutable.value.authorDialog?.request === request && selected(request.expected, request.peerId) && !mutable.value.busy)
            mutable.value = mutable.value.copy(authorDialog = null, atLatest = false)
    }

    fun submitAuthor(request: DirectAuthorRequest) {
        if (!authorAvailable(request) || mutable.value.busy) return
        val body = mutable.value.authorDialog?.body.orEmpty()
        if (request.kind == AuthorChangeKind.EDIT) {
            val invalid = validateEditedBody(body) ?: "message_unchanged".takeIf { body.phpTrim() == request.original.body }
            if (invalid != null) {
                mutable.value = mutable.value.copy(authorDialog = DirectAuthorDialog(request, body, invalid)); return
            }
        }
        start { peer ->
            if (!authorAvailable(request)) throw CancellationException("Author selection changed")
            write = Write.AUTHOR; authorPosting = request
            mutable.value = mutable.value.copy(authorWriting = true)
            val result = checked { when (request.kind) {
                AuthorChangeKind.EDIT -> checkNotNull(mutations).editDirect(request.expected, request.original, body)
                AuthorChangeKind.DELETE -> checkNotNull(mutations).deleteDirect(request.expected, request.original)
            } }
            if (result.id != request.original.id || result.sender.id != request.original.sender.id ||
                result.recipient.id != request.original.recipient.id || !result.outgoing ||
                request.kind == AuthorChangeKind.DELETE && !result.deleted) throw ApiFailure(FailureKind.INCOMPATIBLE)
            // The acknowledged write is confirmed even if the following canonical GET fails.
            authorPosting = null; write = null
            mutable.value = mutable.value.copy(messages = applyDirectAuthorResult(mutable.value.messages, result),
                authorDialog = null, authorWriting = false, stale = true,
                authorNotice = if (request.kind == AuthorChangeKind.EDIT) "author_edited" else "author_deleted")
            reload(peer) // No uncertain-SEND consent; preserve the existing window/anchor/draft.
        }
    }

    fun checkAuthorHistory(expected: SessionState, original: DirectAuthorUnknown) {
        if (!active || !selected(expected, original.peerId) || unknownAuthors[original.peerId] !== original || mutable.value.busy) return
        val editor = mutable.value.authorDialog?.takeIf { it.request.peerId == original.peerId &&
            it.request.original.id == original.messageId && it.request.kind == original.kind }
        start { peer ->
            reload(peer) // GET only; neither entry/passive sync nor SEND recovery clears this marker.
            if (unknownAuthors[original.peerId] === original) {
                unknownAuthors.remove(original.peerId)
                val retained = editor?.takeIf { mutable.value.authorDialog?.request === it.request }
                mutable.value = mutable.value.copy(authorUnknown = null, authorLimit = false, authorNotice = "author_checked",
                    authorDialog = if (retained != null && retained.request.kind == AuthorChangeKind.EDIT)
                        DirectAuthorDialog(retained.request, retained.body, checked = true)
                    else if (retained != null) null else mutable.value.authorDialog)
            }
        }
    }

    private fun rememberUnknownAuthor(request: DirectAuthorRequest) {
        val marker = DirectAuthorUnknown(request.peerId, request.original.id, request.kind)
        unknownAuthors[request.peerId] = marker // Capacity was checked before this single serialized POST.
        if (mutable.value.peerId == request.peerId) mutable.value = mutable.value.copy(authorUnknown = marker)
    }

    private fun start(operation: suspend (Long) -> Unit) {
        val peer = mutable.value.peerId ?: return
        if (!active || !ready() || mutable.value.busy) return
        val seq = ++sequence; val expected = identity
        mutable.value = mutable.value.copy(busy = true, error = null, validation = null, authorNotice = null)
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
                    authorDialog = mutable.value.authorDialog.takeUnless { inaccessible },
                    loaded = mutable.value.loaded && !inaccessible,
                    uncertainSend = mutable.value.uncertainSend || pending == Write.SEND && failure.ambiguous(),
                    uncertainBlock = mutable.value.uncertainBlock || pending == Write.BLOCK && failure.ambiguous(),
                    uncertainRead = mutable.value.uncertainRead || pending == Write.READ && failure.ambiguous(),
                    readError = if (pending == Write.READ) failure else mutable.value.readError)
            } }
            finally { if (current(seq, peer, expected)) {
                authorPosting?.let(::rememberUnknownAuthor)
                authorPosting = null
                write = null; job = null
                mutable.value = mutable.value.copy(busy = false, sending = false, authorWriting = false)
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
        val targetLost = old.highlightedMessageId != null && rows.none { it.id == old.highlightedMessageId }
        rebindUnchangedAuthor(rows)
        mutable.value = mutable.value.copy(context = context, messages = rows, loaded = true, stale = false,
            olderCursor = if (trimmed) rows.firstOrNull()?.id else window.olderCursor,
            mayHaveOlder = trimmed || window.mayHaveOlder, latestWindow = live,
            fixedUpper = if (live) null else upper,
            highlightedMessageId = old.highlightedMessageId.takeUnless { latest || targetLost },
            newerAvailable = !live || !scroll && (old.newerAvailable ||
                previous != null && rows.lastOrNull()?.id?.let { it > previous } == true),
            incompleteRange = false, error = null,
            anchorLost = !live && old.anchorLost || viewport != null && anchor?.id != viewport?.id || targetLost,
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
            val targetLost = existing.highlightedMessageId != null && rows.none { it.id == existing.highlightedMessageId }
            rebindUnchangedAuthor(rows)
            mutable.value = existing.copy(messages = rows, olderCursor = page.olderCursor, mayHaveOlder = page.mayHaveOlder,
                latestWindow = existing.latestWindow && all.size <= 500, newerAvailable = existing.newerAvailable || all.size > 500,
                fixedUpper = if (existing.latestWindow && all.size <= 500) null else
                    if (all.size > 500) rows.lastOrNull()?.id else existing.fixedUpper ?: rows.lastOrNull()?.id,
                highlightedMessageId = existing.highlightedMessageId.takeUnless { targetLost },
                anchorLost = existing.anchorLost || targetLost,
                atLatest = false, anchor = viewport?.copy(revision = ++anchorRevision))
        }
    }

    fun viewport(anchor: DirectAnchor?, incoming: Set<Long>, newest: Long?, edge: Boolean) {
        if (!active || !currentIdentity() || mutable.value.peerId == null) return
        val authorModal = mutable.value.authorDialog != null
        viewport = anchor; visibleNewest = newest.takeUnless { authorModal }
        val present = mutable.value.messages.filter { !it.outgoing }.map { it.id }.toSet()
        val visible = if (authorModal) emptySet() else incoming.intersect(present)
        seen = (seen + visible).intersect(present)
        val peer = mutable.value.peerId!!
        localAttention.value[peer]?.let { attention ->
            setAttention(peer, DirectAttention(attention.ids - visible, attention.additionalMayBeRead))
        }
        mutable.value = mutable.value.copy(atLatest = edge && !authorModal && mutable.value.latestWindow,
            newerAvailable = mutable.value.newerAvailable && !(edge && !authorModal && mutable.value.latestWindow))
        maybeRead()
    }
    private fun maybeRead() {
        val value = mutable.value; val newest = value.messages.lastOrNull()?.id ?: return
        if (!active || !ready() || value.busy || value.authorDialog != null || value.stale || !value.latestWindow || !value.atLatest ||
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
        updateDraft(session.value, mutable.value.peerId, value)
    }
    fun updateDraft(expected: SessionState, peer: Long?, value: String): Boolean {
        if (!selected(expected, peer) || mutable.value.sending || mutable.value.authorDialog != null) return false
        if (!canEditDraft() && value.isNotEmpty()) { mutable.value = mutable.value.copy(draftLimit = true); return false }
        mutable.value = mutable.value.copy(draft = value, validation = null, draftLimit = false)
        rememberDraft(); return true
    }
    fun canUseMessage(expected: SessionState, peer: Long?, message: DirectMessage): Boolean {
        val value = mutable.value
        return active && selected(expected, peer) && ready() && value.context != null && value.authorDialog == null &&
            value.messages.any { it === message } && !message.deleted && message.id > 0
    }
    fun canReplyTo(expected: SessionState, peer: Long?, message: DirectMessage) =
        canUseMessage(expected, peer, message) && canSend() && canEditDraft() && !mutable.value.sending
    fun replyTo(expected: SessionState, peer: Long?, message: DirectMessage): Boolean {
        if (!canReplyTo(expected, peer, message)) return false
        mutable.value = mutable.value.copy(composerReply = ComposerReply(message.id), validation = null,
            error = mutable.value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft(); return true
    }
    fun cancelReply(expected: SessionState, peer: Long?, original: ComposerReply) {
        val value = mutable.value
        if (!active || !selected(expected, peer) || value.sending || value.authorDialog != null || value.composerReply !== original) return
        mutable.value = value.copy(composerReply = null, draftLimit = false,
            error = value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft()
    }
    fun discardSavedDraft(peer: Long) {
        if (!currentIdentity() || mutable.value.sending || mutable.value.authorDialog != null || authorPosting != null) return
        savedDrafts.value = savedDrafts.value - peer
        if (mutable.value.peerId == peer) mutable.value = mutable.value.copy(draft = "", composerReply = null, uncertainSend = false,
            checkedUnknown = false, draftLimit = false)
    }
    private fun rememberDraft() {
        if (!currentIdentity()) return
        val value = mutable.value; val peer = value.peerId ?: return
        savedDrafts.value = if (value.draft.isEmpty() && !value.uncertainSend && value.composerReply == null) savedDrafts.value - peer else
            savedDrafts.value + (peer to DirectDraft(peer, value.context?.peer?.username, value.draft,
                value.uncertainSend, value.checkedUnknown, value.composerReply))
    }
    fun canSend() = active && ready() && mutable.value.let { !it.busy && it.authorDialog == null && !it.stale && it.loaded &&
        !it.uncertainSend && !it.uncertainBlock && it.context?.relationship?.messagingAvailable == true &&
        it.context.mutedEverywhere == null }
    fun send(expected: SessionState, peer: Long?) { if (selected(expected, peer)) send() }
    fun send() {
        if (!canSend()) return
        val body = mutable.value.draft
        val reply = mutable.value.composerReply
        val typingSession = session.value
        validateDirectBody(body)?.let { mutable.value = mutable.value.copy(validation = it); return }
        mutable.value = mutable.value.copy(sending = true, confirmedSendId = null)
        start { peer ->
            write = Write.SEND
            val confirmed = checked { operations.send(peer, body, reply?.messageId) }
            typing?.sent(typingSession, TypingTarget.Direct(peer))
            write = null
            mutable.value = mutable.value.copy(draft = "", composerReply = null, sending = false, uncertainSend = false,
                checkedUnknown = false, confirmedSendId = confirmed)
            rememberDraft()
            reload(peer, latest = true)
        }
    }
    fun allowDuplicateRisk() {
        if (mutable.value.uncertainSend && mutable.value.checkedUnknown && !mutable.value.busy && mutable.value.authorDialog == null) {
            mutable.value = mutable.value.copy(uncertainSend = false, checkedUnknown = false)
            rememberDraft()
        }
    }
    fun setBlocked(expected: SessionState, peer: Long?, blocked: Boolean) {
        if (selected(expected, peer)) setBlocked(blocked)
    }
    fun setBlocked(blocked: Boolean) {
        if (mutable.value.context == null || mutable.value.uncertainBlock || mutable.value.authorDialog != null) return
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
    private fun retire() { sequence++; job?.cancel(); job = null; write = null; authorPosting = null; requested = 0; attempted = 0 }
    private class IncompleteRange : Exception()
    private fun ApiFailure.ambiguous() = kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.TLS,
        FailureKind.REDIRECT, FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) || (status ?: 0) >= 500
    companion object {
        internal fun factory(operations: DirectOperations, realtime: RealtimeController? = null,
            typing: TypingController? = null, mutations: MessageMutationOperations? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { DirectThreadViewModel(operations, realtime, typing, mutations) }
        }
    }
}
