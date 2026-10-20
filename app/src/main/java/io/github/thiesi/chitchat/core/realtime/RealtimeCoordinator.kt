package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.time.Clock
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive

/** Process-owned, memory-only coordinator. All state transitions run through a bounded mailbox. */
class RealtimeCoordinator(
    private val operations: RealtimeOperations,
    private val sessions: SessionController,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val backoff: (Int) -> Long = { attempt ->
        val ceiling = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000)
        Random.nextLong(ceiling / 2, ceiling + 1)
    },
) : RealtimeController {
    private val mutable = MutableStateFlow(RealtimeState())
    override val state = mutable.asStateFlow()
    private val delivered = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    override val events = delivered.asSharedFlow()
    private val mailbox = Channel<() -> Unit>(64)
    private val writes = Channel<Write>(Channel.CONFLATED)
    private var identity: Identity? = null
    private var foreground = false
    private var network = true
    private var closed = false
    private var terminal = false
    private var eligible = false
    private var expiredRoom: Long? = null
    private var inactive = false
    private var epoch = 0L
    private var ticket = 0L
    private var connection = uuid()
    private var acquiredConnection: String? = null
    private var streamJob: Job? = null
    private var usersJob: Job? = null
    private var usersDirty = false
    private var usersSequence = 0L
    private var pendingWrite: Write? = null
    private var activeWrite: Write? = null

    private val actor = scope.launch { for (action in mailbox) if (!closed) action() }
    private val observer = scope.launch { sessions.state.collect { execute { sessionChanged(sessions.state.value) } } }
    private val writer = scope.launch {
        for (write in writes) {
            // Never cancel a room-switch POST to start a competing POST on the same UUID.
            var valid = false
            execute {
                if (pendingWrite === write) pendingWrite = null
                valid = sessions.state.value.identity() == write.identity &&
                    (write.cleanup || (connection == write.connection && ticket == write.ticket))
                if (valid) activeWrite = write
            }
            if (!valid) continue
            try {
                val result = withTimeout(if (write.cleanup) 2_000L else 25_000L) {
                    awaitReady(write.identity)
                    execute { valid = sessions.state.value.identity() == write.identity &&
                        (write.cleanup || (connection == write.connection && ticket == write.ticket))
                        if (valid && write.room != null) acquiredConnection = write.connection
                    }
                    if (!valid) throw CancellationException("Presence target changed")
                    operations.heartbeat(write.connection, write.room, write.interacted)
                }
                execute {
                    if (write.cleanup && actualIdentity(write.identity) && acquiredConnection == write.connection)
                        acquiredConnection = null
                    if (!write.cleanup && current(write)) {
                        if (result.expired) expiredRoom = write.room
                        else if (write.interacted && result.roomId == write.room) expiredRoom = null
                        mutable.value = mutable.value.copy(presence = result, presenceReceivedAtMillis = clock.millis(), presenceError = null,
                            presencePhase = when { result.expired -> PresencePhase.EXPIRED
                                result.warningSeconds != null -> PresencePhase.WARNING
                                result.roomId != null -> PresencePhase.ACTIVE else -> PresencePhase.NONE })
                        if (result.expired) mutable.value = mutable.value.copy(users = emptyList(), presenceStale = true)
                        else fetchUsers()
                    }
                }
            } catch (failure: TimeoutCancellationException) { execute { presenceFailed(write, ApiFailure(FailureKind.TIMEOUT)) } }
            catch (cancelled: CancellationException) { if (!isActive) throw cancelled }
            catch (failure: ApiFailure) { execute { presenceFailed(write, failure) } }
            finally { execute { if (activeWrite === write) activeWrite = null } }
        }
    }
    private val timer = scope.launch {
        var ticks = 0
        while (isActive) {
            delay(20_000)
            execute {
                if (active()) {
                    if (++ticks % 3 == 0) reconcile()
                    if (eligible && mutable.value.presencePhase !in setOf(PresencePhase.EXPIRED, PresencePhase.ERROR)) queueWrite(false)
                }
            }
        }
    }

    fun setForeground(value: Boolean) = submit {
        if (foreground != value) {
            foreground = value
            if (!value) retirePresence()
            else { reconcile(); if (eligible && !inactive && mutable.value.presencePhase != PresencePhase.EXPIRED) queueWrite(false) }
            transport()
        }
    }

    fun setNetworkAvailable(value: Boolean) = submit {
        if (network != value) {
            network = value
            if (!value) retirePresence() // A pruned lease must not be recreated by passive connectivity restoration.
            transport()
        }
    }

    override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = submit {
        if (roomId != null && roomId <= 0) return@submit
        val changed = roomId != mutable.value.selectedRoomId
        val wasEligible = this.eligible
        this.eligible = roomId != null && eligible
        if (changed) {
            expiredRoom = null
            inactive = false
            ticket++; usersSequence++; usersJob?.cancel(); usersJob = null; usersDirty = false
            mutable.value = mutable.value.copy(selectedRoomId = roomId, presence = null, users = emptyList(),
                presenceStale = true, presenceError = null, presencePhase = PresencePhase.NONE)
        }
        if (interacted) inactive = false
        if (this.eligible && (changed || !wasEligible || interacted) &&
            (mutable.value.presencePhase != PresencePhase.EXPIRED || interacted)) queueWrite(interacted)
        else if (!this.eligible && wasEligible) {
            queueCleanup(connection); usersSequence++; usersJob?.cancel(); usersJob = null; usersDirty = false
            mutable.value = mutable.value.copy(presence = null, users = emptyList(), presenceStale = true,
                presencePhase = if (expiredRoom != null) PresencePhase.EXPIRED else PresencePhase.NONE)
        }
    }

    /** Call only after a server-acknowledged send/ping, never for scrolling or draft changes. */
    override fun activity(roomId: Long) = submit {
        if (roomId == mutable.value.selectedRoomId && expiredRoom == null && !inactive) queueWrite(true)
    }
    override fun reenter(roomId: Long) = submit { if (roomId == mutable.value.selectedRoomId && eligible) { inactive = false; queueWrite(true) } }
    override fun retry() = submit {
        terminal = false
        if (active()) { reconcile(); transport(); if (eligible && mutable.value.presencePhase != PresencePhase.EXPIRED) queueWrite(false) }
    }
    override fun dismissBroadcast(id: Long) = submit { mutable.value = mutable.value.copy(broadcasts = mutable.value.broadcasts.filterNot { it.id == id }) }

    fun close() = submit {
        retirePresence(); closed = true; epoch++; streamJob?.cancel(); usersJob?.cancel()
        observer.cancel(); timer.cancel(); writer.cancel(); writes.close()
        mutable.value = RealtimeState(); mailbox.close()
    }

    private fun sessionChanged(value: SessionState) {
        val next = value.identity()
        if (next != identity) {
            epoch++; ticket++; usersSequence++; streamJob?.cancel(); usersJob?.cancel(); usersJob = null; usersDirty = false
            identity = next; connection = uuid(); acquiredConnection = null; eligible = false; terminal = false
            expiredRoom = null
            inactive = false
            mutable.value = RealtimeState(generation = value.generation)
        }
        transport()
    }

    private fun transport() {
        val session = sessions.state.value
        val blocked = session.refreshRequired || session.storageWarning
        if (!active() || blocked || terminal) {
            epoch++; streamJob?.cancel()
            mutable.value = mutable.value.copy(phase = when {
                identity == null || !foreground || closed -> RealtimePhase.STOPPED
                !network -> RealtimePhase.OFFLINE
                else -> RealtimePhase.ERROR
            }, error = if (blocked) session.error ?: ApiFailure(if (session.storageWarning) FailureKind.STORAGE else FailureKind.EXPIRED) else mutable.value.error)
            return
        }
        if (streamJob?.isActive == true) return // A busy bootstrap alone does not retire a live stream.
        val expected = identity ?: return
        val token = ++epoch
        val previous = streamJob
        streamJob = scope.launch {
            previous?.join()
            var failures = 0
            while (isActive) {
                var wait = 1_000L
                try {
                    execute { if (token == epoch && actualIdentity(expected)) mutable.value = mutable.value.copy(
                        phase = if (mutable.value.cursor == 0L && failures == 0) RealtimePhase.CONNECTING else RealtimePhase.RECONNECTING) }
                    awaitReady(expected)
                    withTimeout(40_000) {
                        operations.stream(mutable.value.cursor) { signal -> execute {
                            if (token == epoch && actualIdentity(expected) && active()) when (signal) {
                                RealtimeSignal.Opened -> { failures = 0; mutable.value = mutable.value.copy(phase = RealtimePhase.LIVE, error = null); reconcile() }
                                is RealtimeSignal.Event -> accept(signal.value, expected.user)
                                RealtimeSignal.SessionInvalidated -> Unit // The shared session repository invalidates authentication.
                            }
                        } }
                    }
                } catch (failure: TimeoutCancellationException) {
                    failures++; wait = backoff(failures - 1); execute { if (token == epoch && actualIdentity(expected)) mutable.value = mutable.value.copy(error = ApiFailure(FailureKind.TIMEOUT)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: ApiFailure) {
                    execute { if (token == epoch && actualIdentity(expected)) {
                        terminal = failure.kind in setOf(FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE, FailureKind.REDIRECT, FailureKind.TLS)
                        mutable.value = mutable.value.copy(error = failure, phase = if (terminal) RealtimePhase.ERROR else RealtimePhase.RECONNECTING)
                    } }
                    if (terminal) break
                    wait = maxOf(backoff(failures++), (failure.retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1_000)
                }
                execute { if (token == epoch && actualIdentity(expected) && !terminal) { mutable.value = mutable.value.copy(phase = RealtimePhase.RECONNECTING); reconcile() } }
                delay(wait.coerceIn(1, 86_400_000))
            }
        }
    }

    private fun accept(event: RealtimeEvent, viewer: Long) {
        if (event.id <= mutable.value.cursor) return
        requireValidRealtimePayload(event, viewer) // Malformed known payloads never advance the cursor.
        if (!event.known || delivered.subscriptionCount.value == 0 || !delivered.tryEmit(event)) reconcile()
        if (event.type == "presence_changed" && event.roomId == mutable.value.selectedRoomId) fetchUsers()
        if (event.type in setOf("room_broadcast", "global_broadcast")) {
            val text = (event.payload["message"] as JsonPrimitive).content
            mutable.value = mutable.value.copy(broadcasts = (mutable.value.broadcasts +
                BroadcastNotice(event.id, event.roomId, text, event.createdAt)).takeLast(20))
        }
        mutable.value = mutable.value.copy(cursor = event.id) // Delivery/fallback is recorded first.
    }

    private fun queueWrite(interacted: Boolean) {
        val expected = identity ?: return
        if (!active() || !eligible || inactive || (expiredRoom == mutable.value.selectedRoomId && !interacted)) return
        if (!interacted && activeWrite?.let { !it.cleanup && it.identity == expected &&
                it.connection == connection && it.room == mutable.value.selectedRoomId } == true) return
        if (expiredRoom == null && (interacted || mutable.value.presencePhase !in setOf(PresencePhase.ACTIVE, PresencePhase.WARNING)))
            mutable.value = mutable.value.copy(presencePhase = PresencePhase.CONNECTING)
        val pending = pendingWrite
        val interaction = interacted || (pending != null && pending.room == mutable.value.selectedRoomId &&
            pending.connection == connection && pending.interacted)
        val write = Write(expected, connection, ++ticket, mutable.value.selectedRoomId, interaction)
        pendingWrite = write
        writes.trySend(write)
    }
    private fun queueCleanup(id: String) {
        val expected = identity ?: return
        if (network && actualIdentity(expected) && acquiredConnection == id) {
            val write = Write(expected, id, ++ticket, null, false, cleanup = true)
            pendingWrite = write; writes.trySend(write)
        }
    }
    private fun retirePresence() {
        queueCleanup(connection); connection = uuid(); acquiredConnection = null; ticket++
        inactive = true
        usersSequence++; usersJob?.cancel(); usersJob = null; usersDirty = false
        mutable.value = mutable.value.copy(users = emptyList(), presenceStale = true,
            presencePhase = if (expiredRoom != null) PresencePhase.EXPIRED else PresencePhase.NONE)
    }
    private fun presenceFailed(write: Write, failure: ApiFailure) {
        if (write.cleanup || !actualIdentity(write.identity) || connection != write.connection) return
        val applies = current(write)
        connection = uuid(); acquiredConnection = null; ticket++ // Unknown old writes cannot clobber this connection's next selection.
        if (applies) mutable.value = mutable.value.copy(
            presencePhase = if (expiredRoom != null) PresencePhase.EXPIRED else PresencePhase.ERROR,
            presenceStale = true, presenceError = failure)
        else queueWrite(false)
    }
    private fun fetchUsers() {
        val room = mutable.value.selectedRoomId ?: return
        val expected = identity ?: return
        if (!active() || !eligible || inactive || mutable.value.presencePhase == PresencePhase.EXPIRED) return
        if (usersJob?.isActive == true) { usersDirty = true; return }
        val target = ticket
        val request = ++usersSequence
        usersJob = scope.launch {
            try {
                awaitReady(expected)
                val users = operations.presence(room)
                execute { if (actualIdentity(expected) && ticket == target && active()) mutable.value = mutable.value.copy(users = users, presenceStale = false, presenceError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ApiFailure) { execute { if (actualIdentity(expected) && ticket == target) mutable.value = mutable.value.copy(presenceStale = true, presenceError = failure) } }
            finally { submit { if (actualIdentity(expected) && request == usersSequence) { usersJob = null; if (usersDirty) { usersDirty = false; fetchUsers() } } } }
        }
    }
    private suspend fun awaitReady(expected: Identity) {
        while (true) {
            val value = sessions.state.first { it.identity() != expected || (!it.busy && !it.refreshRequired && !it.storageWarning) }
            if (value.identity() != expected) throw CancellationException("Session retired")
            val remaining = value.retryAtMillis - clock.millis()
            if (remaining <= 0) return
            delay(remaining)
        }
    }
    private fun actualIdentity(expected: Identity) = identity == expected && sessions.state.value.identity() == expected
    private fun active() = !closed && foreground && network && identity?.let(::actualIdentity) == true
    private fun current(write: Write) = active() && actualIdentity(write.identity) && ticket == write.ticket && connection == write.connection
    private fun reconcile() { mutable.value = mutable.value.copy(reconcileVersion = mutable.value.reconcileVersion + 1) }
    private fun submit(action: () -> Unit) { if (!closed && mailbox.trySend(action).isFailure) scope.launch { if (!closed) mailbox.send(action) } }
    private suspend fun execute(action: () -> Unit) {
        if (closed) return
        val result = CompletableDeferred<Result<Unit>>()
        mailbox.send { result.complete(runCatching(action)) }
        result.await().getOrThrow()
    }
    private data class Identity(val server: String?, val generation: Long, val user: Long, val version: Int)
    private fun SessionState.identity() = user?.takeIf { phase == SessionPhase.AUTHENTICATED }
        ?.let { Identity(server?.value, generation, it.id, it.sessionVersion) }
    private class Write(val identity: Identity, val connection: String, val ticket: Long, val room: Long?, val interacted: Boolean, val cleanup: Boolean = false)
}
