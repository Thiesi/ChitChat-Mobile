package io.github.thiesi.chitchat.feature.direct

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.conversation.serverInstant
import java.time.Instant
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.mutations.MessageMutationOperations
import io.github.thiesi.chitchat.core.mutations.validateEditedBody
import io.github.thiesi.chitchat.core.reactions.MessageReactionOperations
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.reactions.selectedBy
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.typing.TypingController
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.awaitSendWhenBackOnline
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class DirectAnchor(val id: Long, val offset: Int, val revision: Long = 0)
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
    val authorNotice: String? = null,
    val reactionDialog: DirectReactionDialog? = null, val reactionWriting: Boolean = false,
    val reactionUnknown: DirectReactionUnknown? = null, val reactionLimit: Boolean = false,
    val reactionNotice: String? = null,
    val attachmentReview: DirectAttachmentReview? = null,
    /** Send when back online (S4b): this conversation's draft waits for the connection; it is not an attempt. */
    val waitingToSend: Boolean = false) { override fun toString() = "DirectThreadState" }

/** One serialized operation per thread: hints queue behind writes, never cancel/replay a POST. */
class DirectThreadViewModel internal constructor(private val operations: DirectOperations,
    val realtime: RealtimeController? = null,
    val typing: TypingController? = null,
    private val mutations: MessageMutationOperations? = null,
    private val reactions: MessageReactionOperations? = null,
    private val attachmentBudget: AttachmentMemoryBudget? = null,
    private val uploads: AttachmentUploadOperations? = null,
    /** The existing session refresh; Send when back online asks for one revalidation per reconnect. */
    private val revalidate: (suspend () -> Unit)? = null) : ViewModel() {
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
    private enum class Write { SEND, READ, BLOCK, AUTHOR, REACTION, UPLOAD }
    private var authorPosting: DirectAuthorRequest? = null
    private val unknownAuthors = mutableMapOf<Long, DirectAuthorUnknown>()
    private var reactionPosting: DirectReactionWrite? = null
    private val unknownReactions = mutableMapOf<Long, DirectReactionUnknown>()

    private class AttachmentWork(val selection: DirectAttachmentSelection) {
        var view = DirectAttachmentDraft(selection, DirectAttachmentPhase.PREPARING)
        var job: Job? = null
        var producerEnd: Job? = null
        var retained: RetainedAttachmentPreparation? = null
    }
    private val attachments = mutableMapOf<Long, AttachmentWork>()
    private var uploadPosting: DirectAttachmentUpload? = null

    /** A clean draft frozen at Send while offline: exactly this text and reply target go out, once. Memory only. */
    private class WaitingSend(val peer: Long, val body: String, val reply: ComposerReply?)
    private var waiting: WaitingSend? = null
    private var waitJob: Job? = null

    init { viewModelScope.launch { session.collect {
        if (it.directIdentity() != identity) {
            retireOwnerWork(); identity = it.directIdentity(); mutable.value = DirectThreadState()
            savedDrafts.value = emptyMap(); localAttention.value = emptyMap(); unknownAuthors.clear(); unknownReactions.clear(); seen = emptySet()
        } else drain()
    } } }

    fun open(peer: Long, expected: SessionState = session.value) {
        val owner = expected.directIdentity() ?: return
        if (owner != session.value.directIdentity() || peer <= 0 || peer == owner.user) return
        if (owner != identity) {
            retireOwnerWork(); identity = owner; mutable.value = DirectThreadState()
            savedDrafts.value = emptyMap(); localAttention.value = emptyMap(); unknownAuthors.clear(); unknownReactions.clear(); seen = emptySet()
        }
        if (mutable.value.peerId != peer) {
            dropWaiting()
            val foreground = active
            if (uploadPosting != null) retire(keepUpload = true) else background()
            active = foreground; seen = emptySet(); attemptedRead = 0
            viewport = null; visibleNewest = null
            val memo = savedDrafts.value[peer]
            mutable.value = DirectThreadState(peerId = peer, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, checkedUnknown = memo?.checked == true, composerReply = memo?.composerReply,
                authorUnknown = unknownAuthors[peer], reactionUnknown = unknownReactions[peer],
                busy = uploadPosting != null, sending = uploadPosting?.selection?.peer == peer)
        }
        if (!mutable.value.loaded) invalidate() // Entry is not explicit uncertain-send reconciliation/consent.
    }
    fun foreground() {
        active = true
        if (mutable.value.attachmentReview == null) invalidate()
    }
    fun background() {
        active = false
        dropWaiting() // Waiting never outlives the foreground or the conversation on screen.
        authorPosting?.let(::rememberUnknownAuthor)
        reactionPosting?.let(::rememberUnknownReaction)
        val current = mutable.value
        mutable.value = current.copy(uncertainSend = current.uncertainSend || write == Write.SEND,
            uncertainRead = current.uncertainRead || write == Write.READ,
            uncertainBlock = current.uncertainBlock || write == Write.BLOCK,
            checkedUnknown = false, busy = uploadPosting != null, sending = uploadPosting?.selection?.peer == current.peerId && uploadPosting != null, stale = true, atLatest = false,
            authorDialog = null, authorWriting = false, reactionDialog = null, reactionWriting = false, attachmentReview = null)
        rememberDraft(); retire(); visibleNewest = null; viewport = null
    }

    /** Preserve an explicitly authorized upload through navigation. App background still cancels. */
    fun leavePane() {
        dropWaiting()
        if (uploadPosting == null || !currentIdentity()) { background(); return }
        active = false; visibleNewest = null; viewport = null
        mutable.value = mutable.value.copy(atLatest = false, authorDialog = null, reactionDialog = null)
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
        val review = mutable.value.attachmentReview?.request
        fun reviewAccess() {
            if (review == null) return
            if (mutable.value.attachmentReview?.request !== review) throw CancellationException("Attachment review retired")
            if (!ready()) throw ApiFailure(FailureKind.HTTP, code = "csrf_failed")
            validatedDirectPrivacy(session.value.privacy)
        }
        reviewAccess()
        if (!current(seq, peer, expected)) throw CancellationException("Thread retired")
        return call().also {
            if (!current(seq, peer, expected)) throw CancellationException("Thread retired")
            reviewAccess()
        }
    }
    private fun drain() {
        if (!active || !ready() || mutable.value.busy || uploadPosting != null || mutable.value.attachmentReview != null || mutable.value.peerId == null || requested <= attempted ||
            realtime?.state?.value?.phase == RealtimePhase.OFFLINE) return
        attempted = requested
        start { peer -> reload(peer) }
    }
    fun refresh() { start { peer -> reload(peer, latest = mutable.value.uncertainSend, consent = true) } }
    fun latest() { start { peer -> reload(peer, latest = true, consent = true) } }

    /** Same-peer reply adoption; it is neither a whole-peer read nor an unknown-send history check. */
    fun canAdoptTarget(snapshot: TargetSnapshot.DirectMessage): Boolean = active && ready() &&
        snapshot.matches(session.value) && snapshot.available && snapshot.context.peer.id == mutable.value.peerId &&
        !mutable.value.busy && write == null && mutable.value.authorDialog == null && authorPosting == null &&
        mutable.value.reactionDialog == null && reactionPosting == null && mutable.value.attachmentReview == null

    fun belongsTo(expected: SessionState) = expected.directIdentity() != null && expected.directIdentity() == identity &&
        currentIdentity()

    private fun selected(expected: SessionState, peer: Long?) = belongsTo(expected) && peer != null &&
        mutable.value.peerId == peer

    fun targetDenied(request: TargetRequest, error: ApiFailure) {
        val target = request.target as? MessageTarget.DirectMessage ?: return
        if (!request.matches(session.value) || !selected(request.original, target.peerId) || uploadPosting != null || authorPosting != null || reactionPosting != null ||
            mutable.value.attachmentReview != null || error.status !in setOf(403, 404) || error.code == "csrf_failed") return
        val old = mutable.value; val pending = write
        retire()
        mutable.value = old.copy(context = null, messages = emptyList(), loaded = false, stale = true,
            busy = false, sending = false, atLatest = false, anchor = null, fixedUpper = null, highlightedMessageId = null,
            olderCursor = null, mayHaveOlder = false, error = error, checkedUnknown = false,
            uncertainSend = old.uncertainSend || pending == Write.SEND,
            uncertainRead = old.uncertainRead || pending == Write.READ,
            uncertainBlock = old.uncertainBlock || pending == Write.BLOCK, authorDialog = null, authorWriting = false,
            reactionDialog = null, reactionWriting = false)
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
        mutable.value.authorDialog == null && authorPosting == null && mutable.value.reactionDialog == null && reactionPosting == null && mutable.value.attachmentReview == null

    fun prepareTarget(snapshot: TargetSnapshot.DirectMessage): Boolean {
        val fresh = snapshot.withCurrentPolicy(session.value) as? TargetSnapshot.DirectMessage ?: return false
        if (!canPrepareTarget(fresh)) return false
        background() // Never cancel a send/read/block POST to serve a notification link.
        if (mutable.value.peerId != fresh.context.peer.id) {
            seen = emptySet(); attemptedRead = 0; viewport = null; visibleNewest = null
            val memo = savedDrafts.value[fresh.context.peer.id]
            mutable.value = DirectThreadState(peerId = fresh.context.peer.id, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, composerReply = memo?.composerReply,
                authorUnknown = unknownAuthors[fresh.context.peer.id], reactionUnknown = unknownReactions[fresh.context.peer.id]) // A target GET is never checkedUnknown consent.
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
            value.attachmentReview == null && value.loaded && !value.stale && context.peer.id == request.peerId &&
            row.id > 0 && row.sender.id == user.id && row.recipient.id == request.peerId && row.outgoing &&
            !row.deleted && row.body != null && value.messages.any { it === row } && when (request.kind) {
                AuthorChangeKind.EDIT -> row.canEdit && context.relationship.messagingAvailable && mute == null
                AuthorChangeKind.DELETE -> row.canDelete // Blocking/mute does not revoke author deletion.
            }
    }

    fun canBeginAuthor(expected: SessionState, peer: Long?, message: DirectMessage, kind: AuthorChangeKind): Boolean =
        peer != null && mutable.value.authorDialog == null && mutable.value.reactionDialog == null && !mutable.value.busy &&
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
        if (!active || !selected(expected, original.peerId) || unknownAuthors[original.peerId] !== original || mutable.value.busy ||
            mutable.value.reactionDialog != null || mutable.value.attachmentReview != null) return
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

    private fun reactionPrivacyKnown(): Boolean {
        if (session.value.chatPolicy == null) return false
        return try { validatedDirectPrivacy(session.value.privacy); true } catch (_: ApiFailure) { false }
    }

    private fun reactionParties(row: DirectMessage, peer: Long, viewer: Long) = row.id > 0 && peer > 0 && peer != viewer &&
        if (row.outgoing) row.sender.id == viewer && row.recipient.id == peer
        else row.sender.id == peer && row.recipient.id == viewer

    private fun reactionRowCurrent(request: DirectReactionRequest): Boolean {
        val value = mutable.value
        val viewer = session.value.user?.id ?: return false
        return reactions != null && active && selected(request.expected, request.peerId) && ready() && reactionPrivacyKnown() &&
            value.attachmentReview == null && value.loaded && !value.stale && value.context?.peer?.id == request.peerId &&
            reactionParties(request.original, request.peerId, viewer) && value.messages.any { it === request.original }
        // Room ignores and send/block/author capabilities are not DM history-reaction permissions.
    }

    internal fun canBeginReactions(expected: SessionState, peer: Long?, row: DirectMessage): Boolean =
        peer != null && mutable.value.authorDialog == null && mutable.value.reactionDialog == null && !mutable.value.busy &&
            unknownReactions[peer] == null && unknownReactions.size < 100 && reactionRowCurrent(DirectReactionRequest(expected, peer, row))

    internal fun beginReactions(expected: SessionState, peer: Long?, row: DirectMessage): Boolean {
        if (!canBeginReactions(expected, peer, row)) {
            if (selected(expected, peer) && unknownReactions.size >= 100) mutable.value = mutable.value.copy(reactionLimit = true)
            return false
        }
        visibleNewest = null
        mutable.value = mutable.value.copy(reactionDialog = DirectReactionDialog(DirectReactionRequest(expected, checkNotNull(peer), row)),
            atLatest = false, reactionLimit = false)
        return true
    }

    private fun reactionChoiceCurrent(request: DirectReactionRequest, emoji: ReactionEmoji, desired: Boolean): Boolean {
        val dialog = mutable.value.reactionDialog ?: return false
        val viewer = session.value.user?.id ?: return false
        return dialog.request === request && !dialog.checked && unknownReactions[request.peerId] == null && reactionRowCurrent(request) &&
            (request.original.reactions.firstOrNull { it.emoji == emoji.value }?.selectedBy(viewer) == true) != desired &&
            (!desired || !request.original.deleted && session.value.chatPolicy?.mutedEverywhere == null)
    }

    internal fun canSetReaction(request: DirectReactionRequest, emoji: ReactionEmoji, desired: Boolean) =
        !mutable.value.busy && reactionChoiceCurrent(request, emoji, desired)

    internal fun setReaction(request: DirectReactionRequest, emoji: ReactionEmoji, desired: Boolean) {
        if (!canSetReaction(request, emoji, desired)) return
        start { peer ->
            if (!reactionChoiceCurrent(request, emoji, desired)) throw CancellationException("Reaction selection changed")
            write = Write.REACTION; reactionPosting = DirectReactionWrite(request, emoji, desired)
            mutable.value = mutable.value.copy(reactionWriting = true)
            val summary = checked { checkNotNull(reactions).setDirect(request.expected, request.original, emoji, desired) }
            // Confirmation precedes every fallible GET; another client's desired state is not synthesized.
            reactionPosting = null; write = null
            mutable.value = mutable.value.copy(messages = applyDirectReactionResult(mutable.value.messages, request.original, summary),
                reactionDialog = null, reactionWriting = false, stale = true, reactionNotice = "reaction_changed")
            reload(peer, reconcileBlock = false)
        }
    }

    internal fun dismissReactions(request: DirectReactionRequest) {
        if (mutable.value.reactionDialog?.request === request && selected(request.expected, request.peerId) && !mutable.value.busy)
            mutable.value = mutable.value.copy(reactionDialog = null, atLatest = false)
    }

    private fun rememberUnknownReaction(pending: DirectReactionWrite) {
        val request = pending.request
        val marker = DirectReactionUnknown(request.peerId, request.original.id, pending.emoji.value, pending.selected)
        unknownReactions[request.peerId] = marker
        if (mutable.value.peerId == request.peerId) mutable.value = mutable.value.copy(reactionUnknown = marker)
    }

    internal fun checkReactionHistory(expected: SessionState, original: DirectReactionUnknown) {
        if (!active || !selected(expected, original.peerId) || unknownReactions[original.peerId] !== original ||
            mutable.value.busy || mutable.value.authorDialog != null || mutable.value.attachmentReview != null) return
        val dialog = mutable.value.reactionDialog
        start { peer ->
            reload(peer, reconcileBlock = false) // GET only; no SEND/READ/BLOCK/AUTHOR reconciliation/consent.
            if (unknownReactions[original.peerId] === original) {
                unknownReactions.remove(original.peerId)
                mutable.value = mutable.value.copy(reactionUnknown = null, reactionLimit = false, reactionNotice = "reaction_checked",
                    reactionDialog = if (dialog != null && mutable.value.reactionDialog?.request === dialog.request)
                        DirectReactionDialog(dialog.request, checked = true) else mutable.value.reactionDialog)
            }
        }
    }

    internal fun reactionCapacityFull() = unknownReactions.size >= 100 && mutable.value.peerId !in unknownReactions

    internal fun reactionCurrentRow(request: DirectReactionRequest): DirectMessage? {
        if (mutable.value.reactionDialog?.request !== request || !selected(request.expected, request.peerId) || !reactionPrivacyKnown()) return null
        val viewer = session.value.user?.id ?: return null
        return mutable.value.messages.firstOrNull { it.id == request.original.id && reactionParties(it, request.peerId, viewer) }
    }

    internal fun canReviewReactions(request: DirectReactionRequest): Boolean {
        val dialog = mutable.value.reactionDialog ?: return false
        val row = reactionCurrentRow(request) ?: return false
        return dialog.request === request && (dialog.checked || row !== request.original) && !mutable.value.busy &&
            unknownReactions[request.peerId] == null && unknownReactions.size < 100 &&
            reactionRowCurrent(DirectReactionRequest(request.expected, request.peerId, row))
    }

    internal fun reviewReactions(request: DirectReactionRequest): Boolean {
        if (!canReviewReactions(request)) return false
        val row = reactionCurrentRow(request) ?: return false
        mutable.value = mutable.value.copy(reactionDialog = DirectReactionDialog(DirectReactionRequest(request.expected, request.peerId, row)))
        return true
    }

    private fun start(preserveAttachmentReview: Boolean = false, operation: suspend (Long) -> Unit) {
        val peer = mutable.value.peerId ?: return
        if (!active || !ready() || mutable.value.busy || uploadPosting != null) return
        if (!preserveAttachmentReview && mutable.value.attachmentReview != null) {
            visibleNewest = null
            mutable.value = mutable.value.copy(attachmentReview = null, atLatest = false)
        }
        val seq = ++sequence; val expected = identity
        mutable.value = mutable.value.copy(busy = true, error = null, validation = null, authorNotice = null, reactionNotice = null)
        val pending = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!current(seq, peer, expected)) return@launch
                if (preserveAttachmentReview) {
                    if (!ready()) throw ApiFailure(FailureKind.HTTP, code = "csrf_failed")
                    validatedDirectPrivacy(session.value.privacy)
                }
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
                    reactionDialog = mutable.value.reactionDialog.takeUnless { inaccessible },
                    loaded = mutable.value.loaded && !inaccessible,
                    uncertainSend = mutable.value.uncertainSend || pending == Write.SEND && failure.ambiguous(),
                    uncertainBlock = mutable.value.uncertainBlock || pending == Write.BLOCK && failure.ambiguous(),
                    uncertainRead = mutable.value.uncertainRead || pending == Write.READ && failure.ambiguous(),
                    readError = if (pending == Write.READ) failure else mutable.value.readError)
            } }
            finally { if (current(seq, peer, expected)) {
                val review = mutable.value.attachmentReview
                if (preserveAttachmentReview && review?.phase == DirectAttachmentReviewPhase.CHECKING)
                    mutable.value = mutable.value.copy(attachmentReview = mutable.value.error?.let {
                        DirectAttachmentReview(review.request, DirectAttachmentReviewPhase.FAILED, error = it)
                    })
                authorPosting?.let(::rememberUnknownAuthor)
                reactionPosting?.let(::rememberUnknownReaction)
                authorPosting = null
                reactionPosting = null
                write = null; job = null
                mutable.value = mutable.value.copy(busy = false, sending = false, authorWriting = false, reactionWriting = false)
                rememberDraft(); drain(); maybeRead()
            } }
        }
        job = pending; pending.start()
    }

    /** Canonical history, not event payload reduction or a guessed around-message route. */
    private suspend fun reload(peer: Long, latest: Boolean = false, consent: Boolean = false, reconcileBlock: Boolean = true) {
        attempted = maxOf(attempted, requested)
        val context = checked { operations.context(peer) }
        mutable.value = mutable.value.copy(context = context, uncertainBlock = mutable.value.uncertainBlock && !reconcileBlock)
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
        if (!mutable.value.mayHaveOlder || mutable.value.stale || mutable.value.attachmentReview != null) return
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
        val authorModal = mutable.value.authorDialog != null || mutable.value.reactionDialog != null || mutable.value.attachmentReview != null
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
        if (!active || !ready() || value.busy || value.authorDialog != null || value.reactionDialog != null || value.attachmentReview != null || value.stale || !value.latestWindow || !value.atLatest ||
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
        if (mutable.value.attachmentReview != null) return
        start { peer ->
            reload(peer)
            attemptedRead = 0
            mutable.value = mutable.value.copy(readError = null, uncertainRead = false)
        }
    }
    fun acknowledgeAdditionalRead() {
        if (mutable.value.attachmentReview != null) return
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
        if (!selected(expected, peer) || mutable.value.sending || waiting != null || mutable.value.authorDialog != null || mutable.value.reactionDialog != null || mutable.value.attachmentReview != null) return false
        if (!canEditDraft() && value.isNotEmpty()) { mutable.value = mutable.value.copy(draftLimit = true); return false }
        mutable.value = mutable.value.copy(draft = value, validation = null, draftLimit = false)
        rememberDraft(); return true
    }
    fun canUseMessage(expected: SessionState, peer: Long?, message: DirectMessage): Boolean {
        val value = mutable.value
        return active && selected(expected, peer) && ready() && value.context != null && value.authorDialog == null && value.reactionDialog == null && value.attachmentReview == null &&
            value.messages.any { it === message } && !message.deleted && message.id > 0
    }
    fun canReplyTo(expected: SessionState, peer: Long?, message: DirectMessage) =
        canUseMessage(expected, peer, message) && composerCanSend() && canEditDraft() && !mutable.value.sending
    fun replyTo(expected: SessionState, peer: Long?, message: DirectMessage): Boolean {
        if (waiting != null || !canReplyTo(expected, peer, message)) return false
        mutable.value = mutable.value.copy(composerReply = ComposerReply(message.id), validation = null,
            error = mutable.value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft(); return true
    }
    fun cancelReply(expected: SessionState, peer: Long?, original: ComposerReply) {
        val value = mutable.value
        if (!active || !selected(expected, peer) || waiting != null || value.sending || value.authorDialog != null || value.reactionDialog != null || value.attachmentReview != null || value.composerReply !== original) return
        mutable.value = value.copy(composerReply = null, draftLimit = false,
            error = value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft()
    }
    fun discardSavedDraft(original: DirectDraft, isCurrentEntry: () -> Boolean): Boolean {
        val peer = original.peer
        val current = savedDrafts.value[peer] ?: return false
        if (!isCurrentEntry() || !currentIdentity() || !original.matches(session.value) || !current.matches(session.value) ||
            !current.sameDiscardChoice(original) || mutable.value.sending || write != null ||
            mutable.value.authorDialog != null || authorPosting != null ||
            mutable.value.reactionDialog != null || reactionPosting != null) return false
        removeAttachmentWork(peer)
        savedDrafts.value = savedDrafts.value - peer
        if (mutable.value.peerId == peer) mutable.value = mutable.value.copy(draft = "", composerReply = null, uncertainSend = false,
            checkedUnknown = false, draftLimit = false)
        return true
    }
    private fun rememberDraft() {
        if (!currentIdentity()) return
        val value = mutable.value; val peer = value.peerId ?: return
        val owner = session.value.directIdentity() ?: return
        val previous = savedDrafts.value[peer]
        val attachment = attachments[peer]?.view
        val name = value.context?.peer?.username ?: previous?.name
        if (previous != null && previous.matches(session.value) && previous.attachment === attachment &&
            previous.name == name && previous.body == value.draft && previous.uncertain == value.uncertainSend &&
            previous.checked == value.checkedUnknown && previous.composerReply === value.composerReply) return
        savedDrafts.value = if (value.draft.isEmpty() && !value.uncertainSend && value.composerReply == null && attachment == null)
            savedDrafts.value - peer else savedDrafts.value + (peer to DirectDraft(owner, peer, name, value.draft,
                value.uncertainSend, value.checkedUnknown, value.composerReply, attachment,
                previous?.slot ?: attachment?.selection?.slot ?: Any(),
                previous?.takeIf { it.body == value.draft && it.composerReply === value.composerReply }?.content ?: Any()))
    }
    /** Never while a draft waits: the waiting job owns that draft's one attempt (no manual second POST). */
    fun canSend() = waiting == null && attachments[mutable.value.peerId] == null && composerCanSend()
    /** Paperclip/admission readiness only. selectAttachment still rechecks every original fact. */
    internal fun canBeginAttachment() = active && waiting == null && attachmentBudget != null && !mutable.value.sending && composerCanSend()
    private fun composerCanSend() = uploadPosting == null && attachments[mutable.value.peerId]?.view?.phase !in
        setOf(DirectAttachmentPhase.UNKNOWN, DirectAttachmentPhase.CONFIRMED, DirectAttachmentPhase.CANCELLING) &&
        active && ready() && mutable.value.let { !it.busy && it.authorDialog == null && it.reactionDialog == null && it.attachmentReview == null && !it.stale && it.loaded &&
        !it.uncertainSend && !it.uncertainBlock && it.context?.relationship?.messagingAvailable == true &&
        it.context.mutedEverywhere == null }
    fun send(expected: SessionState, peer: Long?) { if (selected(expected, peer)) send() }
    fun send() {
        if (canWaitToSend()) { startWaiting(); return }
        if (!canSend()) return
        val body = mutable.value.draft
        validateDirectBody(body)?.let { mutable.value = mutable.value.copy(validation = it); return }
        performSend(body, mutable.value.composerReply)
    }

    private fun performSend(body: String, reply: ComposerReply?) {
        val typingSession = session.value
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
    /**
     * Send when back online (S4b): only a clean text or reply draft in the conversation on screen can wait,
     * and only while offline. Attachments, sends in progress, unknown delivery or blocks and pending
     * choices keep today's flow. Messaging availability and mutes are the last known facts; they are
     * re-read after the reconcile, before the one attempt.
     */
    fun canWaitToSend(): Boolean {
        val value = mutable.value
        val controller = realtime ?: return false
        val context = value.context ?: return false
        val connection = controller.state.value
        return active && waiting == null && revalidate != null && connection.phase == RealtimePhase.OFFLINE &&
            connection.generation == session.value.generation && currentIdentity() && value.peerId != null &&
            attachments[value.peerId] == null && uploadPosting == null && write == null && !value.busy && !value.sending &&
            value.authorDialog == null && value.reactionDialog == null && value.attachmentReview == null && value.loaded &&
            canEditDraft() && !value.uncertainSend && !value.uncertainBlock &&
            context.relationship.messagingAvailable && context.mutedEverywhere == null &&
            value.draft.isNotBlank() && validateDirectBody(value.draft) == null
    }

    /** Cancel returns the text to an editable draft; it never touches delivery state or consent. */
    fun cancelWaiting() { dropWaiting() }

    private fun startWaiting() {
        val value = mutable.value
        val peer = value.peerId ?: return
        val expected = identity ?: return
        val entry = WaitingSend(peer, value.draft, value.composerReply)
        waiting = entry
        mutable.value = value.copy(waitingToSend = true, validation = null)
        waitJob = viewModelScope.launch { awaitThenSendOnce(entry, expected) }
    }

    private fun dropWaiting() {
        waitJob?.cancel(); waitJob = null
        if (waiting == null) return
        waiting = null
        mutable.value = mutable.value.copy(waitingToSend = false)
    }

    private fun stillWaiting(entry: WaitingSend, expected: DirectIdentity): Boolean = waiting === entry && active &&
        identity == expected && currentIdentity() && mutable.value.peerId == entry.peer

    /** The one automatic attempt; see [awaitSendWhenBackOnline]. Policy and target are re-read through canSend(). */
    private suspend fun awaitThenSendOnce(entry: WaitingSend, expected: DirectIdentity) {
        val controller = realtime ?: return
        val refresh = revalidate ?: return
        if (!awaitSendWhenBackOnline(session, controller, refresh, { it.directIdentity() }, expected,
                { stillWaiting(entry, expected) }, mutable.map { it.loaded && !it.stale && !it.busy })) return
        waiting = null; waitJob = null
        mutable.value = mutable.value.copy(waitingToSend = false)
        if (canSend()) performSend(entry.body, entry.reply)
    }

    fun allowDuplicateRisk() {
        if (mutable.value.uncertainSend && mutable.value.checkedUnknown && !mutable.value.busy && mutable.value.authorDialog == null && mutable.value.reactionDialog == null && mutable.value.attachmentReview == null) {
            mutable.value = mutable.value.copy(uncertainSend = false, checkedUnknown = false)
            rememberDraft()
        }
    }
    fun setBlocked(expected: SessionState, peer: Long?, blocked: Boolean) {
        if (selected(expected, peer)) setBlocked(blocked)
    }
    fun setBlocked(blocked: Boolean) {
        if (mutable.value.context == null || mutable.value.uncertainBlock || mutable.value.authorDialog != null || mutable.value.reactionDialog != null || mutable.value.attachmentReview != null) return
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
    private fun retire(keepUpload: Boolean = false) {
        mutable.value = mutable.value.copy(attachmentReview = null)
        sequence++
        if (uploadPosting != null) {
            if (!keepUpload) retireUpload()
        } else { job?.cancel(); job = null; write = null }
        authorPosting = null; reactionPosting = null; requested = 0; attempted = 0
    }

    /** A first pick or an explicit original-bound Replace; the root supplies fresh entry proof. */
    internal fun selectAttachment(expected: SessionState, peer: Long, sourceEntryId: String,
        read: PickedAttachmentRead, source: AttachmentPickSource, original: DirectAttachmentDraft? = null,
        isCurrentEntry: () -> Boolean): Boolean {
        val budget = attachmentBudget ?: return false
        val owner = expected.directIdentity() ?: return false
        if (!isCurrentEntry() || sourceEntryId.isBlank() || !selected(expected, peer) || !composerCanSend() ||
            !canEditDraft() || write != null) return false
        val policy = session.value.attachmentPolicy
        if (policy == AttachmentPolicy.Invalid || policy == AttachmentPolicy.Unavailable ||
            (policy is AttachmentPolicy.Known && policy.maxBytes == 0L)) return false
        val previous = attachments[peer]
        if (original?.selection !== previous?.selection || (original == null && previous != null) ||
            original?.upload !== previous?.view?.upload) return false
        val slot = savedDrafts.value[peer]?.slot ?: Any()
        val selection = DirectAttachmentSelection(owner, peer, slot, sourceEntryId, read.filename, read.mimeType)
        val retiredProducer = removeAttachmentWork(peer)
        val work = AttachmentWork(selection).also { it.producerEnd = retiredProducer }
        attachments[peer] = work
        rememberDraft()
        val pending = viewModelScope.launch {
            retiredProducer?.join()
            val memo = savedDrafts.value[peer]
            if (attachments[peer] !== work || !selection.matches(session.value) || memo == null ||
                !memo.matches(session.value) || memo.slot !== slot) return@launch
            val task = AttachmentPreparationTask.forAvailableMemory(read, source, budget)
            val producer = task.start(this) { completed, outcome ->
                completed === task && adoptAttachmentOutcome(peer, work, outcome)
            }
            work.producerEnd = producer
            producer.join()
        }
        work.job = pending.takeUnless { it.isCompleted }
        return true
    }

    /** Explicit original-bound privacy choice. This prepares local bytes; it never sends.
     * The original producer must have completed its final resource handoff first.
     */
    internal fun prepareOriginalPhoto(expected: SessionState, peer: Long, original: DirectAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val value = mutable.value
        if (!isCurrentEntry() || !active || !selected(expected, peer) || !composerCanSend() || !canEditDraft() ||
            value.sending || write != null) return false
        val policy = session.value.attachmentPolicy
        if (policy == AttachmentPolicy.Invalid || policy == AttachmentPolicy.Unavailable ||
            (policy is AttachmentPolicy.Known && policy.maxBytes == 0L)) return false
        val current = attachments[peer] ?: return false
        val selection = current.selection
        val memo = savedDrafts.value[peer] ?: return false
        if (current.view !== original || original.phase != DirectAttachmentPhase.PRIVACY_CHOICE ||
            !selection.matches(session.value) || !memo.matches(session.value) || memo.slot !== selection.slot ||
            current.producerEnd?.isCompleted != true) return false
        val retained = current.retained ?: return false
        val choice = retained.preparation as? AttachmentPreparation.NeedsChoice ?: return false
        val task = OriginalPhotoPreparationTask(retained, choice)
        // New worker identity, same private slot/selection. Old callbacks cannot adopt into it.
        val work = AttachmentWork(selection)
        current.retained = null
        attachments[peer] = work
        rememberDraft()
        val producer = task.start(viewModelScope) { completed, outcome ->
            completed === task && adoptAttachmentOutcome(peer, work, outcome)
        }
        work.producerEnd = producer
        work.job = producer.takeUnless { it.isCompleted }
        return true
    }

    private fun adoptAttachmentOutcome(peer: Long, work: AttachmentWork, outcome: AttachmentPreparationOutcome): Boolean {
        val selection = work.selection
        val current = savedDrafts.value[peer]
        if (attachments[peer] !== work || !selection.matches(session.value) || current == null ||
            !current.matches(session.value) || current.slot !== selection.slot) return false
        work.view = when (outcome) {
            is AttachmentPreparationOutcome.Failed -> DirectAttachmentDraft(selection, DirectAttachmentPhase.FAILED,
                failure = outcome.kind)
            is AttachmentPreparationOutcome.Prepared -> {
                work.retained = outcome.retained
                when (val prepared = checkNotNull(outcome.retained.preparation)) {
                    is AttachmentPreparation.Ready -> DirectAttachmentDraft(selection, DirectAttachmentPhase.READY,
                        sizeBytes = prepared.payload.sizeBytes, privacy = prepared.privacy)
                    is AttachmentPreparation.NeedsChoice -> DirectAttachmentDraft(selection, DirectAttachmentPhase.PRIVACY_CHOICE,
                        sizeBytes = prepared.original.bytes.size.toLong(), privacyFailure = prepared.kind)
                }
            }
        }
        // Private completion only: preserve the latest caption/reply/uncertainty and active pane.
        savedDrafts.value = savedDrafts.value + (peer to current.withAttachment(work.view))
        return outcome is AttachmentPreparationOutcome.Prepared
    }

    internal fun visibleAttachment(expected: SessionState, peer: Long,
        isCurrentEntry: () -> Boolean): DirectAttachmentDraft? {
        if (!isCurrentEntry() || !active || !selected(expected, peer)) return null
        return attachments[peer]?.view?.takeIf { it.selection.matches(session.value) }
    }

    /** Fresh display-only policy facts; preserve private bytes, draft and every actor marker. */
    internal fun attachmentPolicyObservation(expected: SessionState, peer: Long, original: DirectAttachmentDraft,
        isCurrentEntry: () -> Boolean): AttachmentPolicyObservation? {
        val current = visibleAttachment(expected, peer, isCurrentEntry) ?: return null
        val now = session.value
        if (current.selection !== original.selection || savedDrafts.value[peer]?.slot !== current.selection.slot ||
            !current.selection.matches(now)) return null
        val finalSize = current.sizeBytes.takeIf { current.phase in setOf(DirectAttachmentPhase.READY,
            DirectAttachmentPhase.UPLOADING, DirectAttachmentPhase.FINISHING, DirectAttachmentPhase.CANCELLING,
            DirectAttachmentPhase.UNKNOWN, DirectAttachmentPhase.CONFIRMED) }
        return observeAttachmentPolicy(now.attachmentPolicy, finalSize, current.mimeType)
    }

    internal fun removeAttachment(expected: SessionState, peer: Long, original: DirectAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        if (!isCurrentEntry() || !active || !selected(expected, peer) || mutable.value.busy || write != null ||
            mutable.value.sending || attachments[peer]?.selection !== original.selection ||
            attachments[peer]?.view?.upload !== original.upload || attachments[peer]?.view?.phase in
            setOf(DirectAttachmentPhase.UPLOADING, DirectAttachmentPhase.FINISHING, DirectAttachmentPhase.CANCELLING,
                DirectAttachmentPhase.UNKNOWN, DirectAttachmentPhase.CONFIRMED)) return false
        removeAttachmentWork(peer)
        rememberDraft()
        return true
    }

    private fun removeAttachmentWork(peer: Long): Job? {
        val retired = attachments.remove(peer) ?: return null
        val review = mutable.value.attachmentReview
        if (review?.request?.attempt?.selection === retired.selection) {
            if (review.phase == DirectAttachmentReviewPhase.CHECKING) retire()
            visibleNewest = null
            mutable.value = mutable.value.copy(attachmentReview = null, busy = false, sending = false, atLatest = false)
        }
        retired.job?.cancel()
        retired.retained?.close()
        retired.retained = null
        return retired.producerEnd
    }
    private fun retireAttachments() { attachments.keys.toList().forEach { removeAttachmentWork(it) } }
    private fun retireOwnerWork() {
        dropWaiting()
        retireAttachments(); retire()
        // Old transport remains charged through actual stop, without owning successor serialization.
        uploadPosting?.entryProbe = null
        uploadPosting = null; write = null; job = null
    }
    override fun onCleared() { retireUpload(); retireAttachments(); super.onCleared() }

    private fun uploadPrivacyKnown(): Boolean = try { validatedDirectPrivacy(session.value.privacy); true }
        catch (_: ApiFailure) { false }

    internal fun sendAttachment(expected: SessionState, peer: Long, entryId: String, original: DirectAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val sender = uploads ?: return false
        if (waiting != null) return false
        val work = attachments[peer] ?: return false
        val memo = savedDrafts.value[peer] ?: return false
        val value = mutable.value
        if (!isCurrentEntry() || entryId.isBlank() || !selected(expected, peer) || expected.user?.guest != false ||
            !composerCanSend() || !uploadPrivacyKnown() || session.value.chatPolicy?.mutedEverywhere != null ||
            write != null || authorPosting != null || reactionPosting != null ||
            work.view !== original || original.phase != DirectAttachmentPhase.READY || !work.selection.matches(session.value) ||
            !memo.matches(session.value) || memo.slot !== work.selection.slot || work.producerEnd?.isCompleted != true) return false
        val retained = work.retained ?: return false
        val prepared = retained.preparation as? AttachmentPreparation.Ready ?: return false
        when (observeAttachmentPolicy(session.value.attachmentPolicy, prepared.payload.sizeBytes, prepared.payload.mimeType)) {
            AttachmentPolicyObservation.Unadvertised, AttachmentPolicyObservation.WithinAdvertisedFacts -> Unit
            else -> return false
        }
        val caption = value.draft.phpTrim()
        if (caption.codePointCount(0, caption.length) > 4000) return false
        val attempt = DirectAttachmentUpload(work.selection, entryId, memo.content, caption, value.composerReply,
            prepared.payload.sha256, System.currentTimeMillis(), isCurrentEntry)
        uploadPosting = attempt; write = Write.UPLOAD
        publishUpload(attempt, DirectAttachmentPhase.UPLOADING)
        visibleNewest = null
        mutable.value = mutable.value.copy(busy = true, sending = true, atLatest = false, error = null, validation = null)
        val pending = viewModelScope.launch(start = CoroutineStart.LAZY) {
            attempt.entered = true
            try {
                if (ownedUpload(attempt) == null) throw CancellationException("Attachment retired")
                validatedDirectPrivacy(session.value.privacy)
                val ready = retained.preparation as? AttachmentPreparation.Ready
                    ?: throw CancellationException("Attachment retired")
                val receipt = sender.direct(expected, peer, retained, ready, caption, attempt.reply?.messageId) { facts ->
                    if (uploadPosting !== attempt || ownedUpload(attempt) == null) throw CancellationException("Attachment retired")
                    attempt.facts = facts
                    attempt.observer = launch { facts.collect { state ->
                        if (uploadPosting === attempt && !attempt.cancelRequested)
                            publishUpload(attempt, if (state.fullBodyReported) DirectAttachmentPhase.FINISHING else DirectAttachmentPhase.UPLOADING)
                    } }
                }
                if (receipt.peerId != peer || receipt.messageId <= 0) throw ApiFailure(FailureKind.INCOMPATIBLE)
                attempt.receipt = receipt // Positive ACK survives navigation and any later GET failure.
                ownedUpload(attempt)?.let { it.retained?.close(); it.retained = null }
                publishUpload(attempt, DirectAttachmentPhase.CONFIRMED)
            } catch (cancelled: CancellationException) {
                publishUpload(attempt, if (incompleteUpload(attempt)) DirectAttachmentPhase.READY else DirectAttachmentPhase.UNKNOWN)
                throw cancelled
            } catch (_: AttachmentUploadPolicyFailure) {
                publishUpload(attempt, DirectAttachmentPhase.READY, policyBlocked = true)
            } catch (failure: ApiFailure) {
                publishUpload(attempt, if (failure.ambiguous() && !incompleteUpload(attempt))
                    DirectAttachmentPhase.UNKNOWN else DirectAttachmentPhase.READY, error = failure)
            } finally {
                attempt.observer?.cancel()
                finishUpload(attempt, expected)
            }
        }
        job = pending; attempt.job = pending; pending.start()
        return true
    }

    private fun ownedUpload(attempt: DirectAttachmentUpload): AttachmentWork? {
        val peer = attempt.selection.peer
        val work = attachments[peer] ?: return null
        val memo = savedDrafts.value[peer] ?: return null
        return work.takeIf { it.selection === attempt.selection && attempt.selection.matches(session.value) &&
            memo.matches(session.value) && memo.slot === attempt.selection.slot }
    }
    private fun publishUpload(attempt: DirectAttachmentUpload, phase: DirectAttachmentPhase,
        error: ApiFailure? = null, policyBlocked: Boolean = false) {
        if (uploadPosting !== attempt) return
        val work = ownedUpload(attempt) ?: return
        val old = work.view
        work.view = DirectAttachmentDraft(work.selection, phase, old.sizeBytes, old.privacy,
            upload = attempt, transfer = attempt.facts?.value, uploadError = error, policyBlocked = policyBlocked)
        val peer = work.selection.peer
        savedDrafts.value = savedDrafts.value + (peer to savedDrafts.value.getValue(peer).withAttachment(work.view))
    }
    private fun incompleteUpload(attempt: DirectAttachmentUpload): Boolean {
        val facts = attempt.facts?.value ?: return true
        val length = facts.multipartSizeBytes ?: return true
        return facts.stopped && facts.multipartAttemptedUpperBound < length
    }
    internal fun cancelAttachmentUpload(expected: SessionState, peer: Long, original: DirectAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val attempt = uploadPosting ?: return false
        val current = ownedUpload(attempt)?.view ?: return false
        if (!isCurrentEntry() || !active || !selected(expected, peer) || attempt.selection.peer != peer ||
            original.upload !== attempt || current.upload !== attempt || attempt.cancelRequested ||
            attempt.facts?.value?.fullBodyReported == true) return false
        retireUpload()
        return true
    }
    private fun retireUpload() {
        val attempt = uploadPosting ?: return
        val pending = attempt.job
        attempt.cancelRequested = true
        if (!attempt.entered) {
            publishUpload(attempt, DirectAttachmentPhase.READY)
            uploadPosting = null; write = null
            if (job === pending) job = null
            attempt.entryProbe = null; attempt.job = null
            mutable.value = mutable.value.copy(busy = false, sending = false)
        } else publishUpload(attempt, DirectAttachmentPhase.CANCELLING)
        pending?.cancel()
    }
    private fun finishUpload(attempt: DirectAttachmentUpload, expected: SessionState) {
        val probe = attempt.entryProbe; val pending = attempt.job
        attempt.entryProbe = null; attempt.job = null; attempt.observer = null
        if (uploadPosting !== attempt) return
        uploadPosting = null; write = null
        if (job === pending) job = null
        visibleNewest = null
        mutable.value = mutable.value.copy(busy = false, sending = false, atLatest = false)
        val view = ownedUpload(attempt)?.view
        if (view?.phase == DirectAttachmentPhase.CONFIRMED && probe != null && probe() &&
            adoptConfirmedAttachment(expected, attempt.selection.peer, view, probe)) return
        drain() // Queued canonical GET only; no upload/text/read/other consent.
    }

    internal fun adoptConfirmedAttachment(expected: SessionState, peer: Long, original: DirectAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val attempt = original.upload ?: return false
        val work = ownedUpload(attempt) ?: return false
        val memo = savedDrafts.value[peer] ?: return false
        if (!isCurrentEntry() || !active || !selected(expected, peer) || attempt.selection.peer != peer ||
            work.view !== original || original.phase != DirectAttachmentPhase.CONFIRMED || attempt.receipt == null ||
            uploadPosting != null || mutable.value.busy || write != null) return false
        removeAttachmentWork(peer)
        val sameContent = memo.content === attempt.content
        mutable.value = mutable.value.copy(draft = if (sameContent) "" else memo.body,
            composerReply = if (sameContent) null else memo.composerReply, confirmedSendId = attempt.receipt?.messageId,
            atLatest = false)
        rememberDraft()
        start { originalPeer -> reload(originalPeer, latest = true, consent = false, reconcileBlock = false) }
        return true
    }
    internal fun checkAttachmentHistory(expected: SessionState, peer: Long, entryId: String,
        original: DirectAttachmentDraft, isCurrentEntry: () -> Boolean): Boolean {
        val attempt = original.upload ?: return false
        val work = ownedUpload(attempt) ?: return false
        val value = mutable.value
        if (!isCurrentEntry() || entryId.isBlank() || !active || !selected(expected, peer) || !ready() || !uploadPrivacyKnown() ||
            attempt.selection.peer != peer || work.view !== original || original.phase != DirectAttachmentPhase.UNKNOWN ||
            value.busy || uploadPosting != null || write != null || authorPosting != null || reactionPosting != null ||
            value.authorDialog != null || value.reactionDialog != null) return false
        val check = DirectAttachmentReviewRequest(attempt, entryId)
        visibleNewest = null
        mutable.value = value.copy(attachmentReview = DirectAttachmentReview(check, DirectAttachmentReviewPhase.CHECKING), atLatest = false)
        start(preserveAttachmentReview = true) { originalPeer ->
            reload(originalPeer, latest = true, consent = false, reconcileBlock = false)
            if (!active || mutable.value.attachmentReview?.request !== check ||
                ownedUpload(attempt)?.view?.let { it.upload === attempt && it.phase == DirectAttachmentPhase.UNKNOWN } != true)
                throw CancellationException("Attachment review retired")
            val evidence = mutable.value.messages.filter { attachmentEvidence(attempt, it, session.value.user?.id) }
                .map(::DirectAttachmentEvidence)
            mutable.value = mutable.value.copy(attachmentReview = DirectAttachmentReview(check,
                if (evidence.isEmpty()) DirectAttachmentReviewPhase.DUPLICATE_RISK else DirectAttachmentReviewPhase.EVIDENCE,
                evidence))
        }
        return true
    }

    private fun attachmentEvidence(attempt: DirectAttachmentUpload, row: DirectMessage, viewer: Long?): Boolean =
        viewer != null && row.outgoing && row.sender.id == viewer && row.recipient.id == attempt.selection.peer && row.id > 0 && !row.deleted &&
            row.attachment?.sha256 == attempt.sha256 &&
            serverInstant(row.createdAt)?.isAfter(Instant.ofEpochMilli(attempt.startedAtMillis)) == true

    internal fun visibleAttachmentReview(expected: SessionState, peer: Long, entryId: String,
        isCurrentEntry: () -> Boolean): DirectAttachmentReview? {
        val review = mutable.value.attachmentReview ?: return null
        return review.takeIf { attachmentReviewCurrent(expected, peer, entryId, review, isCurrentEntry) }
    }

    private fun attachmentReviewCurrent(expected: SessionState, peer: Long, entryId: String,
        original: DirectAttachmentReview, isCurrentEntry: () -> Boolean, requireReady: Boolean = true): Boolean {
        val check = original.request
        return isCurrentEntry() && active && selected(expected, peer) && (!requireReady || ready() && uploadPrivacyKnown()) &&
            check.entryId == entryId && check.attempt.selection.peer == peer &&
            mutable.value.attachmentReview === original && ownedUpload(check.attempt)?.view?.let { it.upload === check.attempt && it.phase == DirectAttachmentPhase.UNKNOWN } == true
    }

    internal fun closeAttachmentReview(expected: SessionState, peer: Long, entryId: String,
        original: DirectAttachmentReview, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, peer, entryId, original, isCurrentEntry, requireReady = false)) return false
        if (original.phase == DirectAttachmentReviewPhase.CHECKING) {
            retire()
            mutable.value = mutable.value.copy(busy = false, sending = false, stale = true)
        }
        visibleNewest = null
        mutable.value = mutable.value.copy(attachmentReview = null, atLatest = false)
        drain()
        return true
    }

    internal fun rejectAttachmentEvidence(expected: SessionState, peer: Long, entryId: String,
        original: DirectAttachmentReview, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, peer, entryId, original, isCurrentEntry) || mutable.value.busy ||
            original.phase != DirectAttachmentReviewPhase.EVIDENCE) return false
        mutable.value = mutable.value.copy(attachmentReview = DirectAttachmentReview(original.request, DirectAttachmentReviewPhase.DUPLICATE_RISK))
        return true
    }

    internal fun confirmAttachmentEvidence(expected: SessionState, peer: Long, entryId: String,
        original: DirectAttachmentReview, evidence: DirectAttachmentEvidence, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, peer, entryId, original, isCurrentEntry) || mutable.value.busy ||
            original.phase != DirectAttachmentReviewPhase.EVIDENCE || original.evidence.none { it === evidence } ||
            mutable.value.messages.none { it === evidence.original } ||
            !attachmentEvidence(original.request.attempt, evidence.original, session.value.user?.id)) return false
        val attempt = original.request.attempt
        val memo = savedDrafts.value[peer] ?: return false
        removeAttachmentWork(peer)
        val sameContent = memo.content === attempt.content
        mutable.value = mutable.value.copy(attachmentReview = null, atLatest = false,
            draft = if (sameContent) "" else memo.body, composerReply = if (sameContent) null else memo.composerReply)
        rememberDraft()
        drain()
        return true
    }

    /** Explicit risk acknowledgement only unlocks the exact file. A later fresh Send is separate. */
    internal fun acceptAttachmentDuplicateRisk(expected: SessionState, peer: Long, entryId: String,
        original: DirectAttachmentReview, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, peer, entryId, original, isCurrentEntry) || mutable.value.busy ||
            original.phase != DirectAttachmentReviewPhase.DUPLICATE_RISK) return false
        val work = ownedUpload(original.request.attempt) ?: return false
        val old = work.view
        work.view = DirectAttachmentDraft(work.selection, DirectAttachmentPhase.READY, old.sizeBytes, old.privacy,
            upload = original.request.attempt)
        savedDrafts.value = savedDrafts.value + (peer to savedDrafts.value.getValue(peer).withAttachment(work.view))
        mutable.value = mutable.value.copy(attachmentReview = null, atLatest = false)
        drain()
        return true
    }

    private class IncompleteRange : Exception()
    private fun ApiFailure.ambiguous() = kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.TLS,
        FailureKind.REDIRECT, FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) || (status ?: 0) >= 500
    companion object {
        internal fun factory(operations: DirectOperations, realtime: RealtimeController? = null,
            typing: TypingController? = null, mutations: MessageMutationOperations? = null,
            reactions: MessageReactionOperations? = null,
            attachmentBudget: AttachmentMemoryBudget? = null, uploads: AttachmentUploadOperations? = null,
            revalidate: (suspend () -> Unit)? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { DirectThreadViewModel(operations, realtime, typing, mutations, reactions, attachmentBudget, uploads, revalidate) }
        }
    }
}
