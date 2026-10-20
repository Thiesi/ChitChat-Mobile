package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.AuthFlow
import io.github.thiesi.chitchat.core.session.SessionController
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeCoordinatorTest {
    @Test fun oneStreamSurvivesBusyBootstrapAndStopsOfflineOrBackground() = runTest {
        val h = Harness(this); h.start()
        assertEquals(1, h.api.streams.size); assertEquals(RealtimePhase.LIVE, h.model.state.value.phase)
        h.sessions.state.value = h.sessions.state.value.copy(busy = true); runCurrent()
        assertEquals(1, h.api.streams.size)
        h.model.setNetworkAvailable(false); runCurrent()
        assertEquals(0, h.api.active); assertEquals(RealtimePhase.OFFLINE, h.model.state.value.phase)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, h.api.streams.size)
        h.sessions.state.value = h.sessions.state.value.copy(busy = false)
        h.model.setNetworkAvailable(true); runCurrent(); assertEquals(2, h.api.streams.size)
        h.model.setForeground(false); runCurrent(); assertEquals(0, h.api.active)
        assertEquals(1, h.api.maximumActive)
    }

    @Test fun startingAwaitsSessionReadinessAndCooldownWithoutLoggingOut() = runTest {
        val h = Harness(this)
        h.sessions.state.value = h.sessions.state.value.copy(busy = true, retryAtMillis = 2_000)
        h.start(); assertTrue(h.api.streams.isEmpty())
        h.sessions.state.value = h.sessions.state.value.copy(busy = false); runCurrent()
        advanceTimeBy(1_999); runCurrent(); assertTrue(h.api.streams.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(1, h.api.streams.size)
        assertEquals(SessionPhase.AUTHENTICATED, h.sessions.state.value.phase)
    }

    @Test fun homeAndNeverStartedRoomSelectionDoNotGenerateCleanupPosts() = runTest {
        val h = Harness(this); h.start()
        h.model.setForeground(false); runCurrent(); h.model.setForeground(true); runCurrent()
        assertTrue(h.api.writes.isEmpty())
        h.sessions.state.value = h.sessions.state.value.copy(busy = true); runCurrent()
        h.select(42); h.model.setForeground(false); runCurrent()
        h.sessions.state.value = h.sessions.state.value.copy(busy = false); runCurrent()
        assertTrue(h.api.writes.isEmpty()) // Queued work never reached HTTP; no lease exists to clean up.
        h.model.close(); runCurrent(); assertTrue(h.api.writes.isEmpty())
    }

    @Test fun uncertainConnectivityCannotSilentlyRecreateAPrunedPresenceLease() = runTest {
        val h = Harness(this); h.start(); h.select(42); val old = h.api.writes.single().connection
        h.model.setNetworkAvailable(false); runCurrent(); advanceTimeBy(90_000); runCurrent()
        h.model.setNetworkAvailable(true); h.model.retry(); runCurrent()
        h.model.selectRoom(42, true); advanceTimeBy(20_000); runCurrent()
        assertEquals(1, h.api.writes.size); assertEquals(PresencePhase.NONE, h.model.state.value.presencePhase)
        assertTrue(h.model.state.value.users.isEmpty()); assertEquals(42L, h.model.state.value.selectedRoomId)
        h.model.reenter(42); runCurrent()
        assertEquals(2, h.api.writes.size); assertTrue(h.api.writes.last().interacted)
        assertNotEquals(old, h.api.writes.last().connection)
    }

    @Test fun normalEofUsesOneSecondAndFailureHonorsRetryAfter() = runTest {
        val h = Harness(this); h.start(); h.api.streams.last().signals.close(); runCurrent()
        advanceTimeBy(999); runCurrent(); assertEquals(1, h.api.streams.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, h.api.streams.size)
        h.api.nextStreamFailure = ApiFailure(FailureKind.HTTP, 429, retryAfterSeconds = 4)
        h.api.streams.last().signals.close(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(3, h.api.attempts)
        advanceTimeBy(3_999); runCurrent(); assertEquals(3, h.api.attempts)
        advanceTimeBy(1); runCurrent(); assertEquals(4, h.api.attempts)
    }

    @Test fun duplicateEventsDoNotReplayAndGapsAreLegal() = runTest {
        val h = Harness(this); h.start()
        h.emit(event(10)); val revision = h.model.state.value.reconcileVersion
        h.emit(event(10)); h.emit(event(9)); assertEquals(revision, h.model.state.value.reconcileVersion)
        h.emit(event(91)); assertEquals(91L, h.model.state.value.cursor)
        assertTrue(h.model.state.value.reconcileVersion > revision) // No subscriber: durable fallback.
        h.api.streams.last().signals.close(); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(91L, h.api.streams.last().after)
    }

    @Test fun malformedKnownPayloadStopsWithoutAdvancingAndRetryIsExplicit() = runTest {
        val h = Harness(this); h.start(); h.emit(event(3))
        h.emit(event(4, "global_broadcast"))
        assertEquals(3L, h.model.state.value.cursor); assertEquals(RealtimePhase.ERROR, h.model.state.value.phase)
        advanceTimeBy(10_000); runCurrent(); assertEquals(1, h.api.streams.size)
        h.model.retry(); runCurrent(); assertEquals(2, h.api.streams.size)
    }

    @Test fun slowConsumerOverflowRecordsReconciliationBeforeCursor() = runTest {
        val h = Harness(this); h.start()
        val blocked = CompletableDeferred<Unit>()
        backgroundScope.launch { h.model.events.collect { blocked.await() } }; runCurrent()
        h.emit(event(1, "rooms_changed")); val revision = h.model.state.value.reconcileVersion
        repeat(70) { h.emit(event(it + 2L, "rooms_changed")) }
        assertEquals(71L, h.model.state.value.cursor)
        assertTrue(h.model.state.value.reconcileVersion > revision)
        blocked.complete(Unit)
    }

    @Test fun identityTransitionClearsContentAndLateStreamCannotRepopulate() = runTest {
        val h = Harness(this); h.start(); val old = h.api.streams.single()
        h.emit(event(2, "global_broadcast", message = "Private banner"))
        h.select(42); assertEquals(1, h.model.state.value.broadcasts.size)
        h.sessions.state.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT); runCurrent()
        backgroundScope.launch { old.receive(RealtimeSignal.Event(event(20, "global_broadcast", message = "Late"))) }; runCurrent()
        assertEquals(0L, h.model.state.value.cursor); assertTrue(h.model.state.value.broadcasts.isEmpty())
        assertTrue(h.model.state.value.users.isEmpty()); assertNull(h.model.state.value.selectedRoomId)
        assertEquals(RealtimePhase.STOPPED, h.model.state.value.phase)
    }

    @Test fun actualSessionChangeFencesAnEventAlreadyQueuedAheadOfObserver() = runTest {
        val h = Harness(this); h.start(); val old = h.api.streams.single()
        val snapshots = mutableListOf<RealtimeState>(); val events = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.model.state.collect { snapshots += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.model.events.collect { events += it.id } }
        // Enqueue the event command first, but change actual authentication before the actor runs it.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            old.receive(RealtimeSignal.Event(event(20, "global_broadcast", message = "Retired account")))
        }
        h.sessions.state.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT)
        runCurrent()
        assertFalse(snapshots.any { it.cursor == 20L || it.broadcasts.any { notice -> notice.id == 20L } })
        assertTrue(events.isEmpty()); assertEquals(RealtimePhase.STOPPED, h.model.state.value.phase)
    }

    @Test fun streamWatchdogReconnectsAndPeriodicForegroundFallbackRuns() = runTest {
        val h = Harness(this); h.start(); val revision = h.model.state.value.reconcileVersion
        advanceTimeBy(40_000); runCurrent()
        assertEquals(FailureKind.TIMEOUT, h.model.state.value.error?.kind)
        advanceTimeBy(1_000); runCurrent(); assertEquals(2, h.api.streams.size)
        advanceTimeBy(19_000); runCurrent(); assertTrue(h.model.state.value.reconcileVersion > revision)
        h.model.setForeground(false); runCurrent(); val stopped = h.model.state.value.reconcileVersion
        advanceTimeBy(80_000); runCurrent(); assertEquals(stopped, h.model.state.value.reconcileVersion)
    }

    @Test fun onlyExplicitInteractionRenewsActivityAndAggregateIsRefetched() = runTest {
        val h = Harness(this); h.start(); h.select(42, interacted = true)
        assertTrue(h.api.writes.last().interacted); assertEquals(1, h.api.presenceReads)
        advanceTimeBy(20_000); runCurrent(); assertFalse(h.api.writes.last().interacted)
        h.model.activity(99); runCurrent(); val count = h.api.writes.size
        h.model.activity(42); runCurrent(); assertEquals(count + 1, h.api.writes.size); assertTrue(h.api.writes.last().interacted)
        h.emit(event(7, "presence_changed", 42)); assertTrue(h.api.presenceReads >= 3)
        assertEquals(2, h.model.state.value.users.single().connections)
    }

    @Test fun expiredRoomNeverReentersOnRenewResumeRetryOrFailedExplicitReentry() = runTest {
        val h = Harness(this); h.start(); h.select(42)
        h.api.nextStatus = PresenceStatus(null, 0, null, true, 45)
        advanceTimeBy(20_000); runCurrent(); assertEquals(PresencePhase.EXPIRED, h.model.state.value.presencePhase)
        val count = h.api.writes.count { it.room != null }
        h.model.setNetworkAvailable(false); runCurrent(); h.model.setNetworkAvailable(true); runCurrent()
        assertEquals(PresencePhase.EXPIRED, h.model.state.value.presencePhase)
        h.model.setForeground(false); runCurrent(); h.model.setForeground(true); h.model.retry(); runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertEquals(count, h.api.writes.count { it.room != null })
        h.api.nextPresenceFailure = ApiFailure(FailureKind.NETWORK)
        h.model.reenter(42); runCurrent(); assertEquals(PresencePhase.EXPIRED, h.model.state.value.presencePhase)
        h.model.retry(); runCurrent(); assertEquals(count + 1, h.api.writes.count { it.room != null })
        h.model.reenter(42); runCurrent(); assertTrue(h.api.writes.last().interacted)
        assertEquals(PresencePhase.ACTIVE, h.model.state.value.presencePhase)
    }

    @Test fun roomSwitchAndBackgroundCleanupNeverCompeteOnSameConnection() = runTest {
        val h = Harness(this); h.start()
        val gate = CompletableDeferred<Unit>(); h.api.writeGate = gate
        h.select(42); val oldId = h.api.writes.single().connection
        h.model.selectRoom(43, true, true); h.model.setForeground(false); runCurrent()
        h.model.setForeground(true); h.model.reenter(43); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(1, h.api.maximumWrites)
        assertEquals(43L, h.api.writes.last().room); assertNotEquals(oldId, h.api.writes.last().connection)
        assertEquals(43L, h.model.state.value.presence?.roomId)
        h.model.setForeground(false); runCurrent(); assertNull(h.api.writes.last().room)
        assertFalse(h.api.writes.last().interacted)
    }

    @Test fun foregroundAloneCannotReenterAfterGenuineBackground() = runTest {
        val h = Harness(this); h.start(); h.select(42)
        h.model.setForeground(false); runCurrent(); val count = h.api.writes.size
        h.model.setForeground(true); h.model.retry(); runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertEquals(count, h.api.writes.size)
        assertEquals(42L, h.model.state.value.selectedRoomId); assertEquals(PresencePhase.NONE, h.model.state.value.presencePhase)
        h.model.reenter(42); runCurrent(); assertEquals(count + 1, h.api.writes.size)
        assertTrue(h.api.writes.last().interacted)
    }

    @Test fun permissionFailureStaysPresenceLocalAndRequiresRetry() = runTest {
        val h = Harness(this); h.start(); h.api.nextPresenceFailure = ApiFailure(FailureKind.HTTP, 403, "room_membership_required")
        h.select(42); assertEquals(PresencePhase.ERROR, h.model.state.value.presencePhase)
        assertEquals(RealtimePhase.LIVE, h.model.state.value.phase)
        val count = h.api.writes.size; advanceTimeBy(20_000); runCurrent(); assertEquals(count, h.api.writes.size)
        h.model.retry(); runCurrent(); assertEquals(PresencePhase.ACTIVE, h.model.state.value.presencePhase)
    }

    @Test fun routineTimerCannotReplaceQueuedDeliberateInteractionOrRacePost() = runTest {
        val h = Harness(this); h.start(); h.select(42)
        val gate = CompletableDeferred<Unit>(); h.api.writeGate = gate
        h.model.activity(42); runCurrent(); h.model.activity(42); runCurrent()
        advanceTimeBy(20_000); runCurrent(); assertEquals(2, h.api.writes.size)
        gate.complete(Unit); runCurrent(); assertEquals(3, h.api.writes.size)
        assertTrue(h.api.writes.last().interacted); assertEquals(1, h.api.maximumWrites)
    }

    @Test fun latePresenceResponseCannotRestoreSignedOutUsersOrSelectedRoom() = runTest {
        val h = Harness(this); h.start()
        val gate = CompletableDeferred<Unit>(); h.api.writeGate = gate; h.select(42)
        h.sessions.state.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(h.model.state.value.presence); assertNull(h.model.state.value.selectedRoomId)
        assertTrue(h.model.state.value.users.isEmpty()); assertEquals(0, h.api.presenceReads)
    }

    @Test fun broadcastsAreBoundedTimestampedDismissibleAndNotGeneratedStrings() = runTest {
        val h = Harness(this); h.start()
        repeat(23) { h.emit(event(it + 1L, "global_broadcast", message = "Sensitive")) }
        val banners = h.model.state.value.broadcasts
        assertEquals(20, banners.size); assertEquals(4L, banners.first().id)
        assertEquals(Instant.EPOCH, banners.first().createdAt)
        assertFalse(h.model.state.value.toString().contains("Sensitive"))
        assertFalse(banners.first().toString().contains("Sensitive"))
        h.model.dismissBroadcast(4); runCurrent(); assertEquals(19, h.model.state.value.broadcasts.size)
    }

    private class Harness(private val scope: TestScope) {
        val sessions = Sessions()
        val api = Operations(sessions)
        private var ids = 0
        val model = RealtimeCoordinator(api, sessions, scope.backgroundScope, object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(scope.testScheduler.currentTime)
        }, uuid = { "connection-${++ids}" }, backoff = { 1_000 })
        fun start() { scope.runCurrent(); model.setForeground(true); scope.runCurrent() }
        fun select(id: Long, interacted: Boolean = false) { model.selectRoom(id, true, interacted); scope.runCurrent() }
        fun emit(event: RealtimeEvent) { api.streams.last().signals.trySend(RealtimeSignal.Event(event)); scope.runCurrent() }
    }
    private class Sessions : SessionController {
        override val state = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        override suspend fun connect(server: ServerAddress?) = Unit
        override suspend fun signIn(username: String, password: String, restore: Boolean) = Unit
        override suspend fun completeRecovery(code: String) = Unit
        override suspend fun refresh() = Unit
        override suspend fun signOut() = Unit
        override suspend fun selectAuthFlow(flow: AuthFlow) = Unit
        override suspend fun prepareRegistration() = Unit
        override suspend fun register(username: String, password: String, birthDate: String?) = Unit
    }
    private class Stream(val after: Long, val receive: suspend (RealtimeSignal) -> Unit) {
        val signals = Channel<RealtimeSignal>(Channel.UNLIMITED) // Test driver only; production uses bounded delivery.
    }
    private class Call(val connection: String, val room: Long?, val interacted: Boolean)
    private class Operations(sessions: Sessions) : RealtimeOperations {
        override val session = sessions.state
        val streams = mutableListOf<Stream>(); val writes = mutableListOf<Call>()
        var active = 0; var maximumActive = 0; var attempts = 0
        var activeWrites = 0; var maximumWrites = 0; var presenceReads = 0
        var nextStreamFailure: ApiFailure? = null; var nextPresenceFailure: ApiFailure? = null
        var nextStatus: PresenceStatus? = null; var writeGate: CompletableDeferred<Unit>? = null
        override suspend fun stream(afterId: Long, receive: suspend (RealtimeSignal) -> Unit) {
            attempts++; nextStreamFailure?.let { nextStreamFailure = null; throw it }
            val stream = Stream(afterId, receive); streams += stream; active++; maximumActive = maxOf(maximumActive, active)
            try { receive(RealtimeSignal.Opened); for (signal in stream.signals) receive(signal) }
            finally { active-- }
        }
        override suspend fun heartbeat(connectionId: String, roomId: Long?, interacted: Boolean): PresenceStatus {
            writes += Call(connectionId, roomId, interacted); activeWrites++; maximumWrites = maxOf(maximumWrites, activeWrites)
            try {
                writeGate?.let { writeGate = null; it.await() }
                nextPresenceFailure?.let { nextPresenceFailure = null; throw it }
                return nextStatus?.also { nextStatus = null } ?: PresenceStatus(roomId, 0, null, false, 45)
            } finally { activeWrites-- }
        }
        override suspend fun presence(roomId: Long): List<PresenceUser> {
            presenceReads++; return listOf(PresenceUser(7, "Viewer", false, 0, 2))
        }
    }
    companion object {
        private fun event(id: Long, type: String = "future_event", room: Long? = null, message: String? = null) =
            RealtimeEvent(id, type, room, null, JsonObject(buildMap {
                if (message != null) put("message", JsonPrimitive(message))
                if (type == "presence_changed") { put("room_id", JsonPrimitive(room)); put("user_id", JsonPrimitive(7)) }
            }), Instant.EPOCH)
    }
}
