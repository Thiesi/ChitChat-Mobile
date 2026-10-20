package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.conversation.ConversationOperations
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.conversation.MessagePage
import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.conversation.RoomMute
import io.github.thiesi.chitchat.core.conversation.RoomPing
import io.github.thiesi.chitchat.core.conversation.SendResult
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Conversations()
    private lateinit var model: ConversationViewModel
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = ConversationViewModel(fake) }
    @After fun tearDown() { model.cancel(); fake.gate?.complete(Unit); Dispatchers.resetMain() }
    private fun TestScope.open() { model.foreground(); model.open(42); runCurrent() }

    @Test fun loadingNeverAcknowledgesWithoutActualLiveEdgeVisibility() = runTest(dispatcher) {
        model.open(42); advanceUntilIdle()
        model.viewport(null, 3, true); runCurrent(); assertEquals(0, fake.reads)
        model.foreground(); advanceUntilIdle()
        model.viewport(null, null, true); model.viewport(null, 2, true); model.viewport(null, 3, false)
        runCurrent(); assertEquals(0, fake.reads)
        model.viewport(null, 3, true); advanceUntilIdle(); assertEquals(1, fake.reads)
        model.background(); model.viewport(null, 3, true); advanceUntilIdle(); assertEquals(1, fake.reads)
    }

    @Test fun actualReadCursorIsMonotonicAndInitialUnreadDividerDoesNotMove() = runTest(dispatcher) {
        open(); val divider = model.state.value.unreadDividerId
        fake.readResult = 10
        model.viewport(null, 3, true); advanceUntilIdle()
        assertEquals(10L, model.state.value.readCursor); assertEquals(2L, divider)
        model.refresh(); advanceUntilIdle()
        assertEquals(divider, model.state.value.unreadDividerId)
        model.viewport(null, 3, true); advanceUntilIdle(); assertEquals(1, fake.reads)
    }

    @Test fun prependPreservesVisibleIdAndPixelOffset() = runTest(dispatcher) {
        fake.latest = listOf(message(50), message(51)); fake.older = listOf(message(48), message(49))
        open(); model.viewport(TimelineAnchor(50, 17), null, false)
        model.older(); advanceUntilIdle()
        assertEquals(listOf(48L, 49L, 50L, 51L), model.state.value.messages.map { it.id })
        assertEquals(50L, model.state.value.anchor?.id); assertEquals(17, model.state.value.anchor?.offset)
        assertEquals(listOf<Long?>(null, 50), fake.before)
    }

    @Test fun ordinaryRefreshPreservesAvailableAnchorButExplicitLatestScrolls() = runTest(dispatcher) {
        open(); model.viewport(TimelineAnchor(2, 12), null, false)
        val scroll = model.state.value.scrollRequest
        model.refresh(); advanceUntilIdle()
        assertEquals(scroll, model.state.value.scrollRequest); assertEquals(2L, model.state.value.anchor?.id)
        assertEquals(12, model.state.value.anchor?.offset)
        model.latest(); advanceUntilIdle(); assertTrue(model.state.value.scrollRequest > scroll)
        model.viewport(TimelineAnchor(1, 5), null, false); model.refresh(); advanceUntilIdle()
        assertEquals("latest_reset", model.state.value.notice)
    }

    @Test fun readFailureDoesNotLoopAndHasAnExplicitRetry() = runTest(dispatcher) {
        open(); fake.readFailure = ApiFailure(FailureKind.NETWORK)
        model.viewport(null, 3, true); advanceUntilIdle()
        assertNotNull(model.state.value.readError)
        model.viewport(null, 3, true); advanceUntilIdle(); assertEquals(1, fake.reads)
        fake.readFailure = null; model.retryRead(); advanceUntilIdle()
        assertEquals(2, fake.reads); assertNull(model.state.value.readError)
    }

    @Test fun readCsrfFailurePreservesPrivateHistoryAndRetriesOnlyExplicitlyAfterSessionRefresh() = runTest(dispatcher) {
        fake.visibility = "private"; fake.pingList = listOf(ping(500)); open()
        model.updateDraft("Private unsent fixture")
        val context = model.state.value.context
        fake.readFailure = ApiFailure(FailureKind.HTTP, 403, "csrf_failed")
        model.viewport(null, 3, true); advanceUntilIdle()
        assertTrue(fake.session.value.refreshRequired)
        assertSame(context, model.state.value.context); assertTrue(model.state.value.loaded)
        assertEquals(listOf(2L, 3L), model.state.value.messages.map { it.id })
        assertEquals(500L, model.state.value.pings.single().id)
        assertEquals("Private unsent fixture", model.state.value.draft)
        assertEquals("csrf_failed", model.state.value.readError?.code); assertNull(model.state.value.error)
        model.viewport(null, 3, true); advanceUntilIdle(); assertEquals(1, fake.reads)
        fake.readFailure = null
        fake.session.value = fake.session.value.copy(busy = true); runCurrent()
        fake.session.value = fake.session.value.copy(busy = false, refreshRequired = false); advanceUntilIdle()
        assertEquals(1, fake.reads)
        model.retryRead(); advanceUntilIdle()
        assertEquals(2, fake.reads); assertEquals(3L, model.state.value.readCursor)
        assertNull(model.state.value.readError); assertEquals("Private unsent fixture", model.state.value.draft)
    }

    @Test fun genuineReadPermissionLossStillClearsRoomContent() = runTest(dispatcher) {
        fake.visibility = "private"; fake.pingList = listOf(ping(500))
        for (status in listOf(403, 404)) {
            fake.readFailure = null; open()
            fake.readFailure = ApiFailure(FailureKind.HTTP, status, "room_forbidden")
            model.viewport(null, 3, true); advanceUntilIdle()
            assertNull(model.state.value.context); assertTrue(model.state.value.messages.isEmpty())
            assertTrue(model.state.value.pings.isEmpty()); assertFalse(model.state.value.loaded)
            assertEquals(status, model.state.value.error?.status)
        }
    }

    @Test fun boundedOldWindowPrunesNewestAndSendReloadsRatherThanStitching() = runTest(dispatcher) {
        fake.latest = (101L..600).map(::message); fake.older = (1L..100).map(::message)
        open(); model.older(); advanceUntilIdle()
        assertEquals(500, model.state.value.messages.size); assertEquals(500L, model.state.value.messages.last().id)
        assertFalse(model.state.value.latestWindow)
        model.viewport(null, 500, true); runCurrent(); assertEquals(0, fake.reads)
        fake.result = SendResult.Message(message(601)); model.updateDraft("New fixture"); model.send(); advanceUntilIdle()
        assertTrue(model.state.value.latestWindow); assertEquals(601L, model.state.value.messages.last().id)
        assertEquals(3, fake.before.size)
    }

    @Test fun successfulSendClearsOnlyAcknowledgedDraftAndScrollsToOwnMessage() = runTest(dispatcher) {
        open(); val scroll = model.state.value.scrollRequest
        model.updateDraft("Multiline\nfixture"); model.send(); advanceUntilIdle()
        assertEquals("Multiline\nfixture", fake.lastBody); assertEquals("", model.state.value.draft)
        assertEquals(1, fake.sends); assertEquals(4L, model.state.value.messages.last().id)
        assertTrue(model.state.value.scrollRequest > scroll)
    }

    @Test fun pingIsPrivateAndNeverInsertedAsMessageOrReadId() = runTest(dispatcher) {
        fake.pingList = listOf(ping(499)); open(); fake.result = SendResult.Ping(ping(500))
        model.updateDraft("/ping Bob private"); model.send(); advanceUntilIdle()
        assertEquals(listOf(2L, 3L), model.state.value.messages.map { it.id })
        assertEquals(listOf(499L, 500L), model.state.value.pings.map { it.id }); assertEquals("ping_sent", model.state.value.notice)
        model.viewport(null, 3, true); advanceUntilIdle(); assertEquals(3L, fake.lastRead)
    }

    @Test fun ambiguitySurvivesRefreshUntilExplicitDuplicateRiskChoice() = runTest(dispatcher) {
        open(); fake.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        model.updateDraft("Possibly sent"); model.send(); advanceUntilIdle()
        assertTrue(model.state.value.uncertainSend); assertEquals("Possibly sent", model.state.value.draft)
        model.acknowledgeDuplicateRisk(); model.send(); advanceUntilIdle(); assertEquals(1, fake.sends)
        fake.sendFailure = null; model.refresh(); advanceUntilIdle()
        assertTrue(model.state.value.uncertainSend); assertTrue(model.state.value.reconciledAfterUncertain)
        model.send(); advanceUntilIdle(); assertEquals(1, fake.sends)
        model.acknowledgeDuplicateRisk(); assertEquals(1, fake.sends)
        model.send(); advanceUntilIdle(); assertEquals(2, fake.sends)
    }

    @Test fun malformedOrServerFailureSendDoesNotReplay() = runTest(dispatcher) {
        open()
        for (failure in listOf(ApiFailure(FailureKind.INCOMPATIBLE), ApiFailure(FailureKind.HTTP, 503))) {
            fake.sendFailure = failure; model.updateDraft("Fixture"); model.send(); advanceUntilIdle()
            assertTrue(model.state.value.uncertainSend)
            fake.sendFailure = null; model.refresh(); advanceUntilIdle(); model.acknowledgeDuplicateRisk()
        }
        assertEquals(2, fake.sends)
    }

    @Test fun cancelledSendPreservesDraftAndIgnoresLateSuccess() = runTest(dispatcher) {
        open(); fake.gate = CompletableDeferred()
        model.updateDraft("Keep fixture"); model.send(); runCurrent(); model.background()
        fake.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("Keep fixture", model.state.value.draft); assertTrue(model.state.value.uncertainSend)
        assertEquals(listOf(2L, 3L), model.state.value.messages.map { it.id })
    }

    @Test fun roomChangeRejectsLateResponseAndDoesNotCarryDraft() = runTest(dispatcher) {
        open(); fake.gate = CompletableDeferred(); val pending = fake.gate!!
        model.updateDraft("Old room"); model.send(); runCurrent()
        fake.gate = null; model.open(43); runCurrent(); pending.complete(Unit); advanceUntilIdle()
        assertEquals(43L, model.state.value.roomId); assertEquals("", model.state.value.draft)
        assertEquals(listOf(2L, 3L), model.state.value.messages.map { it.id })
    }

    @Test fun logoutScrubsContentPingsDraftAndRejectsLateErrors() = runTest(dispatcher) {
        open(); fake.gate = CompletableDeferred(); model.updateDraft("Private draft"); model.send(); runCurrent()
        fake.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT)
        runCurrent(); fake.sendFailure = ApiFailure(FailureKind.NETWORK); fake.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("", model.state.value.draft); assertTrue(model.state.value.messages.isEmpty())
        assertTrue(model.state.value.pings.isEmpty()); assertNull(model.state.value.error)
    }

    @Test fun authoritativeHistoryDenialClearsContentButKeepsUnsentDraft() = runTest(dispatcher) {
        for (status in listOf(403, 404)) {
            fake.historyFailure = null; open(); model.updateDraft("Keep draft")
            fake.historyFailure = ApiFailure(FailureKind.HTTP, status, "room_forbidden")
            model.refresh(); advanceUntilIdle()
            assertTrue(model.state.value.messages.isEmpty()); assertNull(model.state.value.context)
            assertEquals("Keep draft", model.state.value.draft); assertEquals(status, model.state.value.error?.status)
        }
    }

    @Test fun rateLimitAndRefreshRequirementsPreventPosts() = runTest(dispatcher) {
        open(); model.updateDraft("Fixture")
        fake.session.value = fake.session.value.copy(retryAtMillis = System.currentTimeMillis() + 60_000)
        model.send(); advanceUntilIdle(); assertEquals(0, fake.sends)
        fake.session.value = fake.session.value.copy(retryAtMillis = 0, refreshRequired = true)
        model.send(); advanceUntilIdle(); assertEquals(0, fake.sends)
    }

    @Test fun muteNonmemberAndReadonlyGuestCannotPost() = runTest(dispatcher) {
        fake.mute = RoomMute(1, "room", 42, null, "Fixture reason"); open()
        model.updateDraft("Fixture"); model.send(); advanceUntilIdle(); assertEquals(0, fake.sends)
        fake.mute = null; fake.role = null; model.refresh(); advanceUntilIdle(); model.send(); advanceUntilIdle()
        assertEquals(0, fake.sends)
        fake.role = "member"; fake.guestAccess = "read"
        fake.session.value = fake.session.value.copy(user = SessionUser(7, "Guest", listOf("member"), 1, true))
        model.refresh(); advanceUntilIdle(); model.send(); advanceUntilIdle(); assertEquals(0, fake.sends)
    }

    @Test fun ignoredSenderMessagesAreRetainedForExplicitRevealButPingsAreHidden() = runTest(dispatcher) {
        fake.ignored = setOf(8); fake.pingList = listOf(ping(500)); open()
        assertEquals(2, model.state.value.messages.size); assertTrue(model.state.value.pings.isEmpty())
        model.reveal(2); assertTrue(2L in model.state.value.revealed)
        model.background(); assertTrue(model.state.value.revealed.isEmpty())
    }

    @Test fun invalidCommandsAndLengthNeverPost() = runTest(dispatcher) {
        open()
        for (body in listOf("/me", "/ping", "/unknown fixture", "x".repeat(4001))) {
            model.updateDraft(body); model.send(); advanceUntilIdle(); assertNotNull(model.state.value.validation)
        }
        assertEquals(0, fake.sends)
    }

    @Test fun pingTarget404KeepsAuthorizedHistoryAndDraftWithoutAmbiguity() = runTest(dispatcher) {
        open(); fake.sendFailure = ApiFailure(FailureKind.HTTP, 404, "ping_target_not_found")
        model.updateDraft("/ping Missing fixture"); model.send(); advanceUntilIdle()
        assertEquals(2, model.state.value.messages.size); assertNotNull(model.state.value.context)
        assertFalse(model.state.value.uncertainSend); assertEquals("/ping Missing fixture", model.state.value.draft)
        assertEquals(1, fake.sends)
    }

    private class Conversations : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        var latest = listOf(message(2), message(3)); var older = listOf(message(1))
        var result: SendResult = SendResult.Message(message(4))
        var sendFailure: ApiFailure? = null; var historyFailure: ApiFailure? = null
        var readFailure: ApiFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        var sends = 0; var reads = 0; var lastRead = 0L; var readResult: Long? = null; var lastBody = ""
        val before = mutableListOf<Long?>()
        var ignored = emptySet<Long>(); var mute: RoomMute? = null; var role: String? = "member"; var guestAccess = "none"
        var visibility = "public"
        var pingList = emptyList<RoomPing>()
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(roomId, "lounge", "Lounge", "", visibility, 0, 0, 7,
            role, false, guestAccess, 2, 1, mute), ChatPolicy(ignored, null, DisplayPreferences()))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            before += beforeId; historyFailure?.let { throw it }
            val messages = if (beforeId == null) latest else older
            return MessagePage(messages, messages.firstOrNull()?.id, beforeId == null)
        }
        override suspend fun pings(roomId: Long) = pingList
        override suspend fun send(roomId: Long, body: String): SendResult {
            sends++; lastBody = body; gate?.let { withContext(NonCancellable) { it.await() } }
            sendFailure?.let { throw it }
            // The server changes independently; a cancelled native response must still be rejected.
            (result as? SendResult.Message)?.let { latest = latest + it.message }
            return result
        }
        override suspend fun read(roomId: Long, messageId: Long): Long {
            reads++; lastRead = messageId; readFailure?.let {
                if (it.code == "csrf_failed") session.value = session.value.copy(refreshRequired = true)
                throw it
            }; return readResult ?: messageId
        }
    }
    companion object {
        private fun message(id: Long) = RoomMessage(id, 42, 8, "Bob", "text", "Fixture $id", false, "2026-10-20 12:00:00+00")
        private fun ping(id: Long) = RoomPing(id, 42, MessagePerson(8, "Bob"), MessagePerson(7, "Alice"), "Private fixture", "2026-10-20T12:00:00+00:00")
    }
}
