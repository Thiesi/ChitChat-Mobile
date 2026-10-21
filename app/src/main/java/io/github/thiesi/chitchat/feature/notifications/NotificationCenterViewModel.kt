package io.github.thiesi.chitchat.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.notifications.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReadAllChoice internal constructor(val original: SessionState)
data class NotificationCenterState(val notifications: List<AccountNotification> = emptyList(),
    val loaded: Boolean = false, val busy: Boolean = false, val writing: Boolean = false, val stale: Boolean = true,
    val unreadCount: Int? = null, val confirmedReadIds: Set<Long> = emptySet(),
    val olderCursor: Long? = null, val mayHaveOlder: Boolean = false,
    val latestWindow: Boolean = true, val fixedUpper: Long? = null, val incompleteRange: Boolean = false,
    val unknownRead: Boolean = false, val allChoice: ReadAllChoice? = null, val scrollRequest: Long = 0,
    val error: ApiFailure? = null) {
    fun isRead(item: AccountNotification) = item.read || item.id in confirmedReadIds
    override fun toString() = "NotificationCenterState"
}

/** Account-scoped memory state only. Native center/badge/link consumers are a separate slice. */
class NotificationCenterViewModel(private val operations: NotificationOperations,
    private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    val session = operations.session
    private val mutable = MutableStateFlow(NotificationCenterState())
    val state = mutable.asStateFlow()
    private var owner = session.value.noticeIdentity()
    private var active = false
    private var cleared = false
    private var sequence = 0L
    private var requested = 0L
    private var attempted = 0L
    private var job: Job? = null
    private var postInFlight = false

    init { viewModelScope.launch { session.collect { value ->
        if (value.noticeIdentity() != owner) {
            retire(); owner = value.noticeIdentity(); mutable.value = NotificationCenterState()
            if (owner != null) requested++
        }
        drain()
    } } }

    fun belongsTo(expected: SessionState) = !cleared && expected.noticeIdentity() != null && expected.noticeIdentity() == owner &&
        owner == session.value.noticeIdentity()
    private fun ready(expected: SessionState) = belongsTo(expected) && !session.value.busy &&
        !session.value.refreshRequired && !session.value.storageWarning && now() >= session.value.retryAtMillis
    private fun current(token: Long, expected: SessionState) = token == sequence && active && belongsTo(expected)

    fun foreground() { if (!cleared) { active = true; open(session.value) } }
    fun open(expected: SessionState) {
        if (cleared) return
        val identity = expected.noticeIdentity() ?: return
        if (identity != session.value.noticeIdentity()) return
        if (identity != owner) { retire(); owner = identity; mutable.value = NotificationCenterState() }
        requested++; drain()
    }
    fun background() {
        active = false
        val unknown = mutable.value.unknownRead || postInFlight
        retire()
        mutable.value = mutable.value.copy(busy = false, writing = false, stale = true, unknownRead = unknown, allChoice = null)
    }
    fun invalidate() { if (belongsTo(session.value)) { requested++; drain() } }
    fun connectionLost() { mutable.value = mutable.value.copy(stale = true) }

    /** Explicit current-state check, not an automatic repeat of a previous ids/All POST. */
    fun refresh(expected: SessionState) {
        if (!active || !ready(expected) || mutable.value.busy) return
        requested++
        drain(reconcileUnknown = true)
    }
    fun latest(expected: SessionState) {
        if (!active || !ready(expected) || mutable.value.busy) return
        requested++
        drain(reconcileUnknown = true, latest = true)
    }
    private fun drain(reconcileUnknown: Boolean = false, latest: Boolean = false) {
        val expected = session.value
        if (!active || !ready(expected) || mutable.value.busy || requested <= attempted) return
        attempted = requested
        launch(expected) { token -> reconcile(token, expected, reconcileUnknown, latest) }
    }
    private fun launch(expected: SessionState, operation: suspend (Long) -> Unit) {
        val token = ++sequence
        mutable.value = mutable.value.copy(busy = true, error = null)
        val pending = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { if (current(token, expected)) operation(token) }
            catch (_: CancellationException) { if (current(token, expected)) mutable.value = mutable.value.copy(stale = true) }
            catch (failure: ApiFailure) { if (current(token, expected)) failed(failure) }
            finally { if (current(token, expected)) {
                job = null; postInFlight = false
                mutable.value = mutable.value.copy(busy = false, writing = false)
                drain()
            } }
        }
        job = pending; pending.start()
    }

    private suspend fun reconcile(token: Long, expected: SessionState, explicit: Boolean, latest: Boolean = false) {
        val old = mutable.value
        if (!old.loaded || latest) {
            val page = operations.page(expected)
            if (!current(token, expected)) return
            mutable.value = mutable.value.copy(notifications = page.notifications, loaded = true, stale = false,
                unreadCount = page.unreadCount, confirmedReadIds = emptySet(), olderCursor = page.olderCursor,
                mayHaveOlder = page.mayHaveOlder, latestWindow = true, fixedUpper = null, incompleteRange = false,
                unknownRead = old.unknownRead && !explicit, error = null,
                scrollRequest = old.scrollRequest + if (latest) 1 else 0)
            return
        }
        val lower = old.olderCursor ?: old.notifications.lastOrNull()?.id ?: 1
        val upper = if (old.latestWindow) null else (old.fixedUpper ?: throw ApiFailure(FailureKind.INCOMPATIBLE))
        val window = readCanonicalNotificationWindow(lower, upper) { before, limit ->
            if (!current(token, expected)) throw CancellationException("Notification selection retired")
            operations.page(expected, before, limit)
        }
        if (!current(token, expected)) return
        if (!window.coveredLowerBoundary) {
            mutable.value = mutable.value.copy(stale = true, incompleteRange = true, unreadCount = window.unreadCount)
            return
        }
        val rows = window.notifications.take(500)
        val trimmed = window.notifications.size > 500
        mutable.value = mutable.value.copy(notifications = rows, stale = false, unreadCount = window.unreadCount,
            confirmedReadIds = emptySet(), olderCursor = if (trimmed) rows.last().id else window.olderCursor,
            mayHaveOlder = trimmed || window.mayHaveOlder, incompleteRange = false,
            unknownRead = old.unknownRead && !explicit, error = null)
    }

    fun older(expected: SessionState) {
        val old = mutable.value; val cursor = old.olderCursor ?: return
        if (!canBrowse(expected) || !old.mayHaveOlder) return
        launch(expected) { token ->
            val page = operations.page(expected, cursor)
            if (!current(token, expected)) return@launch
            val merged = (mutable.value.notifications + page.notifications).distinctBy { it.id }.sortedByDescending { it.id }
            val rows = merged.takeLast(500)
            val live = old.latestWindow && merged.size <= 500
            mutable.value = mutable.value.copy(notifications = rows, unreadCount = page.unreadCount,
                olderCursor = page.olderCursor, mayHaveOlder = page.mayHaveOlder, latestWindow = live,
                fixedUpper = if (live) null else if (merged.size > 500) rows.firstOrNull()?.id else old.fixedUpper,
                confirmedReadIds = mutable.value.confirmedReadIds.intersect(rows.map { it.id }.toSet()))
        }
    }
    private fun canBrowse(expected: SessionState) = active && ready(expected) && mutable.value.let {
        it.loaded && !it.busy && !it.stale
    }
    private fun eligible(expected: SessionState) = canBrowse(expected) && !mutable.value.unknownRead
    fun markRead(expected: SessionState, id: Long) {
        val old = mutable.value
        if (!eligible(expected) || old.notifications.none { it.id == id && !old.isRead(it) }) return
        write(expected, listOf(id), all = false)
    }
    fun requestAll(expected: SessionState) {
        if (eligible(expected) && (mutable.value.unreadCount ?: 0) > 0)
            mutable.value = mutable.value.copy(allChoice = ReadAllChoice(expected))
    }
    fun cancelAll(choice: ReadAllChoice) {
        if (mutable.value.allChoice === choice) mutable.value = mutable.value.copy(allChoice = null)
    }
    fun confirmAll(choice: ReadAllChoice) {
        if (mutable.value.allChoice !== choice || !eligible(choice.original)) return
        mutable.value = mutable.value.copy(allChoice = null)
        write(choice.original, emptyList(), all = true)
    }
    private fun write(expected: SessionState, ids: List<Long>, all: Boolean) {
        launch(expected) { token ->
            mutable.value = mutable.value.copy(writing = true)
            postInFlight = true
            var confirmed = false
            try {
                val reply = if (all) operations.markAllRead(expected) else operations.markRead(expected, ids)
                if (!current(token, expected)) return@launch
                postInFlight = false; confirmed = true
                val value = mutable.value
                // No fabricated server read_at timestamp: pending canonical GET keeps a local overlay.
                val affected = if (all) value.notifications.map { it.id }.toSet() else ids.toSet()
                mutable.value = value.copy(writing = false, unreadCount = reply.unreadCount, stale = true,
                    confirmedReadIds = value.confirmedReadIds + affected)
                reconcile(token, expected, explicit = false)
            } catch (cancelled: CancellationException) {
                if (current(token, expected) && !confirmed) mutable.value = mutable.value.copy(unknownRead = true, stale = true)
                throw cancelled
            } catch (failure: ApiFailure) {
                if (current(token, expected) && !confirmed) mutable.value = mutable.value.copy(unknownRead = true)
                throw failure
            }
        }
    }
    private fun failed(failure: ApiFailure) {
        val revoked = failure.kind == FailureKind.INCOMPATIBLE || failure.status in setOf(401, 403, 404) && failure.code != "csrf_failed"
        mutable.value = mutable.value.copy(stale = true, error = failure,
            notifications = if (revoked) emptyList() else mutable.value.notifications,
            loaded = mutable.value.loaded && !revoked, unreadCount = mutable.value.unreadCount.takeUnless { revoked },
            confirmedReadIds = if (revoked) emptySet() else mutable.value.confirmedReadIds,
            allChoice = mutable.value.allChoice.takeUnless { revoked })
    }
    private fun retire() {
        sequence++; job?.cancel(); job = null; postInFlight = false; requested = 0; attempted = 0
    }
    override fun onCleared() {
        cleared = true; active = false; retire(); owner = null
        mutable.value = NotificationCenterState()
        super.onCleared()
    }
    companion object {
        fun factory(operations: NotificationOperations): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotificationCenterViewModel(operations) }
        }
    }
}

private data class NoticeIdentity(val server: String, val generation: Long, val user: Long, val version: Int)
private fun SessionState.noticeIdentity(): NoticeIdentity? {
    val address = server?.value ?: return null
    return user?.takeIf { phase == SessionPhase.AUTHENTICATED && !it.guest }
        ?.let { NoticeIdentity(address, generation, it.id, it.sessionVersion) } // Guest UI/lifecycle remains M7.
}
