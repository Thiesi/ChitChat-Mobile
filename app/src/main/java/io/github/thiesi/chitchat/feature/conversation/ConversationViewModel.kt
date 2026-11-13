package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.media.AttachmentMemoryBudget
import io.github.thiesi.chitchat.core.media.AttachmentPickSource
import io.github.thiesi.chitchat.core.media.AttachmentPolicy
import io.github.thiesi.chitchat.core.media.AttachmentPolicyObservation
import io.github.thiesi.chitchat.core.media.observeAttachmentPolicy
import io.github.thiesi.chitchat.core.media.AttachmentPreparation
import io.github.thiesi.chitchat.core.media.AttachmentPreparationOutcome
import io.github.thiesi.chitchat.core.media.AttachmentPreparationTask
import io.github.thiesi.chitchat.core.media.OriginalPhotoPreparationTask
import io.github.thiesi.chitchat.core.media.PickedAttachmentRead
import io.github.thiesi.chitchat.core.media.RetainedAttachmentPreparation
import io.github.thiesi.chitchat.core.media.AttachmentUploadOperations
import io.github.thiesi.chitchat.core.media.AttachmentUploadPolicyFailure
import io.github.thiesi.chitchat.core.media.UploadTransferFacts
import io.github.thiesi.chitchat.core.conversation.ComposerReply

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.conversation.ConversationOperations
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.conversation.RoomPing
import io.github.thiesi.chitchat.core.conversation.SendResult
import io.github.thiesi.chitchat.core.conversation.validateMessageBody
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.conversation.serverInstant
import java.time.Instant
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.mutations.MessageMutationOperations
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.mutations.validateEditedBody
import io.github.thiesi.chitchat.core.reactions.MessageReactionOperations
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.reactions.selectedBy
import io.github.thiesi.chitchat.core.realtime.PresencePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeController
import io.github.thiesi.chitchat.core.typing.TypingController
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.targets.TargetSnapshot
import io.github.thiesi.chitchat.core.targets.TargetRequest
import io.github.thiesi.chitchat.core.targets.withCurrentPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout

data class TimelineAnchor(val id: Long, val offset: Int, val revision: Long = 0)
class PingSelection internal constructor(val request: TargetRequest, val id: Long)
data class ConversationUiState(
    val roomId: Long? = null, val context: ConversationContext? = null,
    val messages: List<RoomMessage> = emptyList(), val pings: List<RoomPing> = emptyList(),
    val draft: String = "", val loaded: Boolean = false, val busy: Boolean = false,
    val sending: Boolean = false, val reading: Boolean = false, val stale: Boolean = true, val latestWindow: Boolean = true,
    val fixedUpper: Long? = null, val highlightedMessageId: Long? = null,
    val olderCursor: Long? = null, val mayHaveOlder: Boolean = false,
    val atLatest: Boolean = false, val scrollRequest: Long = 0, val anchor: TimelineAnchor? = null,
    val unreadDividerId: Long? = null, val readCursor: Long = 0,
    val error: ApiFailure? = null, val readError: ApiFailure? = null, val validation: String? = null,
    val uncertainSend: Boolean = false, val reconciledAfterUncertain: Boolean = false,
    val revealed: Set<Long> = emptySet(), val notice: String? = null,
    val syncing: Boolean = false, val syncError: ApiFailure? = null,
    val newerAvailable: Boolean = false, val anchorUnavailable: Boolean = false,
    val draftsOpen: Boolean = false, val draftLimit: Boolean = false,
    val pingSelection: PingSelection? = null,
    val composerReply: ComposerReply? = null,
    val authorDialog: RoomAuthorDialog? = null, val authorWriting: Boolean = false,
    val authorUnknown: RoomAuthorUnknown? = null, val authorLimit: Boolean = false,
    val reactionDialog: RoomReactionDialog? = null, val reactionWriting: Boolean = false,
    val reactionUnknown: RoomReactionUnknown? = null, val reactionLimit: Boolean = false,
    val attachmentReview: RoomAttachmentReview? = null,
) { override fun toString() = "ConversationUiState" }

class ConversationViewModel internal constructor(private val operations: ConversationOperations,
    val realtime: RealtimeController? = null,
    val typing: TypingController? = null,
    private val mutations: MessageMutationOperations? = null,
    private val reactions: MessageReactionOperations? = null,
    private val attachmentBudget: AttachmentMemoryBudget? = null,
    private val uploads: AttachmentUploadOperations? = null) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(ConversationUiState())
    val state = mutable.asStateFlow()
    private val savedDrafts = MutableStateFlow<Map<Long, RoomDraft>>(emptyMap())
    val drafts = savedDrafts.asStateFlow()
    private var identity = session.value.identity()
    private var sequence = 0L
    private var job: Job? = null
    private var readJob: Job? = null
    private var readSequence = 0L
    private var foreground = false
    private var postInFlight = false
    private var viewport: TimelineAnchor? = null
    private var visibleNewest: Long? = null
    private var attemptedRead = 0L
    private var dividerEstablished = false
    private var syncJob: Job? = null
    private var requestedSync = 0L
    private var attemptedSync = 0L
    private var appliedSync = 0L
    private var deliberateSelection = false
    private var mutationJob: Job? = null
    private var authorPosting: RoomAuthorRequest? = null
    private val unknownAuthors = mutableMapOf<Long, RoomAuthorUnknown>()
    private var reactionPosting: RoomReactionWrite? = null
    private val unknownReactions = mutableMapOf<Long, RoomReactionUnknown>()

    private class AttachmentWork(val selection: RoomAttachmentSelection) {
        var view = RoomAttachmentDraft(selection, RoomAttachmentPhase.PREPARING)
        var job: Job? = null
        // Keep a predecessor's actual producer barrier even if this queued work is replaced
        // before its coroutine starts. Cancelling a joiner does not terminate that producer.
        var producerEnd: Job? = null
        var retained: RetainedAttachmentPreparation? = null
    }
    private val attachments = mutableMapOf<Long, AttachmentWork>()
    private var uploadPosting: RoomAttachmentUpload? = null

    init { viewModelScope.launch { session.collect { current ->
        if (current.identity() != identity) {
            retireOwnerWork(); identity = current.identity(); dividerEstablished = false
            mutable.value = ConversationUiState()
            savedDrafts.value = emptyMap()
            unknownAuthors.clear()
            unknownReactions.clear()
        } else {
            val value = mutable.value
            val context = value.context
            val policy = current.chatPolicy
            if (context != null && policy != null && context.policy.ignoredUserIds != policy.ignoredUserIds) {
                mutable.value = value.copy(context = ConversationContext(context.room, policy), revealed = emptySet(),
                    pings = value.pings.filterNot { it.sender.id in policy.ignoredUserIds })
                // Queue canonical pings/unread/window reconciliation; never cancel or replay a write.
                invalidate()
            }
            drainSync()
        }
    } } }

    fun open(roomId: Long, interacted: Boolean = false, expected: SessionState = session.value) {
        val owner = expected.identity() ?: return
        if (roomId <= 0 || owner != session.value.identity()) return
        if (owner != identity) {
            retireOwnerWork(); identity = owner; savedDrafts.value = emptyMap(); unknownAuthors.clear(); mutable.value = ConversationUiState()
            unknownReactions.clear()
        }
        if (mutable.value.roomId != roomId) {
            val wasForeground = foreground
            if (uploadPosting != null) retire(keepUpload = true) else background()
            foreground = wasForeground; dividerEstablished = false
            realtime?.selectRoom(null, false)
            val memo = savedDrafts.value[roomId]?.takeIf { it.matches(expected) }
            mutable.value = ConversationUiState(roomId = roomId, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, composerReply = memo?.composerReply,
                authorUnknown = unknownAuthors[roomId], reactionUnknown = unknownReactions[roomId],
                busy = uploadPosting != null, sending = uploadPosting?.selection?.roomId == roomId)
        }
        if (interacted) deliberateSelection = true
        if (!mutable.value.loaded && !mutable.value.busy) loadEntry()
        else if (interacted) invalidate()
    }

    fun foreground() {
        foreground = true
        if (mutable.value.attachmentReview != null) return // Same-entry restoration retains its explicit check, never replays it.
        if (mutable.value.loaded && realtime != null) invalidate()
        else if (mutable.value.roomId != null && !mutable.value.busy) loadEntry()
    }
    fun background() {
        foreground = false
        val anchor = viewport
        cancelRequest()
        mutable.value = mutable.value.copy(revealed = emptySet(), atLatest = false, anchor = anchor, draftsOpen = false)
    }
    fun cancel() { background(); realtime?.selectRoom(null, false) }

    /** Navigation must not turn an already authorized write into unknown delivery. */
    fun leavePane() {
        val value = mutable.value
        if (!belongsTo(session.value) || (uploadPosting == null && !postInFlight && !value.sending && !value.authorWriting && !value.reactionWriting &&
            authorPosting == null && reactionPosting == null && readJob?.isActive != true)) {
            cancel()
            return
        }
        foreground = false
        visibleNewest = null
        mutable.value = value.copy(revealed = emptySet(), atLatest = false, anchor = viewport, draftsOpen = false)
        realtime?.selectRoom(null, false)
        // Keep original operation/sequence markers until ACK/failure. Real background still retires them.
    }

    fun cancelRequest() {
        val uncertain = mutable.value.uncertainSend || postInFlight
        retire()
        mutable.value = mutable.value.copy(busy = false, sending = false, stale = true,
            uncertainSend = uncertain, reconciledAfterUncertain = false, notice = "cancelled")
        rememberDraft()
    }

    fun canEditDraft() = mutable.value.roomId in savedDrafts.value || savedDrafts.value.size < 100
    fun updateDraft(value: String) { updateDraft(session.value, mutable.value.roomId, value) }
    fun updateDraft(expected: SessionState, roomId: Long?, value: String): Boolean {
        if (roomId == null || roomId != mutable.value.roomId || expected.identity() == null ||
            expected.identity() != identity || expected.identity() != session.value.identity() || mutable.value.sending ||
            mutable.value.attachmentReview != null || mutable.value.authorDialog != null || mutable.value.authorWriting || mutable.value.reactionDialog != null ||
            mutable.value.reactionWriting) return false
        if (!canEditDraft() && value.isNotEmpty()) { mutable.value = mutable.value.copy(draftLimit = true); return false }
        mutable.value = mutable.value.copy(draft = value, validation = null, draftLimit = false)
        rememberDraft(); return true
    }
    fun canUseMessage(expected: SessionState, roomId: Long?, message: RoomMessage): Boolean {
        val value = mutable.value
        val context = value.context ?: return false
        return uploadPosting == null && foreground && belongsTo(expected) && ready(session.value) && roomId != null && value.roomId == roomId &&
            value.attachmentReview == null && value.pingSelection == null && value.authorDialog == null && !value.authorWriting && value.reactionDialog == null &&
            !value.reactionWriting && message.roomId == roomId && message.id > 0 &&
            value.messages.any { it === message } && !message.deleted &&
            (message.senderId !in currentPolicy(context).policy.ignoredUserIds || message.id in value.revealed)
    }
    fun canReplyTo(expected: SessionState, roomId: Long?, message: RoomMessage) =
        canUseMessage(expected, roomId, message) && composerCanSend() && !mutable.value.busy && !mutable.value.sending
    fun replyTo(expected: SessionState, roomId: Long?, message: RoomMessage): Boolean {
        if (!canReplyTo(expected, roomId, message)) return false
        mutable.value = mutable.value.copy(composerReply = ComposerReply(message.id), validation = null,
            error = mutable.value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft(); return true
    }
    fun cancelReply(expected: SessionState, roomId: Long?, original: ComposerReply) {
        val value = mutable.value
        if (!foreground || !belongsTo(expected) || roomId == null || roomId != value.roomId ||
            value.sending || value.attachmentReview != null || value.composerReply !== original) return
        mutable.value = value.copy(composerReply = null, draftLimit = false,
            error = value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft()
    }
    fun showDrafts(expected: SessionState = session.value) {
        if (expected.identity() != null && expected.identity() == identity && expected.identity() == session.value.identity() && mutable.value.attachmentReview == null)
            mutable.value = mutable.value.copy(draftsOpen = true)
    }
    fun hideDrafts(expected: SessionState = session.value) {
        if (expected.identity() == identity && expected.identity() == session.value.identity())
            mutable.value = mutable.value.copy(draftsOpen = false)
    }
    fun discardDraft(draft: RoomDraft) {
        if (!draft.matches(session.value) || mutable.value.sending || uploadPosting?.selection?.roomId == draft.roomId ||
            savedDrafts.value[draft.roomId] !== draft) return
        if (mutable.value.attachmentReview?.request?.attempt?.selection?.slot === draft.slot &&
            mutable.value.attachmentReview?.phase == RoomAttachmentReviewPhase.CHECKING) cancelRequest()
        removeAttachmentWork(draft.roomId)
        savedDrafts.value = savedDrafts.value - draft.roomId
        mutable.value = if (mutable.value.roomId == draft.roomId) mutable.value.copy(draft = "", composerReply = null, uncertainSend = false,
            reconciledAfterUncertain = false, draftLimit = false, validation = null) else mutable.value.copy(draftLimit = false)
    }
    private fun rememberDraft() {
        if (identity == null || identity != session.value.identity()) return
        val value = mutable.value; val roomId = value.roomId ?: return
        val owner = session.value.roomDraftAccount() ?: return
        val previous = savedDrafts.value[roomId]
        val name = value.context?.room?.name ?: previous?.roomName
        val attachment = attachments[roomId]?.view
        if (previous != null && previous.matches(session.value) && previous.attachment === attachment && previous.body == value.draft &&
            previous.uncertain == value.uncertainSend && previous.roomName == name && previous.composerReply === value.composerReply) return
        savedDrafts.value = if (value.draft.isEmpty() && !value.uncertainSend && value.composerReply == null && attachment == null) savedDrafts.value - roomId else
            savedDrafts.value + (roomId to RoomDraft(owner, roomId, name, value.draft, value.uncertainSend, value.composerReply,
                attachment, previous?.slot ?: attachment?.selection?.slot ?: Any(),
                previous?.takeIf { it.body == value.draft && it.composerReply === value.composerReply }?.content ?: Any()))
    }
    /** The caller has already obtained the first pick or an explicit original-bound Replace.
     * isCurrentEntry must query the real root entry now, never a cached foreground Boolean.
     */
    internal fun selectAttachment(expected: SessionState, roomId: Long, sourceEntryId: String,
        read: PickedAttachmentRead, source: AttachmentPickSource, original: RoomAttachmentDraft? = null,
        isCurrentEntry: () -> Boolean): Boolean {
        val budget = attachmentBudget ?: return false
        val owner = expected.roomDraftAccount() ?: return false
        val value = mutable.value
        if (!isCurrentEntry() || sourceEntryId.isBlank() || !foreground || roomId != value.roomId ||
            !belongsTo(expected) || expected.user?.guest != false || !composerCanSend() || value.busy || value.sending) return false
        val policy = session.value.attachmentPolicy
        if (policy == AttachmentPolicy.Invalid || policy == AttachmentPolicy.Unavailable ||
            (policy is AttachmentPolicy.Known && policy.maxBytes == 0L)) return false
        val previous = attachments[roomId]
        if (original?.selection !== previous?.selection || (original == null && previous != null) ||
            original?.upload !== previous?.view?.upload) return false
        val slot = savedDrafts.value[roomId]?.slot ?: Any()
        val selection = RoomAttachmentSelection(owner, roomId, slot, sourceEntryId, read.filename, read.mimeType)
        val retiredProducer = removeAttachmentWork(roomId)
        val work = AttachmentWork(selection).also { it.producerEnd = retiredProducer }
        attachments[roomId] = work
        rememberDraft() // Count even a file-only/preparing draft against the existing100 slots.
        val pending = viewModelScope.launch {
            retiredProducer?.join()
            val memo = savedDrafts.value[roomId]
            if (attachments[roomId] !== work || !selection.matches(session.value) ||
                memo == null || !memo.matches(session.value) || memo.slot !== slot) return@launch
            val task = AttachmentPreparationTask.forAvailableMemory(read, source, budget)
            val producer = task.start(this) { completed, outcome ->
                completed === task && adoptAttachmentOutcome(roomId, work, outcome)
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
    internal fun prepareOriginalPhoto(expected: SessionState, roomId: Long, original: RoomAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val value = mutable.value
        if (!isCurrentEntry() || !foreground || roomId != value.roomId || !belongsTo(expected) || expected.user?.guest != false ||
            !composerCanSend() || value.busy || value.sending) return false
        val policy = session.value.attachmentPolicy
        if (policy == AttachmentPolicy.Invalid || policy == AttachmentPolicy.Unavailable ||
            (policy is AttachmentPolicy.Known && policy.maxBytes == 0L)) return false
        val current = attachments[roomId] ?: return false
        val selection = current.selection
        val memo = savedDrafts.value[roomId] ?: return false
        if (current.view !== original || original.phase != RoomAttachmentPhase.PRIVACY_CHOICE ||
            !selection.matches(session.value) || !memo.matches(session.value) || memo.slot !== selection.slot ||
            current.producerEnd?.isCompleted != true) return false
        val retained = current.retained ?: return false
        val choice = retained.preparation as? AttachmentPreparation.NeedsChoice ?: return false
        val task = OriginalPhotoPreparationTask(retained, choice)
        // New worker identity, same private slot/selection. Old callbacks cannot adopt into it.
        val work = AttachmentWork(selection)
        current.retained = null
        attachments[roomId] = work
        rememberDraft()
        val producer = task.start(viewModelScope) { completed, outcome ->
            completed === task && adoptAttachmentOutcome(roomId, work, outcome)
        }
        work.producerEnd = producer
        work.job = producer.takeUnless { it.isCompleted }
        return true
    }

    private fun adoptAttachmentOutcome(roomId: Long, work: AttachmentWork, outcome: AttachmentPreparationOutcome): Boolean {
        val selection = work.selection
        val current = savedDrafts.value[roomId]
        if (attachments[roomId] !== work || !selection.matches(session.value) || current == null ||
            !current.matches(session.value) || current.slot !== selection.slot) return false
        work.view = when (outcome) {
            is AttachmentPreparationOutcome.Failed -> RoomAttachmentDraft(selection, RoomAttachmentPhase.FAILED,
                failure = outcome.kind)
            is AttachmentPreparationOutcome.Prepared -> {
                work.retained = outcome.retained
                when (val prepared = checkNotNull(outcome.retained.preparation)) {
                    is AttachmentPreparation.Ready -> RoomAttachmentDraft(selection, RoomAttachmentPhase.READY,
                        sizeBytes = prepared.payload.sizeBytes, privacy = prepared.privacy)
                    is AttachmentPreparation.NeedsChoice -> RoomAttachmentDraft(selection, RoomAttachmentPhase.PRIVACY_CHOICE,
                        sizeBytes = prepared.original.bytes.size.toLong(), privacyFailure = prepared.kind)
                }
            }
        }
        // Private completion only: preserve the latest caption/reply/uncertainty and active pane.
        savedDrafts.value = savedDrafts.value + (roomId to current.withAttachment(work.view))
        return outcome is AttachmentPreparationOutcome.Prepared
    }

    internal fun visibleAttachment(expected: SessionState, roomId: Long,
        isCurrentEntry: () -> Boolean): RoomAttachmentDraft? {
        if (!isCurrentEntry() || !foreground || mutable.value.roomId != roomId || !belongsTo(expected)) return null
        return attachments[roomId]?.view?.takeIf { it.selection.matches(session.value) }
    }

    /** Display facts for the current original selection, without mutating its private draft.
     * Recompute from current policy; neither an earlier result nor these facts authorize Send.
     */
    internal fun attachmentPolicyObservation(expected: SessionState, roomId: Long, original: RoomAttachmentDraft,
        isCurrentEntry: () -> Boolean): AttachmentPolicyObservation? {
        val current = visibleAttachment(expected, roomId, isCurrentEntry) ?: return null
        val now = session.value
        if (current.selection !== original.selection || savedDrafts.value[roomId]?.slot !== current.selection.slot ||
            !current.selection.matches(now)) return null
        val finalSize = current.sizeBytes.takeIf { current.phase in setOf(RoomAttachmentPhase.READY,
            RoomAttachmentPhase.UPLOADING, RoomAttachmentPhase.FINISHING, RoomAttachmentPhase.CANCELLING,
            RoomAttachmentPhase.UNKNOWN, RoomAttachmentPhase.CONFIRMED) }
        return observeAttachmentPolicy(now.attachmentPolicy, finalSize, current.mimeType)
    }

    internal fun removeAttachment(expected: SessionState, roomId: Long, original: RoomAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        if (!isCurrentEntry() || !foreground || !belongsTo(expected) || mutable.value.roomId != roomId ||
            mutable.value.busy || mutable.value.sending || attachments[roomId]?.selection !== original.selection ||
            attachments[roomId]?.view?.upload !== original.upload ||
            attachments[roomId]?.view?.phase in setOf(RoomAttachmentPhase.UPLOADING, RoomAttachmentPhase.FINISHING,
                RoomAttachmentPhase.CANCELLING, RoomAttachmentPhase.UNKNOWN, RoomAttachmentPhase.CONFIRMED)) return false
        removeAttachmentWork(roomId)
        rememberDraft()
        return true
    }

    private fun removeAttachmentWork(roomId: Long): Job? {
        val retired = attachments.remove(roomId) ?: return null
        if (mutable.value.attachmentReview?.request?.attempt?.selection === retired.selection)
            mutable.value = mutable.value.copy(attachmentReview = null)
        retired.job?.cancel() // Task owns this charge until its actual worker ends.
        retired.retained?.close()
        retired.retained = null
        return retired.producerEnd
    }
    private fun retireAttachments() { attachments.keys.toList().forEach { removeAttachmentWork(it) } }
    private fun retireOwnerWork() {
        retireAttachments()
        retire()
        // The retired generation keeps its own transport allocation until actual stop, but
        // cannot serialize or clear a successor generation's jobs/busy state.
        uploadPosting?.entryProbe = null
        uploadPosting = null
    }
    override fun onCleared() { retireUpload(); retireAttachments(); super.onCleared() }

    /** Explicit original-entry Send only. Captions are literal PHP-trimmed text, not commands. */
    internal fun sendAttachment(expected: SessionState, roomId: Long, entryId: String, original: RoomAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val sender = uploads ?: return false
        val value = mutable.value
        val work = attachments[roomId] ?: return false
        val memo = savedDrafts.value[roomId] ?: return false
        if (!isCurrentEntry() || entryId.isBlank() || !foreground || !belongsTo(expected) || expected.user?.guest != false ||
            roomId != value.roomId || !composerCanSend() || value.busy || value.sending || value.draftsOpen || uploadPosting != null ||
            postInFlight || authorPosting != null || reactionPosting != null || readJob?.isActive == true ||
            work.view !== original || original.phase != RoomAttachmentPhase.READY || !work.selection.matches(session.value) ||
            !memo.matches(session.value) || memo.slot !== work.selection.slot || work.producerEnd?.isCompleted != true) return false
        val retained = work.retained ?: return false
        val prepared = retained.preparation as? AttachmentPreparation.Ready ?: return false
        when (observeAttachmentPolicy(session.value.attachmentPolicy, prepared.payload.sizeBytes, prepared.payload.mimeType)) {
            AttachmentPolicyObservation.Unadvertised, AttachmentPolicyObservation.WithinAdvertisedFacts -> Unit
            else -> return false
        }
        val caption = value.draft.phpTrim()
        if (caption.codePointCount(0, caption.length) > 4000) return false
        val attempt = RoomAttachmentUpload(work.selection, entryId, memo.content, caption, value.composerReply,
            prepared.payload.sha256, System.currentTimeMillis(), isCurrentEntry)
        stopSync()
        uploadPosting = attempt
        publishUpload(attempt, RoomAttachmentPhase.UPLOADING)
        mutable.value = mutable.value.copy(busy = true, sending = true, error = null, validation = null, notice = null)
        val pending = viewModelScope.launch {
            attempt.entered = true
            try {
                if (ownedUpload(attempt) == null) throw CancellationException("Attachment retired")
                // This handle can only keep the same READY or become null. Avoid capturing its
                // payload-bearing preparation in the longer-lived job/attempt closure.
                val readyForSend = retained.preparation as? AttachmentPreparation.Ready
                    ?: throw CancellationException("Attachment retired")
                val receipt = sender.room(expected, roomId, retained, readyForSend, caption, attempt.reply?.messageId) { facts ->
                    if (uploadPosting !== attempt || ownedUpload(attempt) == null) throw CancellationException("Attachment retired")
                    attempt.facts = facts
                    attempt.observer = launch {
                        facts.collect { state ->
                            if (uploadPosting === attempt && !attempt.cancelRequested)
                                publishUpload(attempt, if (state.fullBodyReported) RoomAttachmentPhase.FINISHING else RoomAttachmentPhase.UPLOADING)
                        }
                    }
                }
                if (receipt.roomId != roomId || receipt.messageId <= 0) throw ApiFailure(FailureKind.INCOMPATIBLE)
                attempt.receipt = receipt // Positive ACK precedes any fallible canonical GET/adoption.
                ownedUpload(attempt)?.let { it.retained?.close(); it.retained = null }
                publishUpload(attempt, RoomAttachmentPhase.CONFIRMED)
            } catch (cancelled: CancellationException) {
                publishUpload(attempt, if (incompleteUpload(attempt)) RoomAttachmentPhase.READY else RoomAttachmentPhase.UNKNOWN)
                throw cancelled
            } catch (_: AttachmentUploadPolicyFailure) {
                publishUpload(attempt, RoomAttachmentPhase.READY, policyBlocked = true)
            } catch (failure: ApiFailure) {
                publishUpload(attempt, if (failure.ambiguous() && !incompleteUpload(attempt))
                    RoomAttachmentPhase.UNKNOWN else RoomAttachmentPhase.READY, error = failure)
            } finally {
                attempt.observer?.cancel()
                finishUpload(attempt, expected)
            }
        }
        attempt.job = pending.takeUnless { it.isCompleted }
        return true
    }

    private fun ownedUpload(attempt: RoomAttachmentUpload): AttachmentWork? {
        val roomId = attempt.selection.roomId
        val work = attachments[roomId] ?: return null
        val memo = savedDrafts.value[roomId] ?: return null
        return work.takeIf { it.selection === attempt.selection && attempt.selection.matches(session.value) &&
            memo.matches(session.value) && memo.slot === attempt.selection.slot }
    }

    private fun publishUpload(attempt: RoomAttachmentUpload, phase: RoomAttachmentPhase,
        error: ApiFailure? = null, policyBlocked: Boolean = false) {
        if (uploadPosting !== attempt) return
        val work = ownedUpload(attempt) ?: return
        val old = work.view
        work.view = RoomAttachmentDraft(work.selection, phase, old.sizeBytes, old.privacy,
            upload = attempt, transfer = attempt.facts?.value, uploadError = error, policyBlocked = policyBlocked)
        val roomId = work.selection.roomId
        savedDrafts.value = savedDrafts.value + (roomId to savedDrafts.value.getValue(roomId).withAttachment(work.view))
    }

    /** Only after the operation returned/threw: its contract includes actual callback/writer stop. */
    private fun incompleteUpload(attempt: RoomAttachmentUpload): Boolean {
        val facts = attempt.facts?.value ?: return true // No transport was bound/started.
        val length = facts.multipartSizeBytes ?: return true // Rejected before multipart/call creation.
        return facts.stopped && facts.multipartAttemptedUpperBound < length
    }

    internal fun cancelAttachmentUpload(expected: SessionState, roomId: Long, original: RoomAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val attempt = uploadPosting ?: return false
        val current = ownedUpload(attempt)?.view ?: return false
        if (!isCurrentEntry() || !foreground || !belongsTo(expected) || mutable.value.roomId != roomId || mutable.value.draftsOpen ||
            attempt.selection.roomId != roomId || original.upload !== attempt || current.upload !== attempt ||
            attempt.cancelRequested || attempt.facts?.value?.fullBodyReported == true) return false
        retireUpload()
        return true
    }

    private fun retireUpload() {
        val attempt = uploadPosting ?: return
        val pending = attempt.job
        attempt.cancelRequested = true
        if (!attempt.entered) {
            // All cancellation paths are feature-owned/Main-confined. DEFAULT may never enter;
            // retire the queued marker here, before admitting any successor. No transport hold exists.
            publishUpload(attempt, RoomAttachmentPhase.READY)
            uploadPosting = null
            attempt.entryProbe = null
            attempt.job = null
            mutable.value = mutable.value.copy(busy = false, sending = false)
        } else publishUpload(attempt, RoomAttachmentPhase.CANCELLING)
        pending?.cancel()
    }

    private fun finishUpload(attempt: RoomAttachmentUpload, expected: SessionState) {
        val probe = attempt.entryProbe
        attempt.entryProbe = null
        attempt.job = null
        attempt.observer = null
        if (uploadPosting !== attempt) return
        uploadPosting = null
        mutable.value = mutable.value.copy(busy = false, sending = false)
        val view = ownedUpload(attempt)?.view
        val denial = view?.uploadError
        if (denial?.status in setOf(403, 404) && denial?.code in setOf("room_forbidden", "room_not_found") &&
            probe?.invoke() == true && foreground && belongsTo(expected) && mutable.value.roomId == attempt.selection.roomId) {
            clearUnauthorized(checkNotNull(denial), unknownWrite = false)
            return
        }
        if (view?.phase == RoomAttachmentPhase.CONFIRMED && probe != null && probe() && foreground &&
            adoptConfirmedAttachment(expected, attempt.selection.roomId, view, probe)) return
        if (foreground && mutable.value.roomId != null && !mutable.value.loaded) loadEntry() else drainSync()
    }

    /** Fresh actual-entry adoption, including a new entry returning to the original private slot.
     * New authored content (even edit-away/back) survives; ACK never becomes unknown on GET failure.
     */
    internal fun adoptConfirmedAttachment(expected: SessionState, roomId: Long, original: RoomAttachmentDraft,
        isCurrentEntry: () -> Boolean): Boolean {
        val attempt = original.upload ?: return false
        val work = ownedUpload(attempt) ?: return false
        val memo = savedDrafts.value[roomId] ?: return false
        if (!isCurrentEntry() || !foreground || !belongsTo(expected) || roomId != mutable.value.roomId ||
            roomId != attempt.selection.roomId || work.view !== original || original.phase != RoomAttachmentPhase.CONFIRMED ||
            attempt.receipt == null || uploadPosting != null || mutable.value.busy || readJob?.isActive == true) return false
        removeAttachmentWork(roomId)
        val sameContent = memo.content === attempt.content
        mutable.value = mutable.value.copy(draft = if (sameContent) "" else memo.body,
            composerReply = if (sameContent) null else memo.composerReply)
        rememberDraft()
        request { current ->
            mutable.value = mutable.value.copy(notice = "sent")
            reload(current, forceLatest = true)
        }
        return true
    }

    /** One explicit bounded history check, never a POST, text-send consent or automatic resolution.
     * Configuration retains the private request; every display/action needs fresh root entry proof.
     */
    internal fun checkAttachmentHistory(expected: SessionState, roomId: Long, entryId: String,
        original: RoomAttachmentDraft, isCurrentEntry: () -> Boolean): Boolean {
        val attempt = original.upload ?: return false
        val work = ownedUpload(attempt) ?: return false
        val value = mutable.value
        if (!isCurrentEntry() || entryId.isBlank() || !foreground || !belongsTo(expected) || !ready(session.value) ||
            value.roomId != roomId || attempt.selection.roomId != roomId || work.view !== original ||
            original.phase != RoomAttachmentPhase.UNKNOWN || value.busy || uploadPosting != null ||
            readJob?.isActive == true || postInFlight || authorPosting != null || reactionPosting != null ||
            value.authorDialog != null || value.reactionDialog != null || value.pingSelection != null || value.draftsOpen) return false
        val check = RoomAttachmentReviewRequest(attempt, entryId)
        mutable.value = value.copy(attachmentReview = RoomAttachmentReview(check, RoomAttachmentReviewPhase.CHECKING), atLatest = false)
        request(preserveAttachmentReview = true) { current ->
            fun currentCheck() {
                current()
                if (!foreground || mutable.value.attachmentReview?.request !== check || ownedUpload(attempt)?.view?.phase != RoomAttachmentPhase.UNKNOWN)
                    throw CancellationException("Attachment review retired")
            }
            try {
                reload(::currentCheck, forceLatest = true)
                currentCheck()
                val evidence = mutable.value.messages.filter { attachmentEvidence(attempt, it, expected.user?.id) }
                    .map(::RoomAttachmentEvidence)
                mutable.value = mutable.value.copy(attachmentReview = RoomAttachmentReview(check,
                    if (evidence.isEmpty()) RoomAttachmentReviewPhase.DUPLICATE_RISK else RoomAttachmentReviewPhase.EVIDENCE,
                    evidence))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                currentCheck()
                mutable.value = mutable.value.copy(attachmentReview = RoomAttachmentReview(check, RoomAttachmentReviewPhase.FAILED, error = failure))
                throw failure
            }
        }
        return true
    }

    private fun attachmentEvidence(attempt: RoomAttachmentUpload, row: RoomMessage, viewer: Long?): Boolean =
        viewer != null && row.senderId == viewer && row.roomId == attempt.selection.roomId && row.id > 0 && !row.deleted &&
            row.attachment?.sha256 == attempt.sha256 &&
            serverInstant(row.createdAt)?.isAfter(Instant.ofEpochMilli(attempt.startedAtMillis)) == true

    internal fun visibleAttachmentReview(expected: SessionState, roomId: Long, entryId: String,
        isCurrentEntry: () -> Boolean): RoomAttachmentReview? {
        val review = mutable.value.attachmentReview ?: return null
        return review.takeIf { attachmentReviewCurrent(expected, roomId, entryId, review, isCurrentEntry) }
    }

    private fun attachmentReviewCurrent(expected: SessionState, roomId: Long, entryId: String,
        original: RoomAttachmentReview, isCurrentEntry: () -> Boolean, requireReady: Boolean = true): Boolean {
        val check = original.request
        return isCurrentEntry() && foreground && belongsTo(expected) && (!requireReady || ready(session.value)) && mutable.value.roomId == roomId &&
            check.entryId == entryId && check.attempt.selection.roomId == roomId &&
            mutable.value.attachmentReview === original && ownedUpload(check.attempt)?.view?.phase == RoomAttachmentPhase.UNKNOWN
    }

    internal fun closeAttachmentReview(expected: SessionState, roomId: Long, entryId: String,
        original: RoomAttachmentReview, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, roomId, entryId, original, isCurrentEntry, requireReady = false)) return false
        if (original.phase == RoomAttachmentReviewPhase.CHECKING) cancelRequest()
        else {
            mutable.value = mutable.value.copy(attachmentReview = null, atLatest = false)
            drainSync()
        }
        return true
    }

    internal fun rejectAttachmentEvidence(expected: SessionState, roomId: Long, entryId: String,
        original: RoomAttachmentReview, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, roomId, entryId, original, isCurrentEntry) || mutable.value.busy ||
            original.phase != RoomAttachmentReviewPhase.EVIDENCE) return false
        mutable.value = mutable.value.copy(attachmentReview = RoomAttachmentReview(original.request, RoomAttachmentReviewPhase.DUPLICATE_RISK))
        return true
    }

    internal fun confirmAttachmentEvidence(expected: SessionState, roomId: Long, entryId: String,
        original: RoomAttachmentReview, evidence: RoomAttachmentEvidence, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, roomId, entryId, original, isCurrentEntry) || mutable.value.busy ||
            original.phase != RoomAttachmentReviewPhase.EVIDENCE || original.evidence.none { it === evidence } ||
            mutable.value.messages.none { it === evidence.original } ||
            !attachmentEvidence(original.request.attempt, evidence.original, session.value.user?.id)) return false
        val attempt = original.request.attempt
        val memo = savedDrafts.value[roomId] ?: return false
        removeAttachmentWork(roomId)
        val sameContent = memo.content === attempt.content
        mutable.value = mutable.value.copy(attachmentReview = null, atLatest = false,
            draft = if (sameContent) "" else memo.body, composerReply = if (sameContent) null else memo.composerReply)
        rememberDraft()
        drainSync()
        return true
    }

    /** Explicit risk acknowledgement only unlocks the exact file. A later fresh Send is separate. */
    internal fun acceptAttachmentDuplicateRisk(expected: SessionState, roomId: Long, entryId: String,
        original: RoomAttachmentReview, isCurrentEntry: () -> Boolean): Boolean {
        if (!attachmentReviewCurrent(expected, roomId, entryId, original, isCurrentEntry) || mutable.value.busy ||
            original.phase != RoomAttachmentReviewPhase.DUPLICATE_RISK) return false
        val work = ownedUpload(original.request.attempt) ?: return false
        val old = work.view
        work.view = RoomAttachmentDraft(work.selection, RoomAttachmentPhase.READY, old.sizeBytes, old.privacy,
            upload = original.request.attempt)
        savedDrafts.value = savedDrafts.value + (roomId to savedDrafts.value.getValue(roomId).withAttachment(work.view))
        mutable.value = mutable.value.copy(attachmentReview = null, atLatest = false)
        drainSync()
        return true
    }

    fun reveal(id: Long) { mutable.value = mutable.value.copy(revealed = mutable.value.revealed + id) }
    private fun loadEntry() { request { current -> refreshWindow(current) } }
    fun refresh() { request { current -> refreshWindow(current, explicit = true) } }
    fun latest() { request { current -> reload(current, forceLatest = true, explicitReconciliation = true) } }

    private fun authorRowCurrent(request: RoomAuthorRequest): Boolean {
        val value = mutable.value
        val user = session.value.user ?: return false
        val context = value.context ?: return false
        val message = request.original
        val hints = message.authorActions ?: return false
        return mutations != null && foreground && belongsTo(request.expected) && ready(session.value) &&
            session.value.phase == SessionPhase.AUTHENTICATED && !user.guest && value.loaded && !value.stale &&
            value.roomId == message.roomId && value.attachmentReview == null && value.pingSelection == null && context.room.isMember &&
            message.id > 0 && message.senderId == user.id && !message.deleted && message.body != null &&
            message.type in setOf("text", "emote", "attachment") && value.messages.any { it === message } &&
            when (request.kind) {
                AuthorChangeKind.EDIT -> hints.canEdit && currentPolicy(context).mute == null
                AuthorChangeKind.DELETE -> hints.canDelete
            }
    }

    fun canBeginAuthor(expected: SessionState, roomId: Long, message: RoomMessage, kind: AuthorChangeKind): Boolean =
        roomId == mutable.value.roomId && mutable.value.authorDialog == null && mutable.value.reactionDialog == null && !mutable.value.busy &&
            readJob?.isActive != true && unknownAuthors[roomId] == null &&
            (roomId in unknownAuthors || unknownAuthors.size < 100) && authorRowCurrent(RoomAuthorRequest(expected, message, kind))

    fun beginAuthor(expected: SessionState, roomId: Long, message: RoomMessage, kind: AuthorChangeKind): Boolean {
        if (!canBeginAuthor(expected, roomId, message, kind)) {
            if (belongsTo(expected) && roomId == mutable.value.roomId && unknownAuthors.size >= 100)
                mutable.value = mutable.value.copy(authorLimit = true)
            return false
        }
        val request = RoomAuthorRequest(expected, message, kind)
        visibleNewest = null
        mutable.value = mutable.value.copy(authorDialog = RoomAuthorDialog(request,
            if (kind == AuthorChangeKind.EDIT) message.body.orEmpty() else ""), atLatest = false, authorLimit = false)
        return true
    }

    fun authorAvailable(request: RoomAuthorRequest) = mutable.value.authorDialog?.request === request &&
        mutable.value.authorDialog?.checked != true && authorRowCurrent(request) && unknownAuthors[request.original.roomId] == null

    fun authorTargetCurrent(request: RoomAuthorRequest) = mutable.value.authorDialog?.request === request &&
        belongsTo(request.expected) && mutable.value.roomId == request.original.roomId &&
        mutable.value.messages.any { it === request.original && !it.deleted }

    fun authorCapacityFull() = unknownAuthors.size >= 100 && mutable.value.roomId !in unknownAuthors

    private fun currentAuthorRow(request: RoomAuthorRequest): RoomMessage? = mutable.value.messages.firstOrNull {
        foreground && belongsTo(request.expected) && mutable.value.roomId == request.original.roomId &&
            it.roomId == request.original.roomId && it.id == request.original.id && !it.deleted &&
            it.senderId == session.value.user?.id
    }
    fun authorCurrentPreview(request: RoomAuthorRequest): String? = currentAuthorRow(request)?.body
    fun canReviewAuthor(request: RoomAuthorRequest): Boolean {
        val dialog = mutable.value.authorDialog ?: return false
        val current = currentAuthorRow(request) ?: return false
        return dialog.request === request && dialog.checked && request.kind == AuthorChangeKind.EDIT &&
            !mutable.value.busy && readJob?.isActive != true && unknownAuthors[current.roomId] == null &&
            !authorCapacityFull() && authorRowCurrent(RoomAuthorRequest(request.expected, current, AuthorChangeKind.EDIT))
    }
    /** Explicit fresh choice after GET: preserve proposed text, never replay the retired request. */
    fun reviewAuthorProposal(request: RoomAuthorRequest): Boolean {
        if (!canReviewAuthor(request)) return false
        val dialog = checkNotNull(mutable.value.authorDialog)
        val current = checkNotNull(currentAuthorRow(request))
        mutable.value = mutable.value.copy(authorDialog = RoomAuthorDialog(
            RoomAuthorRequest(request.expected, current, AuthorChangeKind.EDIT), dialog.body), error = null)
        return true
    }

    /** A refresh may replace DTO instances without changing the author's revision. Retain private
     * edit text only for that same revision; use the fresh row/permissions/quote/file for any POST.
     * A changed body/edit timestamp/type/author or deletion never upgrades the old modal. */
    private fun rebindUnchangedAuthor(messages: List<RoomMessage>) {
        val request = mutable.value.authorDialog?.request ?: return
        if (!belongsTo(request.expected)) return
        val original = request.original
        val current = messages.firstOrNull { it.id == original.id && it.roomId == original.roomId } ?: return
        if (!current.deleted && current.senderId == original.senderId && current.type == original.type &&
            current.body == original.body && current.editedAt == original.editedAt && current.createdAt == original.createdAt)
            request.original = current
    }

    fun updateAuthorBody(request: RoomAuthorRequest, body: String) {
        if (authorAvailable(request) && !mutable.value.busy && request.kind == AuthorChangeKind.EDIT)
            mutable.value = mutable.value.copy(authorDialog = RoomAuthorDialog(request, body))
    }

    fun dismissAuthor(request: RoomAuthorRequest) {
        if (mutable.value.authorDialog?.request === request && belongsTo(request.expected) && !mutable.value.busy)
            mutable.value = mutable.value.copy(authorDialog = null, atLatest = false)
    }

    fun submitAuthor(request: RoomAuthorRequest) {
        val value = mutable.value
        if (!authorAvailable(request) || value.busy || readJob?.isActive == true) return
        val body = value.authorDialog?.body.orEmpty()
        if (request.kind == AuthorChangeKind.EDIT) {
            val invalid = validateEditedBody(body) ?: "message_unchanged".takeIf { body.phpTrim() == request.original.body }
            if (invalid != null) {
                mutable.value = value.copy(authorDialog = RoomAuthorDialog(request, body, invalid)); return
            }
        }
        mutationRequest(request.original.roomId) { current ->
            // Check the same canonical instance again after waiting for session readiness.
            if (!authorAvailable(request)) throw CancellationException("Author selection changed")
            authorPosting = request
            mutable.value = mutable.value.copy(authorWriting = true)
            val result = when (request.kind) {
                AuthorChangeKind.EDIT -> checkNotNull(mutations).editRoom(request.expected, request.original, body)
                AuthorChangeKind.DELETE -> checkNotNull(mutations).deleteRoom(request.expected, request.original)
            }
            current()
            if (result.id != request.original.id || result.roomId != request.original.roomId ||
                result.senderId != request.original.senderId || result.type != request.original.type ||
                (request.kind == AuthorChangeKind.DELETE && !result.deleted)) throw ApiFailure(FailureKind.INCOMPATIBLE)
            // Acknowledgment precedes every fallible GET. A later failure is NOT write uncertainty.
            authorPosting = null
            mutable.value = mutable.value.copy(messages = applyRoomAuthorResult(mutable.value.messages, result),
                authorDialog = null, authorWriting = false, stale = true,
                notice = if (request.kind == AuthorChangeKind.EDIT) "author_edited" else "author_deleted")
            val revision = requestedSync
            synchronizeWindow(mutable.value, sequence, current)
            appliedSync = maxOf(appliedSync, revision); attemptedSync = maxOf(attemptedSync, revision)
        }
    }

    /** Only this explicit GET clears author uncertainty; passive sync/entry/send reconciliation never does. */
    fun checkAuthorHistory(expected: SessionState, original: RoomAuthorUnknown) {
        if (!foreground || !belongsTo(expected) || mutable.value.roomId != original.roomId ||
            unknownAuthors[original.roomId] !== original || mutable.value.busy || readJob?.isActive == true) return
        val editor = mutable.value.authorDialog?.takeIf { it.request.original.roomId == original.roomId &&
            it.request.original.id == original.messageId && it.request.kind == original.kind }
        mutationRequest(original.roomId) { current ->
            if (mutable.value.loaded) synchronizeWindow(mutable.value, sequence, current)
            else reload(current) // Does not grant uncertain-send consent.
            current()
            if (unknownAuthors[original.roomId] === original) {
                unknownAuthors.remove(original.roomId)
                val retained = editor?.takeIf { mutable.value.authorDialog?.request === it.request }
                mutable.value = mutable.value.copy(authorUnknown = null, authorLimit = false, notice = "author_checked",
                    authorDialog = if (retained != null && retained.request.kind == AuthorChangeKind.EDIT)
                        RoomAuthorDialog(retained.request, retained.body, checked = true)
                    else if (retained != null) null else mutable.value.authorDialog)
            }
        }
    }

    private fun rememberUnknownAuthor(request: RoomAuthorRequest) {
        val unknown = RoomAuthorUnknown(request.original.roomId, request.original.id, request.kind)
        unknownAuthors[unknown.roomId] = unknown // Capacity was reserved before the POST could start.
        if (mutable.value.roomId == unknown.roomId) mutable.value = mutable.value.copy(authorUnknown = unknown)
    }

    private fun reactionRowCurrent(request: RoomReactionRequest): Boolean {
        val value = mutable.value
        val context = value.context ?: return false
        val user = session.value.user ?: return false
        val row = request.original
        val policy = session.value.chatPolicy ?: return false
        return reactions != null && foreground && belongsTo(request.expected) && ready(session.value) &&
            session.value.phase == SessionPhase.AUTHENTICATED && !user.guest && value.loaded && !value.stale &&
            value.roomId == row.roomId && value.attachmentReview == null && value.pingSelection == null && context.room.isMember && row.id > 0 &&
            value.messages.any { it === row } && (row.senderId !in policy.ignoredUserIds || row.id in value.revealed)
    }

    internal fun canBeginReactions(expected: SessionState, roomId: Long, row: RoomMessage): Boolean =
        roomId == mutable.value.roomId && mutable.value.authorDialog == null && mutable.value.reactionDialog == null &&
            !mutable.value.busy && readJob?.isActive != true && unknownReactions[roomId] == null &&
            unknownReactions.size < 100 && reactionRowCurrent(RoomReactionRequest(expected, row))

    internal fun beginReactions(expected: SessionState, roomId: Long, row: RoomMessage): Boolean {
        if (!canBeginReactions(expected, roomId, row)) {
            if (belongsTo(expected) && roomId == mutable.value.roomId && unknownReactions.size >= 100)
                mutable.value = mutable.value.copy(reactionLimit = true)
            return false
        }
        visibleNewest = null
        mutable.value = mutable.value.copy(reactionDialog = RoomReactionDialog(RoomReactionRequest(expected, row)),
            atLatest = false, reactionLimit = false)
        return true
    }

    private fun reactionChoiceCurrent(request: RoomReactionRequest, emoji: ReactionEmoji, selected: Boolean): Boolean {
        val value = mutable.value
        val dialog = value.reactionDialog ?: return false
        val viewer = session.value.user?.id ?: return false
        return dialog.request === request && !dialog.checked &&
            value.authorDialog == null && unknownReactions[request.original.roomId] == null && reactionRowCurrent(request) &&
            (request.original.reactions.firstOrNull { it.emoji == emoji.value }?.selectedBy(viewer) == true) != selected &&
            (!selected || (!request.original.deleted && value.context?.let { currentPolicy(it).mute } == null))
    }

    internal fun canSetReaction(request: RoomReactionRequest, emoji: ReactionEmoji, selected: Boolean) =
        !mutable.value.busy && readJob?.isActive != true && reactionChoiceCurrent(request, emoji, selected)

    internal fun setReaction(request: RoomReactionRequest, emoji: ReactionEmoji, selected: Boolean) {
        if (!canSetReaction(request, emoji, selected)) return
        mutationRequest(request.original.roomId) { current ->
            if (!reactionChoiceCurrent(request, emoji, selected)) throw CancellationException("Reaction selection changed")
            reactionPosting = RoomReactionWrite(request, emoji, selected)
            mutable.value = mutable.value.copy(reactionWriting = true)
            val result = checkNotNull(reactions).setRoom(request.expected, request.original, emoji, selected)
            current()
            // Confirm before any fallible canonical GET. An event may have invalidated this summary already.
            reactionPosting = null
            mutable.value = mutable.value.copy(messages = applyRoomReactionResult(mutable.value.messages, request.original, result),
                reactionDialog = null, reactionWriting = false, stale = true, notice = "reaction_changed")
            val revision = requestedSync
            synchronizeWindow(mutable.value, sequence, current)
            appliedSync = maxOf(appliedSync, revision); attemptedSync = maxOf(attemptedSync, revision)
        }
    }

    internal fun dismissReactions(request: RoomReactionRequest) {
        if (mutable.value.reactionDialog?.request === request && belongsTo(request.expected) && !mutable.value.busy)
            mutable.value = mutable.value.copy(reactionDialog = null, atLatest = false)
    }

    private fun rememberUnknownReaction(write: RoomReactionWrite) {
        val unknown = RoomReactionUnknown(write.request.original.roomId, write.request.original.id, write.emoji.value, write.selected)
        unknownReactions[unknown.roomId] = unknown // One reserved slot per room, no eviction or replay.
        if (mutable.value.roomId == unknown.roomId) mutable.value = mutable.value.copy(reactionUnknown = unknown)
    }

    internal fun checkReactionHistory(expected: SessionState, original: RoomReactionUnknown) {
        if (!foreground || !belongsTo(expected) || mutable.value.roomId != original.roomId ||
            unknownReactions[original.roomId] !== original || mutable.value.busy || readJob?.isActive == true ||
            mutable.value.authorDialog != null) return
        val dialog = mutable.value.reactionDialog
        mutationRequest(original.roomId) { current ->
            if (mutable.value.loaded) synchronizeWindow(mutable.value, sequence, current) else reload(current)
            current()
            if (unknownReactions[original.roomId] === original) {
                unknownReactions.remove(original.roomId)
                mutable.value = mutable.value.copy(reactionUnknown = null, reactionLimit = false, notice = "reaction_checked",
                    reactionDialog = if (dialog != null && mutable.value.reactionDialog?.request === dialog.request)
                        RoomReactionDialog(dialog.request, checked = true) else mutable.value.reactionDialog)
            }
        }
    }

    internal fun reactionCapacityFull() = unknownReactions.size >= 100 && mutable.value.roomId !in unknownReactions

    internal fun reactionCurrentRow(request: RoomReactionRequest): RoomMessage? {
        val value = mutable.value
        if (value.reactionDialog?.request !== request || !belongsTo(request.expected) || value.roomId != request.original.roomId) return null
        val policy = session.value.chatPolicy ?: return null
        return value.messages.firstOrNull { it.roomId == request.original.roomId && it.id == request.original.id &&
            (it.senderId !in policy.ignoredUserIds || it.id in value.revealed) }
    }

    internal fun canReviewReactions(request: RoomReactionRequest): Boolean {
        val value = mutable.value
        val dialog = value.reactionDialog ?: return false
        val row = reactionCurrentRow(request) ?: return false
        val fresh = RoomReactionRequest(request.expected, row)
        return dialog.request === request && (dialog.checked || row !== request.original) && !value.busy && readJob?.isActive != true &&
            unknownReactions[row.roomId] == null && unknownReactions.size < 100 && reactionRowCurrent(fresh)
    }

    /** Explicit review of a NEW canonical result, never automatic reauthorization of old callbacks. */
    internal fun reviewReactions(request: RoomReactionRequest): Boolean {
        if (!canReviewReactions(request)) return false
        val row = reactionCurrentRow(request) ?: return false
        mutable.value = mutable.value.copy(reactionDialog = RoomReactionDialog(RoomReactionRequest(request.expected, row)))
        return true
    }

    private fun mutationRequest(roomId: Long, action: suspend (() -> Unit) -> Unit) {
        val expected = session.value.identity() ?: return
        if (mutable.value.busy || mutable.value.attachmentReview != null || uploadPosting != null || expected != identity) return
        stopSync()
        val attempt = ++sequence
        mutable.value = mutable.value.copy(busy = true, error = null)
        mutationJob = viewModelScope.launch {
            try {
                withTimeout(60_000) {
                    val currentSession = withTimeoutOrNull(30_000) { session.first { !it.busy || it.identity() != expected } }
                        ?: throw ApiFailure(FailureKind.TIMEOUT)
                    ensureCurrent(expected, attempt, roomId)
                    if (!ready(currentSession)) throw ApiFailure(FailureKind.HTTP, code = "csrf_failed")
                    action { ensureCurrent(expected, attempt, roomId) }
                }
            } catch (_: TimeoutCancellationException) {
                if (isCurrent(expected, attempt, roomId)) mutable.value = mutable.value.copy(error = ApiFailure(FailureKind.TIMEOUT), stale = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (isCurrent(expected, attempt, roomId)) {
                    // Any exception after starting a mutation can follow its committed write.
                    if (failure.status in setOf(403, 404) && failure.code in setOf("room_forbidden", "room_not_found"))
                        clearUnauthorized(failure)
                    else mutable.value = mutable.value.copy(error = failure, stale = true)
                }
            } finally {
                if (isCurrent(expected, attempt, roomId)) {
                    authorPosting?.let(::rememberUnknownAuthor)
                    reactionPosting?.let(::rememberUnknownReaction)
                    authorPosting = null; reactionPosting = null; mutationJob = null
                    // On failure retain the foreground proposal until explicit close/background.
                    // Success already retired it before its fallible canonical GET.
                    mutable.value = mutable.value.copy(busy = false, authorWriting = false, reactionWriting = false)
                    drainSync()
                }
            }
        }
    }

    private suspend fun refreshWindow(current: () -> Unit, explicit: Boolean = false) {
        val value = mutable.value
        if (value.loaded && !value.latestWindow && !(explicit && value.uncertainSend))
            synchronizeWindow(value, sequence, current)
        else reload(current, explicitReconciliation = explicit)
    }

    /** Reply navigation adopts in place. Notification entry preparation below needs no temporary latest window. */
    fun canAdoptTarget(snapshot: TargetSnapshot.RoomMessage): Boolean = foreground && ready(session.value) &&
        snapshot.matches(session.value) && snapshot.available && snapshot.context.room.id == mutable.value.roomId &&
        !mutable.value.busy && uploadPosting == null && mutable.value.attachmentReview == null && mutable.value.authorDialog == null && mutable.value.reactionDialog == null && !postInFlight && readJob?.isActive != true

    fun belongsTo(expected: SessionState) = expected.identity() != null && expected.identity() == identity &&
        identity == session.value.identity()

    fun targetDenied(request: TargetRequest, error: ApiFailure) {
        val target = request.target as? MessageTarget.RoomMessage ?: return
        if (!request.matches(session.value) || !belongsTo(request.original) || target.roomId != mutable.value.roomId ||
            error.status !in setOf(403, 404) || error.code == "csrf_failed") return
        clearUnauthorized(error) // Confirmed access loss retires content, preserving any unknown delivery/draft.
    }

    fun adoptTarget(snapshot: TargetSnapshot.RoomMessage): Boolean {
        val fresh = snapshot.withCurrentPolicy(session.value) as? TargetSnapshot.RoomMessage ?: return false
        if (!canAdoptTarget(fresh)) return false
        return installTarget(fresh)
    }

    /** Root must bind this to the actual original navigation entry and consume/navigate synchronously. */
    fun canPrepareTarget(snapshot: TargetSnapshot.RoomMessage): Boolean = ready(session.value) &&
        snapshot.matches(session.value) && snapshot.available && uploadPosting == null && !postInFlight && authorPosting == null && reactionPosting == null &&
        mutable.value.attachmentReview == null && mutable.value.authorDialog == null && mutable.value.reactionDialog == null && readJob?.isActive != true

    fun prepareTarget(snapshot: TargetSnapshot.RoomMessage): Boolean {
        val fresh = snapshot.withCurrentPolicy(session.value) as? TargetSnapshot.RoomMessage ?: return false
        if (!canPrepareTarget(fresh)) return false
        prepareRoom(fresh.context.room.id, fresh.request.original)
        return installTarget(fresh).also { mutable.value = mutable.value.copy(notice = null) }
    }

    fun canPrepareTarget(snapshot: TargetSnapshot.RoomPing): Boolean = ready(session.value) &&
        snapshot.matches(session.value) && snapshot.available && uploadPosting == null && !postInFlight && authorPosting == null && reactionPosting == null &&
        mutable.value.attachmentReview == null && mutable.value.authorDialog == null && mutable.value.reactionDialog == null && readJob?.isActive != true

    fun prepareTarget(snapshot: TargetSnapshot.RoomPing): Boolean {
        val fresh = snapshot.withCurrentPolicy(session.value) as? TargetSnapshot.RoomPing ?: return false
        if (!canPrepareTarget(fresh)) return false
        prepareRoom(fresh.context.room.id, fresh.request.original)
        mutable.value = mutable.value.copy(context = fresh.context, pings = fresh.pings, notice = null,
            pingSelection = PingSelection(fresh.request, fresh.request.target.id))
        return true // The modal intent exists BEFORE root navigation/actor consumption can expose chat.
    }

    fun dismissTargetPing(choice: PingSelection) {
        val target = choice.request.target as? MessageTarget.RoomPing ?: return
        if (mutable.value.pingSelection !== choice || !choice.request.matches(session.value) ||
            !belongsTo(choice.request.original) || target.roomId != mutable.value.roomId) return
        visibleNewest = null
        mutable.value = mutable.value.copy(pingSelection = null, atLatest = false) // Closing is not viewport proof.
    }

    private fun prepareRoom(roomId: Long, expected: SessionState) {
        background() // Only GET work can be active; the guards reject send/read POSTs before this call.
        if (mutable.value.roomId != roomId) {
            realtime?.selectRoom(null, false); dividerEstablished = false
            val memo = savedDrafts.value[roomId]?.takeIf { it.matches(expected) }
            mutable.value = ConversationUiState(roomId = roomId, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, composerReply = memo?.composerReply,
                authorUnknown = unknownAuthors[roomId], reactionUnknown = unknownReactions[roomId])
        }
        deliberateSelection = true
    }

    private fun installTarget(fresh: TargetSnapshot.RoomMessage): Boolean {
        val target = fresh.request.target as? MessageTarget.RoomMessage ?: return false
        val value = mutable.value
        stopSync() // GET work only: active send/read POSTs were rejected above, never cancelled/replayed.
        sequence++; readSequence++; job?.cancel(); job = null
        readJob?.cancel(); readJob = null; visibleNewest = null; attemptedRead = 0
        viewport = TimelineAnchor(target.id, 0, sequence)
        mutable.value = value.copy(context = fresh.context, messages = fresh.page.messages, pings = fresh.pings,
            loaded = true, stale = false, syncing = false, syncError = null, latestWindow = false,
            fixedUpper = target.id, highlightedMessageId = target.id, atLatest = false,
            pingSelection = null,
            olderCursor = fresh.page.olderCursor, mayHaveOlder = fresh.page.mayHaveOlder,
            anchor = viewport, anchorUnavailable = false, newerAvailable = true, revealed = emptySet(),
            error = value.error.takeIf { value.uncertainSend }, validation = null,
            readCursor = maxOf(value.readCursor, fresh.context.room.lastReadMessageId ?: 0),
            reconciledAfterUncertain = false)
        rememberDraft(); drainSync()
        return true
    }

    /** Invalidation is queued while a user operation runs; it never cancels or replays a POST. */
    fun invalidate() {
        if (realtime == null || mutable.value.roomId == null || session.value.identity() == null || session.value.identity() != identity) return
        requestedSync++
        drainSync()
    }

    fun connectionLost() { if (mutable.value.loaded) mutable.value = mutable.value.copy(stale = true) }

    fun enterRoom() {
        val value = mutable.value
        val context = value.context ?: return
        if (value.loaded && !value.stale && !value.busy && ready(session.value) && presenceEligible(context))
            realtime?.selectRoom(checkNotNull(value.roomId), true, interacted = true)
    }

    fun presenceEligible(context: ConversationContext) = context.room.isMember ||
        session.value.user?.roles?.any { it in setOf("super_admin", "admin", "chat_admin", "global_moderator") } == true

    private fun selectPresence() {
        val value = mutable.value
        val context = value.context ?: return
        val controller = realtime ?: return
        if (!presenceEligible(context)) controller.selectRoom(null, false)
        else if (deliberateSelection) controller.selectRoom(value.roomId, true, interacted = true)
        else if (controller.state.value.selectedRoomId == value.roomId) controller.selectRoom(value.roomId, true)
        // Restoring a route alone must not acquire a new lease or reset inactivity.
        deliberateSelection = false
    }

    private fun activePresence(): Boolean {
        val connection = realtime?.state?.value ?: return true
        return connection.generation == session.value.generation && connection.selectedRoomId == mutable.value.roomId &&
            connection.presencePhase in setOf(PresencePhase.ACTIVE, PresencePhase.WARNING)
    }

    fun older() {
        val before = mutable.value.olderCursor ?: return
        if (!mutable.value.mayHaveOlder || mutable.value.stale || mutable.value.uncertainSend) return
        val anchor = viewport
        request { current ->
            val page = operations.history(checkNotNull(mutable.value.roomId), before)
            current()
            val merged = (page.messages + mutable.value.messages).distinctBy { it.id }.sortedBy { it.id }
            val trimmed = merged.take(500)
            val old = mutable.value
            val live = old.latestWindow && merged.size <= 500
            val targetLost = old.highlightedMessageId != null && trimmed.none { it.id == old.highlightedMessageId }
            rebindUnchangedAuthor(trimmed)
            mutable.value = mutable.value.copy(messages = trimmed, olderCursor = page.olderCursor,
                mayHaveOlder = page.mayHaveOlder, latestWindow = live,
                fixedUpper = if (live) null else if (merged.size > 500) trimmed.lastOrNull()?.id else old.fixedUpper ?: trimmed.lastOrNull()?.id,
                highlightedMessageId = old.highlightedMessageId.takeUnless { targetLost },
                anchorUnavailable = old.anchorUnavailable || targetLost,
                anchor = anchor?.copy(revision = sequence), notice = if (merged.size > 500) "window" else null)
        }
    }

    fun acknowledgeDuplicateRisk() {
        if (mutable.value.uncertainSend && mutable.value.reconciledAfterUncertain && !mutable.value.busy) {
            mutable.value = mutable.value.copy(uncertainSend = false, reconciledAfterUncertain = false, notice = "duplicate_risk")
            rememberDraft()
        }
    }

    fun send(expected: SessionState, roomId: Long?) {
        if (belongsTo(expected) && roomId != null && mutable.value.roomId == roomId) send()
    }
    fun send() {
        val value = mutable.value
        if (!canSend() || value.busy) return
        val invalid = validateMessageBody(value.draft)
        if (invalid != null) { mutable.value = value.copy(validation = invalid); return }
        val body = value.draft
        val typingSession = session.value
        request(sending = true) { current ->
            postInFlight = true
            val result = operations.send(checkNotNull(value.roomId), body, value.composerReply?.messageId)
            current(); postInFlight = false
            typing?.sent(typingSession, TypingTarget.Room(checkNotNull(value.roomId)))
            realtime?.activity(checkNotNull(value.roomId)) // Only an acknowledged, current send/ping.
            mutable.value = mutable.value.copy(draft = "", composerReply = null, notice = if (result is SendResult.Ping) "ping_sent" else "sent")
            when (result) {
                is SendResult.Message -> if (value.latestWindow) {
                    val existing = mutable.value
                    val combined = (existing.messages + result.message).distinctBy { it.id }.sortedBy { it.id }
                    val messages = combined.takeLast(500)
                    mutable.value = mutable.value.copy(messages = messages, scrollRequest = mutable.value.scrollRequest + 1,
                        olderCursor = if (combined.size > 500) messages.firstOrNull()?.id else existing.olderCursor,
                        mayHaveOlder = existing.mayHaveOlder || combined.size > 500)
                } else reload(current, forceLatest = true)
                is SendResult.Ping -> mutable.value = mutable.value.copy(pings = (mutable.value.pings + result.ping)
                    .distinctBy { it.id }.filterNot { it.sender.id in currentPolicy(value.context!!).policy.ignoredUserIds }.sortedBy { it.id }.takeLast(100))
            }
        }
    }

    fun canSend(): Boolean = attachments[mutable.value.roomId] == null && composerCanSend()

    /** Paperclip/admission readiness only. selectAttachment still rechecks every original fact. */
    internal fun canBeginAttachment(): Boolean {
        val value = mutable.value
        return foreground && attachmentBudget != null && !value.busy && !value.sending && composerCanSend()
    }

    private fun composerCanSend(): Boolean {
        val value = mutable.value
        val current = session.value
        val context = value.context ?: return false
        return uploadPosting == null && attachments[value.roomId]?.view?.phase !in setOf(RoomAttachmentPhase.UNKNOWN,
            RoomAttachmentPhase.CONFIRMED, RoomAttachmentPhase.CANCELLING) && ready(current) && value.attachmentReview == null && value.pingSelection == null && value.authorDialog == null && !value.authorWriting &&
            value.reactionDialog == null && !value.reactionWriting && canEditDraft() && !value.stale && !value.uncertainSend && activePresence() && context.room.isMember &&
            (!current.user!!.guest || context.room.guestAccess == "write") && context.mute == null
    }

    /** UI reports actual layout visibility; merely fetching the history never acknowledges reads. */
    fun viewport(anchor: TimelineAnchor?, newestVisibleId: Long?, atEdge: Boolean) {
        if (!foreground || !belongsTo(session.value) || mutable.value.roomId == null) return
        viewport = anchor
        visibleNewest = newestVisibleId
        val value = mutable.value
        val edge = atEdge && value.latestWindow && value.pingSelection == null && value.authorDialog == null && value.reactionDialog == null && value.attachmentReview == null
        if (edge != value.atLatest) mutable.value = value.copy(atLatest = edge)
        val newest = value.messages.lastOrNull()?.id ?: return
        if (uploadPosting != null || !foreground || !edge || newestVisibleId != newest || value.busy || value.stale || !activePresence() || readJob?.isActive == true ||
            value.context?.room?.isMember != true || newest <= maxOf(value.readCursor, attemptedRead) || !ready(session.value)) return
        val expected = identity ?: return
        val roomId = value.roomId ?: return
        attemptedRead = newest
        val readAttempt = ++readSequence
        mutable.value = mutable.value.copy(reading = true)
        readJob = viewModelScope.launch {
            try {
                if (!isReadCurrent(expected, readAttempt, roomId)) return@launch
                val cursor = operations.read(roomId, newest)
                if (!isReadCurrent(expected, readAttempt, roomId)) return@launch
                mutable.value = mutable.value.copy(readCursor = maxOf(mutable.value.readCursor, cursor), readError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (isReadCurrent(expected, readAttempt, roomId)) {
                    if (failure.status in setOf(403, 404) && failure.code != "csrf_failed") clearUnauthorized(failure)
                    else mutable.value = mutable.value.copy(readError = failure)
                }
            } finally {
                if (isReadCurrent(expected, readAttempt, roomId)) {
                    readJob = null
                    mutable.value = mutable.value.copy(reading = false)
                    viewport(viewport, visibleNewest, mutable.value.atLatest)
                }
            }
        }
    }

    fun retryRead() {
        if (mutable.value.readError == null || mutable.value.busy) return
        attemptedRead = 0
        viewport(viewport, visibleNewest, mutable.value.atLatest)
    }

    private suspend fun reload(current: () -> Unit, forceLatest: Boolean = false, explicitReconciliation: Boolean = false) {
        val revision = requestedSync
        val roomId = checkNotNull(mutable.value.roomId)
        val snapshotContext = operations.context(roomId)
        current()
        val page = operations.history(roomId)
        current()
        val pings = operations.pings(roomId)
        current()
        val context = currentPolicy(snapshotContext)
        val value = mutable.value
        val automatic = forceLatest || !value.loaded || value.atLatest
        val anchor = viewport?.takeIf { saved -> page.messages.any { it.id == saved.id } }
        val reset = !automatic && anchor == null
        val divider = if (!dividerEstablished && context.room.unreadCount > 0) page.messages.firstOrNull {
            it.id > (context.room.lastReadMessageId ?: 0) && it.senderId != session.value.user?.id && !it.deleted
        }?.id else value.unreadDividerId
        dividerEstablished = true
        attemptedRead = 0
        rebindUnchangedAuthor(page.messages.takeLast(500))
        mutable.value = value.copy(context = context, messages = page.messages.takeLast(500), pings = pings.filterNot {
            it.sender.id in context.policy.ignoredUserIds }.sortedBy { it.id }.takeLast(100), loaded = true, stale = false, latestWindow = true,
            fixedUpper = null, highlightedMessageId = null,
            olderCursor = page.olderCursor, mayHaveOlder = page.mayHaveOlder, atLatest = false,
            scrollRequest = value.scrollRequest + if (automatic || reset) 1 else 0,
            anchor = if (!automatic && !reset) anchor?.copy(revision = sequence) else null, unreadDividerId = divider,
            readCursor = maxOf(value.readCursor, context.room.lastReadMessageId ?: 0), readError = null,
            reconciledAfterUncertain = value.uncertainSend && (value.reconciledAfterUncertain || explicitReconciliation),
            notice = if (reset) "latest_reset" else value.notice,
            syncError = null, newerAvailable = false, anchorUnavailable = false)
        appliedSync = maxOf(appliedSync, revision); attemptedSync = maxOf(attemptedSync, revision)
        selectPresence()
    }

    private fun stopSync() {
        if (syncJob?.isActive == true) {
            sequence++ // Fence a cancelled GET before a user operation can publish.
            attemptedSync = appliedSync
            syncJob?.cancel()
        }
        syncJob = null
        mutable.value = mutable.value.copy(syncing = false)
    }

    private fun drainSync() {
        val original = mutable.value
        val expected = session.value.identity() ?: return
        val roomId = original.roomId ?: return
        if (realtime == null || realtime.state.value.phase == RealtimePhase.OFFLINE || expected != identity ||
            !foreground || !original.loaded || original.busy || uploadPosting != null || original.syncing || original.attachmentReview != null ||
            requestedSync <= attemptedSync || !ready(session.value)) return
        val revision = requestedSync
        attemptedSync = revision
        val attempt = ++sequence
        mutable.value = original.copy(syncing = true)
        syncJob = viewModelScope.launch {
            try {
                withTimeout(60_000) {
                    synchronizeWindow(original, attempt) { ensureCurrent(expected, attempt, roomId) }
                }
                appliedSync = revision
            } catch (_: TimeoutCancellationException) {
                if (isCurrent(expected, attempt, roomId)) mutable.value = mutable.value.copy(
                    stale = true, syncError = ApiFailure(FailureKind.TIMEOUT))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (isCurrent(expected, attempt, roomId)) {
                    if (failure.status in setOf(403, 404) && failure.code != "csrf_failed") clearUnauthorized(failure)
                    else mutable.value = mutable.value.copy(stale = true, syncError = failure)
                }
            } finally {
                if (isCurrent(expected, attempt, roomId)) {
                    syncJob = null
                    mutable.value = mutable.value.copy(syncing = false)
                    // Failed revisions wait for a new hint/foreground/manual retry, not a tight loop.
                    drainSync()
                }
            }
        }
    }

    private suspend fun synchronizeWindow(original: ConversationUiState, attempt: Long, current: () -> Unit) {
        current()
        val roomId = checkNotNull(original.roomId)
        val snapshotContext = operations.context(roomId)
        current()
        val lower = original.olderCursor ?: original.messages.firstOrNull()?.id ?: 1L
        val upper = original.fixedUpper ?: original.messages.lastOrNull()?.id
        var latest = original.latestWindow
        if (!latest && upper == null) throw ApiFailure(FailureKind.HTTP, code = "history_range_unavailable")
        var snapshot = operations.window(roomId, lower, if (latest) null else upper)
        current()
        var attention = upper != null && snapshot.messages.any { it.id > upper }
        if (!latest && upper != null) {
            // limit=1 makes its RAW oldest cursor the newest raw ID, even if enrichment omits it.
            val probe = operations.history(roomId, limit = 1)
            current()
            attention = maxOf(probe.olderCursor ?: 0, probe.messages.lastOrNull()?.id ?: 0) > upper
        }
        val pings = operations.pings(roomId)
        current()
        // Use the CURRENT viewport: the user may have scrolled away while the GETs were running.
        if (latest && upper != null && !mutable.value.atLatest &&
            (!snapshot.coveredLowerBoundary || snapshot.messages.size > 500)) {
            attention = true
            snapshot = operations.window(roomId, lower, upper)
            current()
            latest = false
        }
        val following = latest && mutable.value.atLatest
        // The helper's captured lower cursor cannot describe an unvisited gap, even at live edge.
        if (!snapshot.coveredLowerBoundary || (snapshot.messages.size > 500 && !following))
            throw ApiFailure(FailureKind.HTTP, code = "history_range_unavailable")
        val context = currentPolicy(snapshotContext)
        val messages = snapshot.messages.takeLast(500)
        val previous = mutable.value
        val targetLost = previous.highlightedMessageId != null && messages.none { it.id == previous.highlightedMessageId }
        val savedAnchor = viewport ?: previous.anchor
        val anchorLost = !following && savedAnchor != null && messages.none { it.id == savedAnchor.id }
        val anchor = if (following || savedAnchor == null) null else {
            val survivor = messages.firstOrNull { it.id >= savedAnchor.id } ?: messages.lastOrNull()
            survivor?.let { TimelineAnchor(it.id, if (anchorLost) 0 else savedAnchor.offset, attempt) }
        }
        rebindUnchangedAuthor(messages)
        mutable.value = previous.copy(context = context, messages = messages,
            pings = pings.filterNot { it.sender.id in context.policy.ignoredUserIds }.sortedBy { it.id }.takeLast(100),
            stale = false, syncError = null, latestWindow = latest,
            fixedUpper = if (latest) null else upper,
            highlightedMessageId = previous.highlightedMessageId.takeUnless { targetLost },
            olderCursor = if (snapshot.messages.size > 500) messages.firstOrNull()?.id else snapshot.olderCursor,
            mayHaveOlder = snapshot.mayHaveOlder || snapshot.messages.size > 500,
            scrollRequest = previous.scrollRequest + if (following) 1 else 0, anchor = anchor,
            newerAvailable = attention && !following, anchorUnavailable = previous.anchorUnavailable || anchorLost || targetLost,
            readCursor = maxOf(previous.readCursor, context.room.lastReadMessageId ?: 0))
        selectPresence()
    }

    private fun request(sending: Boolean = false, preserveAttachmentReview: Boolean = false, action: suspend (() -> Unit) -> Unit) {
        val roomId = mutable.value.roomId ?: return
        val expected = session.value.identity() ?: return
        if (mutable.value.busy || uploadPosting != null || expected != identity) return
        if (!preserveAttachmentReview) mutable.value = mutable.value.copy(attachmentReview = null)
        stopSync()
        val attempt = ++sequence
        identity = expected
        mutable.value = mutable.value.copy(busy = true, sending = sending, error = null, validation = null, notice = null)
        job = viewModelScope.launch {
            try {
                val currentSession = withTimeoutOrNull(30_000) { session.first { !it.busy || it.identity() != expected } }
                    ?: throw ApiFailure(FailureKind.TIMEOUT)
                ensureCurrent(expected, attempt, roomId)
                if (!ready(currentSession)) throw ApiFailure(FailureKind.HTTP,
                    if (currentSession.retryAtMillis > System.currentTimeMillis()) 429 else null, "csrf_failed")
                action { ensureCurrent(expected, attempt, roomId) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (isCurrent(expected, attempt, roomId)) {
                    if (failure.status in setOf(403, 404) && failure.code != "csrf_failed" &&
                        (!postInFlight || failure.code in setOf("room_forbidden", "room_not_found")))
                        // A definitive rejection of this request. A send refused with room_not_found says so
                        // in its own words (ChitChat #326); the draft stays as it is.
                        clearUnauthorized(if (sending && failure.code == "room_not_found")
                            ApiFailure(failure.kind, failure.status, ROOM_UNAVAILABLE_ON_SEND) else failure, unknownWrite = false)
                    else mutable.value = mutable.value.copy(error = failure, stale = true,
                        uncertainSend = mutable.value.uncertainSend || (postInFlight && failure.ambiguous()),
                        reconciledAfterUncertain = false)
                }
            } finally {
                if (isCurrent(expected, attempt, roomId)) {
                    val review = mutable.value.attachmentReview
                    if (preserveAttachmentReview && review?.phase == RoomAttachmentReviewPhase.CHECKING)
                        mutable.value = mutable.value.copy(attachmentReview = mutable.value.error?.let {
                            RoomAttachmentReview(review.request, RoomAttachmentReviewPhase.FAILED, error = it)
                        })
                    postInFlight = false; mutable.value = mutable.value.copy(busy = false, sending = false)
                    rememberDraft()
                    drainSync()
                }
            }
        }
    }

    private fun clearUnauthorized(error: ApiFailure, unknownWrite: Boolean = postInFlight) {
        val uncertain = mutable.value.uncertainSend || unknownWrite
        retire()
        realtime?.selectRoom(null, false)
        mutable.value = mutable.value.copy(context = null, messages = emptyList(), pings = emptyList(),
            loaded = false, stale = true, busy = false, sending = false, error = error, revealed = emptySet(),
            unreadDividerId = null, olderCursor = null, mayHaveOlder = false, anchor = null, readCursor = 0,
            fixedUpper = null, highlightedMessageId = null, pingSelection = null,
            uncertainSend = uncertain, reconciledAfterUncertain = false)
        dividerEstablished = false
        rememberDraft()
    }
    /** A delayed history GET must not restore policy captured before an acknowledged local change. */
    private fun currentPolicy(context: ConversationContext) = session.value.chatPolicy
        ?.let { ConversationContext(context.room, it) } ?: context

    private fun retire(keepUpload: Boolean = false) {
        if (!keepUpload) retireUpload()
        authorPosting?.let(::rememberUnknownAuthor)
        reactionPosting?.let(::rememberUnknownReaction)
        authorPosting = null; reactionPosting = null; mutationJob?.cancel(); mutationJob = null
        sequence++; readSequence++; job?.cancel(); readJob?.cancel(); readJob = null; syncJob?.cancel(); syncJob = null
        postInFlight = false; viewport = null; visibleNewest = null; attemptedRead = 0
        requestedSync = 0; attemptedSync = 0; appliedSync = 0; deliberateSelection = false
        mutable.value = mutable.value.copy(syncing = false, reading = false, authorDialog = null, authorWriting = false,
            reactionDialog = null, reactionWriting = false, attachmentReview = null)
    }
    private fun isReadCurrent(expected: Identity, attempt: Long, roomId: Long) = readSequence == attempt &&
        session.value.identity() == expected && mutable.value.roomId == roomId
    private fun ready(value: SessionState) = value.identity() != null && value.identity() == identity && !value.busy && !value.refreshRequired &&
        !value.storageWarning && value.retryAtMillis <= System.currentTimeMillis()
    private fun ensureCurrent(expected: Identity, attempt: Long, roomId: Long) {
        if (!isCurrent(expected, attempt, roomId)) throw CancellationException("Conversation changed")
    }
    private fun isCurrent(expected: Identity, attempt: Long, roomId: Long) = sequence == attempt &&
        session.value.identity() == expected && mutable.value.roomId == roomId
    private data class Identity(val server: String?, val generation: Long, val user: Long, val version: Int)
    private fun SessionState.identity() = user?.takeIf { phase in setOf(SessionPhase.AUTHENTICATED, SessionPhase.GUEST) }
        ?.let { Identity(server?.value, generation, it.id, it.sessionVersion) }
    private fun ApiFailure.ambiguous() = kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.TLS,
        FailureKind.REDIRECT, FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) || (status ?: 0) >= 500
    companion object {
        internal fun factory(operations: ConversationOperations, realtime: RealtimeController? = null,
            typing: TypingController? = null, mutations: MessageMutationOperations? = null,
            reactions: MessageReactionOperations? = null,
            attachmentBudget: AttachmentMemoryBudget? = null,
            uploads: AttachmentUploadOperations? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationViewModel(operations, realtime, typing, mutations, reactions, attachmentBudget, uploads) }
        }
    }
}

/** Local marker for a room send refused with 404 `room_not_found`; never a server code. */
internal const val ROOM_UNAVAILABLE_ON_SEND = "room_not_found_on_send"
