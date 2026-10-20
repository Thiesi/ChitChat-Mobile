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
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
) { override fun toString() = "ConversationUiState" }

class ConversationViewModel(private val operations: ConversationOperations) : ViewModel() {
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

    init { viewModelScope.launch { session.collect { current ->
        if (current.identity() != identity) {
            retire(); identity = current.identity(); dividerEstablished = false
            mutable.value = ConversationUiState()
        }
    } } }

    fun open(roomId: Long) {
        if (roomId <= 0 || session.value.identity() == null) return
        if (mutable.value.roomId != roomId) {
            retire(); dividerEstablished = false
            mutable.value = ConversationUiState(roomId = roomId)
        }
        if (!mutable.value.loaded && !mutable.value.busy) refresh()
    }

    fun foreground() { foreground = true; if (mutable.value.roomId != null && !mutable.value.busy) refresh() }
    fun background() {
        foreground = false
        cancelRequest()
        mutable.value = mutable.value.copy(revealed = emptySet(), atLatest = false)
    }
    fun cancel() = background()

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
        request(sending = true) { current ->
            postInFlight = true
            val result = operations.send(checkNotNull(value.roomId), body)
            current(); postInFlight = false
            mutable.value = mutable.value.copy(draft = "", notice = if (result is SendResult.Ping) "ping_sent" else "sent")
            when (result) {
                is SendResult.Message -> if (value.latestWindow) {
                    val messages = (mutable.value.messages + result.message).distinctBy { it.id }.sortedBy { it.id }.takeLast(500)
                    mutable.value = mutable.value.copy(messages = messages, scrollRequest = mutable.value.scrollRequest + 1,
                        olderCursor = messages.firstOrNull()?.id, mayHaveOlder = value.mayHaveOlder || messages.size == 500)
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
        return ready(current) && !value.stale && !value.uncertainSend && context.room.isMember &&
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
        if (!foreground || !edge || newestVisibleId != newest || value.busy || value.stale ||
            value.context?.room?.isMember != true || newest <= maxOf(value.readCursor, attemptedRead) || !ready(session.value)) return
        val expected = identity ?: return
        val attempt = sequence
        val roomId = value.roomId ?: return
        attemptedRead = newest
        readJob?.cancel()
        val readAttempt = ++readSequence
        readJob = viewModelScope.launch {
            try {
                val cursor = operations.read(roomId, newest)
                ensureCurrent(expected, attempt, roomId)
                if (readAttempt != readSequence) return@launch
                mutable.value = mutable.value.copy(readCursor = maxOf(mutable.value.readCursor, cursor), readError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) {
                if (isCurrent(expected, attempt, roomId) && readAttempt == readSequence) {
                    if (failure.status in setOf(403, 404) && failure.code != "csrf_failed") clearUnauthorized(failure)
                    else mutable.value = mutable.value.copy(readError = failure)
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
            reconciledAfterUncertain = value.uncertainSend, notice = if (reset) "latest_reset" else value.notice)
    }

    private fun request(sending: Boolean = false, action: suspend (() -> Unit) -> Unit) {
        val roomId = mutable.value.roomId ?: return
        val expected = session.value.identity() ?: return
        if (mutable.value.busy) return
        val attempt = ++sequence
        identity = expected
        readJob?.cancel()
        readSequence++
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
                    if (failure.status in setOf(403, 404) && (!postInFlight || failure.code in setOf("room_forbidden", "room_not_found"))) clearUnauthorized(failure)
                    else mutable.value = mutable.value.copy(error = failure, stale = true,
                        uncertainSend = mutable.value.uncertainSend || (postInFlight && failure.ambiguous()),
                        reconciledAfterUncertain = false)
                }
            } finally {
                if (isCurrent(expected, attempt, roomId)) {
                    postInFlight = false; mutable.value = mutable.value.copy(busy = false, sending = false)
                }
            }
        }
    }

    private fun clearUnauthorized(error: ApiFailure) {
        mutable.value = mutable.value.copy(context = null, messages = emptyList(), pings = emptyList(),
            loaded = false, stale = true, error = error, revealed = emptySet(), unreadDividerId = null)
        dividerEstablished = false
    }
    private fun retire() { sequence++; readSequence++; job?.cancel(); readJob?.cancel(); postInFlight = false; viewport = null; visibleNewest = null; attemptedRead = 0 }
    private fun ready(value: SessionState) = value.identity() != null && !value.busy && !value.refreshRequired &&
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
        fun factory(operations: ConversationOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConversationViewModel(operations) }
        }
    }
}
