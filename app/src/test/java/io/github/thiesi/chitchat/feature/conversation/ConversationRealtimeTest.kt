package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRealtimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Conversations()
    private val realtime = Connection()
    private lateinit var model: ConversationViewModel
    @Before fun start() { Dispatchers.setMain(dispatcher); model = ConversationViewModel(api, realtime) }
    @After fun stop() {
        model.cancel(); api.readGate?.complete(Unit); api.sendGate?.complete(Unit); api.windowGate?.complete(Unit)
        Dispatchers.resetMain()
    }
    private fun TestScope.open() { model.foreground(); model.open(42, interacted = true); runCurrent() }

    private suspend fun target(id: Long = 2, room: Long = 42): TargetSnapshot.RoomMessage {
        val context = api.context(room)
        api.session.value = api.session.value.copy(chatPolicy = context.policy)
        return TargetSnapshot.RoomMessage(TargetRequest(api.session.value, MessageTarget.RoomMessage(room, id)),
            context, api.history(room, beforeTarget(id)), api.pings(room))
    }

    @Test fun exactReplyAdoptionPreservesDraftAndNeverGrantsLatestReadProof() = runTest(dispatcher) {
        open(); model.updateDraft("Keep reply-navigation draft"); val snapshot = target()
        assertTrue(model.adoptTarget(snapshot))
        val value = model.state.value
        assertEquals(2L, value.fixedUpper); assertEquals(2L, value.highlightedMessageId)
        assertEquals(2L, value.anchor?.id); assertFalse(value.latestWindow); assertFalse(value.atLatest)
        assertEquals("Keep reply-navigation draft", value.draft); assertEquals(1L, value.readCursor)
        model.viewport(TimelineAnchor(2, 0), 2, true); runCurrent()
        assertEquals(0, api.reads); assertEquals(0, api.sends)
    }
    @Test fun targetRangeRemainsBoundedAfterEveryRowDisappearsAcrossHintResumeAndRefresh() = runTest(dispatcher) {
        open(); assertTrue(model.adoptTarget(target()))
        api.rows = listOf(message(80), message(81)); model.invalidate(); runCurrent()
        assertTrue(model.state.value.messages.isEmpty()); assertEquals(2L, model.state.value.fixedUpper)
        assertFalse(model.state.value.latestWindow); assertNull(model.state.value.highlightedMessageId)
        assertTrue(model.state.value.anchorUnavailable)
        model.invalidate(); runCurrent(); model.background(); model.foreground(); runCurrent(); model.refresh(); runCurrent()
        assertTrue(api.windows.all { it.upper == 2L }); assertTrue(model.state.value.messages.isEmpty())
        assertEquals(2L, model.state.value.fixedUpper); assertEquals(0, api.reads)
        model.latest(); runCurrent(); assertEquals(listOf(80L, 81L), model.state.value.messages.map { it.id })
        assertTrue(model.state.value.latestWindow); assertNull(model.state.value.fixedUpper)
    }
    @Test fun targetAdoptionCannotCancelAReadPostOrGrantUnknownSendConsent() = runTest(dispatcher) {
        open(); val snapshot = target()
        api.readGate = CompletableDeferred(); model.viewport(TimelineAnchor(3, 0), 3, true); runCurrent()
        assertFalse(model.adoptTarget(snapshot)); assertEquals(0, api.cancelledReads)
        api.readGate!!.complete(Unit); runCurrent()
        model.updateDraft("Keep uncertain reply draft"); api.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        model.send(); runCurrent(); assertTrue(model.state.value.uncertainSend)
        assertTrue(model.adoptTarget(snapshot)); assertTrue(model.state.value.uncertainSend)
        assertFalse(model.state.value.reconciledAfterUncertain); assertEquals("Keep uncertain reply draft", model.state.value.draft)
        model.acknowledgeDuplicateRisk(); assertTrue(model.state.value.uncertainSend); assertEquals(1, api.sends)
    }
    @Test fun staleWrongRoomUnavailableAndBackgroundSnapshotsCannotReplaceThePane() = runTest(dispatcher) {
        open(); val snapshot = target(); assertFalse(model.adoptTarget(target(2, 43)))
        assertFalse(model.adoptTarget(target(99))); assertEquals(42L, model.state.value.roomId)
        model.background(); assertFalse(model.adoptTarget(snapshot)); model.foreground(); runCurrent()
        api.session.value = api.session.value.copy(generation = 2)
        assertFalse(model.adoptTarget(snapshot)); runCurrent(); assertTrue(model.state.value.messages.isEmpty())
    }
    @Test fun alreadyConfirmedReadCursorStillPublishesIdleForAWaitingTarget() = runTest(dispatcher) {
        open(); val snapshot = target(); api.readResult = 1; api.readGate = CompletableDeferred()
        model.viewport(TimelineAnchor(3, 0), 3, true); runCurrent()
        assertTrue(model.state.value.reading); assertFalse(model.canAdoptTarget(snapshot))
        api.readGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.reading); assertEquals(1L, model.state.value.readCursor)
        assertTrue(model.canAdoptTarget(snapshot)); assertTrue(model.adoptTarget(snapshot)); assertEquals(1, api.reads)
    }
    @Test fun currentRoomPolicyWinsAtAdoptionAndDoesNotRevealIgnoredTargets() = runTest(dispatcher) {
        open(); val snapshot = target()
        api.session.value = api.session.value.copy(chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences()))
        assertTrue(model.adoptTarget(snapshot)); assertEquals(setOf(8L), model.state.value.context?.policy?.ignoredUserIds)
        assertTrue(model.state.value.revealed.isEmpty()); assertTrue(model.state.value.pings.isEmpty())
    }
    @Test fun oldRenderedSendCannotTransmitANewRoomOrAccountsRetainedDraft() = runTest(dispatcher) {
        open(); val original = api.session.value
        model.open(43, interacted = true); runCurrent(); model.updateDraft("Current room draft")
        model.send(original, 42); runCurrent(); assertEquals(0, api.sends)
        assertEquals("Current room draft", model.state.value.draft)
        api.session.value = api.session.value.copy(generation = 2)
        model.send(original, 43); assertEquals(0, api.sends)
        runCurrent(); assertTrue(model.drafts.value.isEmpty())
    }
    @Test fun targetPermissionLossClearsOnlyItsCurrentRoomContentAndKeepsTheDraft() = runTest(dispatcher) {
        open(); val snapshot = target(); model.updateDraft("Draft survives access loss")
        val denied = ApiFailure(FailureKind.HTTP, 403, "room_forbidden")
        model.targetDenied(target(20, 43).request, denied)
        model.targetDenied(snapshot.request, ApiFailure(FailureKind.HTTP, 403, "csrf_failed"))
        assertTrue(model.state.value.messages.isNotEmpty())
        model.targetDenied(snapshot.request, denied)
        assertTrue(model.state.value.messages.isEmpty()); assertNull(model.state.value.context)
        assertFalse(model.state.value.loaded); assertEquals("Draft survives access loss", model.state.value.draft)
        assertEquals("Draft survives access loss", model.drafts.value[42]?.body)
        assertEquals(0, api.reads); assertEquals(0, api.sends)
    }

    @Test fun automaticSyncDoesNotCancelReplayOrLoseAnActiveReadPost() = runTest(dispatcher) {
        open(); api.readGate = CompletableDeferred()
        model.viewport(TimelineAnchor(2, 9), 3, true); runCurrent(); assertEquals(1, api.reads)
        model.invalidate(); runCurrent()
        assertEquals(1, api.windows.size); assertEquals(0, api.cancelledReads); assertEquals(1, api.reads)
        assertEquals(1L, model.state.value.readCursor)
        api.readGate!!.complete(Unit); runCurrent()
        assertEquals(3L, model.state.value.readCursor); assertEquals(1, api.reads)
    }

    @Test fun sendQueuesHintsAndOwnEventBeforeAcknowledgmentDeduplicatesById() = runTest(dispatcher) {
        open(); api.sendGate = CompletableDeferred(); model.updateDraft("Unchanged outgoing body")
        model.send(); runCurrent(); api.rows = api.rows + message(4, sender = 7)
        repeat(3) { model.invalidate() }; runCurrent()
        assertTrue(api.windows.isEmpty()); assertEquals(1, api.sends)
        api.sendGate!!.complete(Unit); runCurrent()
        assertEquals(listOf(2L, 3L, 4L), model.state.value.messages.map { it.id })
        assertEquals(1, api.windows.size); assertEquals(1, api.sends); assertEquals("", model.state.value.draft)
        assertEquals(listOf(42L), realtime.activity)
    }

    @Test fun acknowledgedSendKeepsAnOmittedRawPaginationBoundaryWhenNoRowsWereTrimmed() = runTest(dispatcher) {
        api.firstRaw = 1; open(); assertEquals(1L, model.state.value.olderCursor)
        model.updateDraft("Keep raw boundary"); model.send(); runCurrent()
        assertEquals(listOf(2L, 3L, 4L), model.state.value.messages.map { it.id })
        assertEquals(1L, model.state.value.olderCursor); assertFalse(model.state.value.mayHaveOlder)
    }

    @Test fun automaticSyncCannotGrantUnknownDeliveryConsentOrClearTheDraft() = runTest(dispatcher) {
        open(); model.updateDraft("Keep unknown draft"); api.sendFailure = ApiFailure(FailureKind.NETWORK)
        model.send(); runCurrent(); assertTrue(model.state.value.uncertainSend)
        model.invalidate(); runCurrent()
        assertEquals("Keep unknown draft", model.state.value.draft); assertFalse(model.state.value.reconciledAfterUncertain)
        assertFalse(model.canSend()); model.refresh(); runCurrent()
        assertTrue(model.state.value.reconciledAfterUncertain)
        model.invalidate(); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertTrue(model.state.value.reconciledAfterUncertain)
        assertEquals("Keep unknown draft", model.state.value.draft); assertEquals(1, api.sends)
        model.acknowledgeDuplicateRisk(); assertFalse(model.state.value.uncertainSend); assertEquals(1, api.sends)
    }

    @Test fun automaticSyncPreservesReadFailureDividerPrivatePingsAndUnsentDraft() = runTest(dispatcher) {
        open(); val divider = model.state.value.unreadDividerId
        val failure = ApiFailure(FailureKind.HTTP, 500, "fixture"); api.readFailure = failure
        model.viewport(TimelineAnchor(2, 7), 3, true); runCurrent(); model.updateDraft("Keep draft")
        model.invalidate(); runCurrent()
        assertSame(failure, model.state.value.readError); assertEquals(divider, model.state.value.unreadDividerId)
        assertEquals("Keep draft", model.state.value.draft); assertEquals(1L, model.state.value.readCursor)
        assertEquals(1, api.reads); assertEquals(listOf(9L), model.state.value.pings.map { it.id })
    }

    @Test fun olderWindowKeepsFixedRawBoundaryAndUsesViewportCapturedAfterLateScroll() = runTest(dispatcher) {
        api.rows = (1L..600).map { message(it) }; open()
        repeat(10) { model.older(); runCurrent() }
        assertFalse(model.state.value.latestWindow); assertEquals(51L, model.state.value.olderCursor)
        model.viewport(TimelineAnchor(200, 17), null, false)
        val scroll = model.state.value.scrollRequest; api.windowGate = CompletableDeferred()
        model.invalidate(); runCurrent(); model.viewport(TimelineAnchor(300, 29), null, false)
        api.windowGate!!.complete(Unit); runCurrent()
        assertEquals(WindowCall(42, 51, 550), api.windows.single())
        assertEquals(51L, model.state.value.olderCursor); assertEquals(300L, model.state.value.anchor?.id)
        assertEquals(29, model.state.value.anchor?.offset); assertEquals(scroll, model.state.value.scrollRequest)
        assertTrue(model.state.value.newerAvailable); assertEquals(0, api.reads)
    }

    @Test fun boundedLatestNonoverlapRefreshesFixedRangeWithoutStitchingOrJumping() = runTest(dispatcher) {
        api.rows = (551L..600).map { message(it) }; open(); model.viewport(TimelineAnchor(575, 12), null, false)
        val scroll = model.state.value.scrollRequest
        api.answer = { lower, upper -> if (upper == null) WindowSnapshot((651L..1250).map { message(it) }, false, lower, true)
            else WindowSnapshot((551L..600).map { message(it) }, true, lower, true) }
        model.invalidate(); runCurrent()
        assertEquals(listOf<Long?>(null, 600), api.windows.map { it.upper })
        assertEquals(551L, model.state.value.messages.first().id); assertEquals(600L, model.state.value.messages.last().id)
        assertEquals(551L, model.state.value.olderCursor); assertFalse(model.state.value.latestWindow)
        assertEquals(575L, model.state.value.anchor?.id); assertEquals(scroll, model.state.value.scrollRequest)
        assertTrue(model.state.value.newerAvailable); assertNull(model.state.value.syncError)
    }

    @Test fun incompleteSmallLiveEdgeSnapshotCannotSkipAnUnvisitedRawGap() = runTest(dispatcher) {
        open(); model.viewport(TimelineAnchor(2, 0), 3, true); runCurrent()
        val scroll = model.state.value.scrollRequest
        api.answer = { lower, _ -> WindowSnapshot(listOf(message(700), message(701)), false, lower, true) }
        model.invalidate(); runCurrent()
        assertEquals(listOf(2L, 3L), model.state.value.messages.map { it.id }); assertEquals(2L, model.state.value.olderCursor)
        assertEquals("history_range_unavailable", model.state.value.syncError?.code)
        assertTrue(model.state.value.stale); assertEquals(scroll, model.state.value.scrollRequest); assertEquals(1, api.reads)
    }

    @Test fun retentionLosingAnchorChoosesNextSurvivorWithoutMovingRawBoundary() = runTest(dispatcher) {
        api.rows = (1L..100).map { message(it) }; open(); model.viewport(TimelineAnchor(70, 21), null, false)
        val scroll = model.state.value.scrollRequest; api.rows = api.rows.filterNot { it.id == 70L }
        model.invalidate(); runCurrent()
        assertEquals(71L, model.state.value.anchor?.id); assertEquals(0, model.state.value.anchor?.offset)
        assertTrue(model.state.value.anchorUnavailable); assertEquals(51L, model.state.value.olderCursor)
        assertEquals(scroll, model.state.value.scrollRequest)
    }

    @Test fun concurrentReadDenialRetiresPendingSendButPreservesUnknownDelivery() = runTest(dispatcher) {
        open(); api.readGate = CompletableDeferred(); api.sendGate = CompletableDeferred()
        model.viewport(TimelineAnchor(2, 0), 3, true); runCurrent()
        model.updateDraft("May have arrived"); model.send(); runCurrent()
        api.readFailure = ApiFailure(FailureKind.HTTP, 403, "room_forbidden")
        api.readGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertTrue(model.state.value.messages.isEmpty())
        assertNull(model.state.value.context); assertEquals("May have arrived", model.state.value.draft)
        api.sendGate!!.complete(Unit); runCurrent()
        assertEquals("May have arrived", model.state.value.draft); assertTrue(realtime.activity.isEmpty())
        api.readFailure = null; model.refresh(); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertTrue(model.state.value.reconciledAfterUncertain)
        assertEquals(1, api.sends); assertFalse(model.canSend())
    }

    @Test fun definitiveSendPermissionRejectionDoesNotInventUnknownDelivery() = runTest(dispatcher) {
        open(); model.updateDraft("Rejected draft"); api.sendFailure = ApiFailure(FailureKind.HTTP, 403, "room_forbidden")
        model.send(); runCurrent()
        assertTrue(model.state.value.messages.isEmpty()); assertNull(model.state.value.context)
        assertFalse(model.state.value.uncertainSend); assertEquals("Rejected draft", model.state.value.draft)
        assertEquals(1, api.sends); assertTrue(realtime.activity.isEmpty())
    }

    @Test fun actualSessionChangeBeforeObserverPreventsReadSendAndPresenceIntents() = runTest(dispatcher) {
        open(); model.updateDraft("Retired account draft"); val selections = realtime.selections.size
        api.session.value = api.session.value.copy(generation = 2, user = SessionUser(8, "Next", listOf("member"), 2, false))
        realtime.state.value = realtime.state.value.copy(generation = 2)
        model.viewport(TimelineAnchor(2, 0), 3, true); model.enterRoom(); model.send()
        assertEquals(0, api.reads); assertEquals(0, api.sends); assertEquals(selections, realtime.selections.size)
        runCurrent(); assertEquals("", model.state.value.draft); assertTrue(model.state.value.messages.isEmpty())
        api.session.value = api.session.value.copy(generation = 3); runCurrent()
        realtime.state.value = realtime.state.value.copy(generation = 3)
        model.open(42, interacted = true); runCurrent()
        model.viewport(null, 3, true) // Queue a read before the observer can see the NEXT transition.
        api.session.value = api.session.value.copy(generation = 4)
        runCurrent(); assertEquals(0, api.reads)
    }

    @Test fun expiredOrInactivePresenceBlocksWritesUntilExplicitSuccessfulReentry() = runTest(dispatcher) {
        open()
        for (phase in listOf(PresencePhase.EXPIRED, PresencePhase.NONE)) {
            realtime.state.value = realtime.state.value.copy(presencePhase = phase); realtime.acknowledgeSelection = false
            val reads = api.reads; val sends = api.sends; val newest = model.state.value.messages.last().id
            model.updateDraft("After reentry"); model.send(); model.viewport(null, newest, true); runCurrent()
            assertEquals(reads, api.reads); assertEquals(sends, api.sends)
            model.enterRoom(); runCurrent(); model.send(); model.viewport(null, newest, true); runCurrent()
            assertEquals(reads, api.reads); assertEquals(sends, api.sends)
            assertTrue(realtime.selections.last().third)
            realtime.state.value = realtime.state.value.copy(presencePhase = PresencePhase.ACTIVE)
            model.viewport(null, newest, true); runCurrent(); model.send(); runCurrent()
            assertEquals(reads + 1, api.reads); assertEquals(sends + 1, api.sends)
        }
    }

    @Test fun failedRevisionDoesNotSpinButNewInvalidationRetries() = runTest(dispatcher) {
        open(); api.windowFailure = ApiFailure(FailureKind.HTTP, 429, "rate_limited")
        model.invalidate(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, api.windows.size); assertTrue(model.state.value.stale)
        api.windowFailure = null; model.invalidate(); runCurrent()
        assertEquals(2, api.windows.size); assertFalse(model.state.value.stale); assertNull(model.state.value.syncError)
    }

    @Test fun lateWindowsCannotRepopulateAnotherRoomOrSignedOutAccount() = runTest(dispatcher) {
        open(); val oldGate = CompletableDeferred<Unit>(); api.windowGate = oldGate
        model.invalidate(); runCurrent(); model.open(43, interacted = true); runCurrent()
        oldGate.complete(Unit); runCurrent()
        assertEquals(43L, model.state.value.roomId); assertTrue(model.state.value.messages.all { it.roomId == 43L })
        val nextGate = CompletableDeferred<Unit>(); api.windowGate = nextGate
        model.invalidate(); runCurrent(); model.updateDraft("Private draft")
        api.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT); runCurrent()
        nextGate.complete(Unit); runCurrent()
        assertNull(model.state.value.roomId); assertTrue(model.state.value.messages.isEmpty())
        assertEquals("", model.state.value.draft); assertNull(model.state.value.syncError)
    }

    private data class WindowCall(val room: Long, val lower: Long, val upper: Long?)
    private class Conversations : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        var rows = listOf(message(2), message(3))
        var firstRaw: Long? = null
        val windows = mutableListOf<WindowCall>()
        var sends = 0; var reads = 0; var cancelledReads = 0
        var readResult: Long? = null
        var sendGate: CompletableDeferred<Unit>? = null; var readGate: CompletableDeferred<Unit>? = null
        var windowGate: CompletableDeferred<Unit>? = null
        var sendFailure: ApiFailure? = null; var readFailure: ApiFailure? = null; var windowFailure: ApiFailure? = null
        var answer: ((Long, Long?) -> WindowSnapshot)? = null
        private fun rows(room: Long) = if (room == 42L) rows else listOf(message(20, room), message(21, room))
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(roomId, "lounge", "Lounge", "", "public", 0, 0,
            7, "member", false, "none", 2, 1), ChatPolicy(emptySet(), null, DisplayPreferences()))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            val page = rows(roomId).filter { beforeId == null || it.id < beforeId }.takeLast(limit)
            return MessagePage(page, if (beforeId == null) firstRaw ?: page.firstOrNull()?.id else page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun window(roomId: Long, lowerInclusive: Long, upperInclusive: Long?): WindowSnapshot {
            windows += WindowCall(roomId, lowerInclusive, upperInclusive)
            val result = answer?.invoke(lowerInclusive, upperInclusive) ?: WindowSnapshot(rows(roomId).filter {
                it.id >= lowerInclusive && (upperInclusive == null || it.id <= upperInclusive) }, true, lowerInclusive, true)
            windowGate?.let { withContext(NonCancellable) { it.await() } }
            windowFailure?.let { throw it }; return result
        }
        override suspend fun pings(roomId: Long) = listOf(RoomPing(9, roomId, MessagePerson(8, "Other"),
            MessagePerson(7, "Viewer"), "Private ping", "2026-10-20T12:00:00Z"))
        override suspend fun send(roomId: Long, body: String): SendResult {
            sends++; val acknowledged = message((rows(roomId).lastOrNull()?.id ?: 0) + 1, roomId, 7)
            sendGate?.let { withContext(NonCancellable) { it.await() } }
            sendFailure?.let { throw it }
            if (roomId == 42L) rows = (rows + acknowledged).distinctBy { it.id }.sortedBy { it.id }
            return SendResult.Message(acknowledged)
        }
        override suspend fun read(roomId: Long, messageId: Long): Long {
            reads++
            try { readGate?.await(); readFailure?.let { throw it }; return readResult ?: messageId }
            catch (cancelled: CancellationException) { cancelledReads++; throw cancelled }
        }
    }
    private class Connection : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(phase = RealtimePhase.LIVE, generation = 1))
        override val events = MutableSharedFlow<RealtimeEvent>()
        val selections = mutableListOf<Triple<Long?, Boolean, Boolean>>()
        val activity = mutableListOf<Long>()
        var acknowledgeSelection = true
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) {
            selections += Triple(roomId, eligible, interacted)
            state.value = state.value.copy(selectedRoomId = roomId, presencePhase = when {
                !eligible -> PresencePhase.NONE
                interacted && acknowledgeSelection -> PresencePhase.ACTIVE
                else -> state.value.presencePhase
            })
        }
        override fun activity(roomId: Long) { activity += roomId }
        override fun reenter(roomId: Long) = selectRoom(roomId, true, true)
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }
    companion object {
        private fun message(id: Long, room: Long = 42, sender: Long = 8) = RoomMessage(id, room, sender, "Fixture user", "text", "Private fixture $id", false,
            "2026-10-20T12:00:00Z")
    }
}
