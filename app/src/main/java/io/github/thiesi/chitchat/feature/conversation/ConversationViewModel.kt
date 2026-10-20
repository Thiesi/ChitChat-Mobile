package io.github.thiesi.chitchat.feature.conversation

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
data class ConversationUiState(
    val roomId: Long? = null, val context: ConversationContext? = null,
    val messages: List<RoomMessage> = emptyList(), val pings: List<RoomPing> = emptyList(),
    val draft: String = "", val loaded: Boolean = false, val busy: Boolean = false,
    val sending: Boolean = false, val stale: Boolean = true, val latestWindow: Boolean = true,
    val olderCursor: Long? = null, val mayHaveOlder: Boolean = false,
    val atLatest: Boolean = false, val scrollRequest: Long = 0, val anchor: TimelineAnchor? = null,
    val unreadDividerId: Long? = null, val readCursor: Long = 0,
    val error: ApiFailure? = null, val readError: ApiFailure? = null, val validation: String? = null,
    val uncertainSend: Boolean = false, val reconciledAfterUncertain: Boolean = false,
    val revealed: Set<Long> = emptySet(), val notice: String? = null,
    val syncing: Boolean = false, val syncError: ApiFailure? = null,
    val newerAvailable: Boolean = false, val anchorUnavailable: Boolean = false,
) { override fun toString() = "ConversationUiState" }

class ConversationViewModel(private val operations: ConversationOperations,
    val realtime: RealtimeController? = null,
    val typing: TypingController? = null) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(ConversationUiState())
    val state = mutable.asStateFlow()
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
        } else drainSync()
    } } }

    fun open(roomId: Long, interacted: Boolean = false) {
        if (roomId <= 0 || session.value.identity() == null) return
        if (mutable.value.roomId != roomId) {
            retire(); dividerEstablished = false
            realtime?.selectRoom(null, false)
            mutable.value = ConversationUiState(roomId = roomId)
        }
        if (interacted) deliberateSelection = true
        if (!mutable.value.loaded && !mutable.value.busy) refresh()
        else if (interacted) invalidate()
    }

    fun foreground() {
        foreground = true
        if (mutable.value.loaded && realtime != null) invalidate()
        else if (mutable.value.roomId != null && !mutable.value.busy) refresh()
    }
    fun background() {
        foreground = false
        val anchor = viewport
        cancelRequest()
        mutable.value = mutable.value.copy(revealed = emptySet(), atLatest = false, anchor = anchor)
    }
    fun cancel() { background(); realtime?.selectRoom(null, false) }

    fun cancelRequest() {
        val uncertain = mutable.value.uncertainSend || postInFlight
        retire()
        mutable.value = mutable.value.copy(busy = false, sending = false, stale = true,
            uncertainSend = uncertain, reconciledAfterUncertain = false, notice = "cancelled")
    }

    fun updateDraft(value: String) { if (!mutable.value.sending) mutable.value = mutable.value.copy(draft = value, validation = null) }
    fun reveal(id: Long) { mutable.value = mutable.value.copy(revealed = mutable.value.revealed + id) }
    fun refresh() { request { current -> reload(current) } }
    fun latest() { request { current -> reload(current, forceLatest = true) } }

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
            mutable.value = mutable.value.copy(messages = trimmed, olderCursor = page.olderCursor,
                mayHaveOlder = page.mayHaveOlder, latestWindow = mutable.value.latestWindow && merged.size <= 500,
                anchor = anchor?.copy(revision = sequence), notice = if (merged.size > 500) "window" else null)
        }
    }

    fun acknowledgeDuplicateRisk() {
        if (mutable.value.uncertainSend && mutable.value.reconciledAfterUncertain && !mutable.value.busy)
            mutable.value = mutable.value.copy(uncertainSend = false, reconciledAfterUncertain = false, notice = "duplicate_risk")
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
            val result = operations.send(checkNotNull(value.roomId), body)
            current(); postInFlight = false
            typing?.sent(typingSession, TypingTarget.Room(checkNotNull(value.roomId)))
            realtime?.activity(checkNotNull(value.roomId)) // Only an acknowledged, current send/ping.
            mutable.value = mutable.value.copy(draft = "", notice = if (result is SendResult.Ping) "ping_sent" else "sent")
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
                    .distinctBy { it.id }.filterNot { it.sender.id in value.context!!.policy.ignoredUserIds }.sortedBy { it.id }.takeLast(100))
            }
        }
    }

    fun canSend(): Boolean {
        val value = mutable.value
        val current = session.value
        val context = value.context ?: return false
        return ready(current) && !value.stale && !value.uncertainSend && activePresence() && context.room.isMember &&
            (!current.user!!.guest || context.room.guestAccess == "write") && context.mute == null
    }

    /** UI reports actual layout visibility; merely fetching the history never acknowledges reads. */
    fun viewport(anchor: TimelineAnchor?, newestVisibleId: Long?, atEdge: Boolean) {
        viewport = anchor
        visibleNewest = newestVisibleId
        val value = mutable.value
        val edge = atEdge && value.latestWindow
        if (edge != value.atLatest) mutable.value = value.copy(atLatest = edge)
        val newest = value.messages.lastOrNull()?.id ?: return
        if (!foreground || !edge || newestVisibleId != newest || value.busy || value.stale || !activePresence() || readJob?.isActive == true ||
            value.context?.room?.isMember != true || newest <= maxOf(value.readCursor, attemptedRead) || !ready(session.value)) return
        val expected = identity ?: return
        val roomId = value.roomId ?: return
        attemptedRead = newest
        val readAttempt = ++readSequence
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

    private suspend fun reload(current: () -> Unit, forceLatest: Boolean = false) {
        val revision = requestedSync
        val roomId = checkNotNull(mutable.value.roomId)
        val context = operations.context(roomId)
        current()
        val page = operations.history(roomId)
        current()
        val pings = operations.pings(roomId)
        current()
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
            olderCursor = page.olderCursor, mayHaveOlder = page.mayHaveOlder, atLatest = false,
            scrollRequest = value.scrollRequest + if (automatic || reset) 1 else 0,
            anchor = if (!automatic && !reset) anchor?.copy(revision = sequence) else null, unreadDividerId = divider,
            readCursor = maxOf(value.readCursor, context.room.lastReadMessageId ?: 0), readError = null,
            reconciledAfterUncertain = value.uncertainSend, notice = if (reset) "latest_reset" else value.notice,
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
        val context = operations.context(roomId)
        current()
        val lower = original.olderCursor ?: original.messages.firstOrNull()?.id ?: 1L
        val upper = original.messages.lastOrNull()?.id
        var latest = original.latestWindow
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
        val messages = snapshot.messages.takeLast(500)
        val previous = mutable.value
        val savedAnchor = viewport ?: previous.anchor
        val anchorLost = !following && savedAnchor != null && messages.none { it.id == savedAnchor.id }
        val anchor = if (following || savedAnchor == null) null else {
            val survivor = messages.firstOrNull { it.id >= savedAnchor.id } ?: messages.lastOrNull()
            survivor?.let { TimelineAnchor(it.id, if (anchorLost) 0 else savedAnchor.offset, attempt) }
        }
        mutable.value = previous.copy(context = context, messages = messages,
            pings = pings.filterNot { it.sender.id in context.policy.ignoredUserIds }.sortedBy { it.id }.takeLast(100),
            stale = false, syncError = null, latestWindow = latest,
            olderCursor = if (snapshot.messages.size > 500) messages.firstOrNull()?.id else snapshot.olderCursor,
            mayHaveOlder = snapshot.mayHaveOlder || snapshot.messages.size > 500,
            scrollRequest = previous.scrollRequest + if (following) 1 else 0, anchor = anchor,
            newerAvailable = attention && !following, anchorUnavailable = previous.anchorUnavailable || anchorLost,
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
            uncertainSend = uncertain, reconciledAfterUncertain = false)
        dividerEstablished = false
    }
    private fun retire() {
        sequence++; readSequence++; job?.cancel(); readJob?.cancel(); readJob = null; syncJob?.cancel(); syncJob = null
        postInFlight = false; viewport = null; visibleNewest = null; attemptedRead = 0
        requestedSync = 0; attemptedSync = 0; appliedSync = 0; deliberateSelection = false
        mutable.value = mutable.value.copy(syncing = false)
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
