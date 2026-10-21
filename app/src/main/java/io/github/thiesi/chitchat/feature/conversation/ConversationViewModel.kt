package io.github.thiesi.chitchat.feature.conversation

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
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
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
) { override fun toString() = "ConversationUiState" }

class ConversationViewModel(private val operations: ConversationOperations,
    val realtime: RealtimeController? = null,
    val typing: TypingController? = null) : ViewModel() {
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

    init { viewModelScope.launch { session.collect { current ->
        if (current.identity() != identity) {
            retire(); identity = current.identity(); dividerEstablished = false
            mutable.value = ConversationUiState()
            savedDrafts.value = emptyMap()
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
            retire(); identity = owner; savedDrafts.value = emptyMap(); mutable.value = ConversationUiState()
        }
        if (mutable.value.roomId != roomId) {
            val wasForeground = foreground
            background(); foreground = wasForeground; dividerEstablished = false
            realtime?.selectRoom(null, false)
            val memo = savedDrafts.value[roomId]?.takeIf { it.matches(expected) }
            mutable.value = ConversationUiState(roomId = roomId, draft = memo?.body.orEmpty(),
                uncertainSend = memo?.uncertain == true, composerReply = memo?.composerReply)
        }
        if (interacted) deliberateSelection = true
        if (!mutable.value.loaded && !mutable.value.busy) loadEntry()
        else if (interacted) invalidate()
    }

    fun foreground() {
        foreground = true
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
            expected.identity() != identity || expected.identity() != session.value.identity() || mutable.value.sending) return false
        if (!canEditDraft() && value.isNotEmpty()) { mutable.value = mutable.value.copy(draftLimit = true); return false }
        mutable.value = mutable.value.copy(draft = value, validation = null, draftLimit = false)
        rememberDraft(); return true
    }
    fun canUseMessage(expected: SessionState, roomId: Long?, message: RoomMessage): Boolean {
        val value = mutable.value
        val context = value.context ?: return false
        return foreground && belongsTo(expected) && ready(session.value) && roomId != null && value.roomId == roomId &&
            value.pingSelection == null && message.roomId == roomId && message.id > 0 &&
            value.messages.any { it === message } && !message.deleted &&
            (message.senderId !in currentPolicy(context).policy.ignoredUserIds || message.id in value.revealed)
    }
    fun canReplyTo(expected: SessionState, roomId: Long?, message: RoomMessage) =
        canUseMessage(expected, roomId, message) && canSend() && !mutable.value.busy && !mutable.value.sending
    fun replyTo(expected: SessionState, roomId: Long?, message: RoomMessage): Boolean {
        if (!canReplyTo(expected, roomId, message)) return false
        mutable.value = mutable.value.copy(composerReply = ComposerReply(message.id), validation = null,
            error = mutable.value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft(); return true
    }
    fun cancelReply(expected: SessionState, roomId: Long?, original: ComposerReply) {
        val value = mutable.value
        if (!foreground || !belongsTo(expected) || roomId == null || roomId != value.roomId ||
            value.sending || value.composerReply !== original) return
        mutable.value = value.copy(composerReply = null, draftLimit = false,
            error = value.error?.takeUnless { it.code == "invalid_reply_target" })
        rememberDraft()
    }
    fun showDrafts(expected: SessionState = session.value) {
        if (expected.identity() != null && expected.identity() == identity && expected.identity() == session.value.identity())
            mutable.value = mutable.value.copy(draftsOpen = true)
    }
    fun hideDrafts(expected: SessionState = session.value) {
        if (expected.identity() == identity && expected.identity() == session.value.identity())
            mutable.value = mutable.value.copy(draftsOpen = false)
    }
    fun discardDraft(draft: RoomDraft) {
        if (!draft.matches(session.value) || mutable.value.sending || savedDrafts.value[draft.roomId] !== draft) return
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
        if (previous != null && previous.matches(session.value) && previous.body == value.draft &&
            previous.uncertain == value.uncertainSend && previous.roomName == name && previous.composerReply === value.composerReply) return
        savedDrafts.value = if (value.draft.isEmpty() && !value.uncertainSend && value.composerReply == null) savedDrafts.value - roomId else
            savedDrafts.value + (roomId to RoomDraft(owner, roomId, name, value.draft, value.uncertainSend, value.composerReply))
    }
    fun reveal(id: Long) { mutable.value = mutable.value.copy(revealed = mutable.value.revealed + id) }
    private fun loadEntry() { request { current -> refreshWindow(current) } }
    fun refresh() { request { current -> refreshWindow(current, explicit = true) } }
    fun latest() { request { current -> reload(current, forceLatest = true, explicitReconciliation = true) } }

    private suspend fun refreshWindow(current: () -> Unit, explicit: Boolean = false) {
        val value = mutable.value
        if (value.loaded && !value.latestWindow && !(explicit && value.uncertainSend))
            synchronizeWindow(value, sequence, current)
        else reload(current, explicitReconciliation = explicit)
    }

    /** Reply navigation adopts in place. Notification entry preparation below needs no temporary latest window. */
    fun canAdoptTarget(snapshot: TargetSnapshot.RoomMessage): Boolean = foreground && ready(session.value) &&
        snapshot.matches(session.value) && snapshot.available && snapshot.context.room.id == mutable.value.roomId &&
        !mutable.value.busy && !postInFlight && readJob?.isActive != true

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
        snapshot.matches(session.value) && snapshot.available && !postInFlight && readJob?.isActive != true

    fun prepareTarget(snapshot: TargetSnapshot.RoomMessage): Boolean {
        val fresh = snapshot.withCurrentPolicy(session.value) as? TargetSnapshot.RoomMessage ?: return false
        if (!canPrepareTarget(fresh)) return false
        prepareRoom(fresh.context.room.id, fresh.request.original)
        return installTarget(fresh).also { mutable.value = mutable.value.copy(notice = null) }
    }

    fun canPrepareTarget(snapshot: TargetSnapshot.RoomPing): Boolean = ready(session.value) &&
        snapshot.matches(session.value) && snapshot.available && !postInFlight && readJob?.isActive != true

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
                uncertainSend = memo?.uncertain == true, composerReply = memo?.composerReply)
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

    fun canSend(): Boolean {
        val value = mutable.value
        val current = session.value
        val context = value.context ?: return false
        return ready(current) && value.pingSelection == null && canEditDraft() && !value.stale && !value.uncertainSend && activePresence() && context.room.isMember &&
            (!current.user!!.guest || context.room.guestAccess == "write") && context.mute == null
    }

    /** UI reports actual layout visibility; merely fetching the history never acknowledges reads. */
    fun viewport(anchor: TimelineAnchor?, newestVisibleId: Long?, atEdge: Boolean) {
        viewport = anchor
        visibleNewest = newestVisibleId
        val value = mutable.value
        val edge = atEdge && value.latestWindow && value.pingSelection == null
        if (edge != value.atLatest) mutable.value = value.copy(atLatest = edge)
        val newest = value.messages.lastOrNull()?.id ?: return
        if (!foreground || !edge || newestVisibleId != newest || value.busy || value.stale || !activePresence() || readJob?.isActive == true ||
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
            !foreground || !original.loaded || original.busy || original.syncing ||
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

    private fun request(sending: Boolean = false, action: suspend (() -> Unit) -> Unit) {
        val roomId = mutable.value.roomId ?: return
        val expected = session.value.identity() ?: return
        if (mutable.value.busy || expected != identity) return
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
                        clearUnauthorized(failure, unknownWrite = false) // A definitive rejection of this request.
                    else mutable.value = mutable.value.copy(error = failure, stale = true,
                        uncertainSend = mutable.value.uncertainSend || (postInFlight && failure.ambiguous()),
                        reconciledAfterUncertain = false)
                }
            } finally {
                if (isCurrent(expected, attempt, roomId)) {
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

    private fun retire() {
        sequence++; readSequence++; job?.cancel(); readJob?.cancel(); readJob = null; syncJob?.cancel(); syncJob = null
        postInFlight = false; viewport = null; visibleNewest = null; attemptedRead = 0
        requestedSync = 0; attemptedSync = 0; appliedSync = 0; deliberateSelection = false
        mutable.value = mutable.value.copy(syncing = false, reading = false)
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
        fun factory(operations: ConversationOperations, realtime: RealtimeController? = null,
            typing: TypingController? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationViewModel(operations, realtime, typing) }
        }
    }
}
