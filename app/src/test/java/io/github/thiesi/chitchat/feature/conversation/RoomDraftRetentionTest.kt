package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDraftRetentionTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Rooms()
    private lateinit var model: ConversationViewModel
    @Before fun start() { Dispatchers.setMain(dispatcher); model = ConversationViewModel(api) }
    @After fun stop() { model.cancel(); api.gate?.complete(Unit); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun switchingRoomsRestoresTheirOwnUnsentText() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Room A fixture")
        model.open(43); runCurrent(); model.updateDraft("Room B fixture")
        model.open(42); runCurrent()
        assertEquals("Room A fixture", model.state.value.draft)
        model.open(43); runCurrent(); assertEquals("Room B fixture", model.state.value.draft)
        assertEquals(0, api.sends)
    }
    @Test fun returningToUnknownDeliveryDoesNotLoseTextOrGrantResendConsent() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Unconfirmed fixture")
        api.failure = ApiFailure(FailureKind.TIMEOUT); model.send(); runCurrent()
        assertTrue(model.state.value.uncertainSend)
        model.open(43); runCurrent(); model.open(42); runCurrent()
        assertTrue("The prior room's unconfirmed delivery must remain guarded", model.state.value.uncertainSend)
        assertEquals("Unconfirmed fixture", model.state.value.draft)
        assertFalse(model.state.value.reconciledAfterUncertain)
        assertFalse(model.canSend()); assertEquals(1, api.sends)
    }

    @Test fun explicitHistoryAndDuplicateChoiceAreRequiredAfterRestoringUnknownDraft() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Unconfirmed fixture")
        api.failure = ApiFailure(FailureKind.TIMEOUT); model.send(); runCurrent()
        model.open(43); runCurrent(); model.open(42); runCurrent()
        model.acknowledgeDuplicateRisk(); assertTrue(model.state.value.uncertainSend)
        api.failure = null; model.refresh(); runCurrent()
        assertTrue(model.state.value.reconciledAfterUncertain); assertFalse(model.canSend())
        model.acknowledgeDuplicateRisk(); assertTrue(model.canSend())
        model.send(); runCurrent(); assertEquals(2, api.sends)
        assertFalse(42L in model.drafts.value); assertEquals("", model.state.value.draft)
    }
    @Test fun lateCancelledSendCannotClearTheNewRoomDraftOrForgetUnknownDelivery() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Room A fixture")
        api.gate = CompletableDeferred(); model.send(); runCurrent(); assertEquals(1, api.sends)
        model.open(43); runCurrent(); model.updateDraft("Room B fixture")
        assertTrue(model.drafts.value.getValue(42).uncertain)
        api.gate!!.complete(Unit); runCurrent()
        assertEquals("Room B fixture", model.state.value.draft)
        model.open(42); runCurrent()
        assertEquals("Room A fixture", model.state.value.draft); assertTrue(model.state.value.uncertainSend)
        assertFalse(model.state.value.reconciledAfterUncertain); assertEquals(1, api.sends)
    }
    @Test fun capacityNeverEvictsAndExplicitDiscardRestoresEditing() = runTest(dispatcher) {
        model.foreground()
        for (id in 42L..141) { model.open(id); runCurrent(); model.updateDraft("Draft $id fixture") }
        assertEquals(100, model.drafts.value.size)
        val original = model.drafts.value.getValue(42)
        model.open(142); runCurrent(); assertFalse(model.canEditDraft())
        assertFalse(model.updateDraft(api.session.value, 142, "Do not silently accept"))
        assertEquals("", model.state.value.draft); assertTrue(model.state.value.draftLimit)
        assertSame(original, model.drafts.value[42]); assertEquals(100, model.drafts.value.size)
        model.discardDraft(original); assertTrue(model.canEditDraft()); assertFalse(model.state.value.draftLimit)
        model.updateDraft("Room 142 fixture"); assertEquals(100, model.drafts.value.size)
        assertEquals("Draft 43 fixture", model.drafts.value.getValue(43).body); assertEquals(0, api.sends)
    }
    @Test fun staleEditDiscardAndOpenCannotMutateReplacementAccount() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Old account fixture")
        val oldSession = api.session.value; val oldDraft = model.drafts.value.getValue(42)
        api.session.value = oldSession.copy(generation = 2, user = SessionUser(9, "Second", listOf("member"), 1, false))
        assertFalse(oldDraft.matches(api.session.value)) // Independent UI scope fence before collector runs.
        runCurrent(); assertTrue(model.drafts.value.isEmpty())
        model.open(42); runCurrent(); model.updateDraft("New account fixture")
        assertFalse(model.updateDraft(oldSession, 42, "Stale input"))
        model.discardDraft(oldDraft); model.open(43, expected = oldSession); runCurrent()
        assertEquals(42L, model.state.value.roomId); assertEquals("New account fixture", model.state.value.draft)
        assertEquals(1, model.drafts.value.size); assertEquals(0, api.sends)
    }
    @Test fun staleInputCannotBleedIntoAnotherRoomInTheSameAccount() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Room A fixture")
        val expected = api.session.value
        model.open(43); runCurrent(); model.updateDraft("Room B fixture")
        assertFalse(model.updateDraft(expected, 42, "Old input callback"))
        assertEquals("Room B fixture", model.state.value.draft)
        assertEquals("Room A fixture", model.drafts.value.getValue(42).body)
    }
    @Test fun acknowledgedSendClearsOnlyItsOwnRoomSlot() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Room A fixture")
        model.open(43); runCurrent(); model.updateDraft("Room B fixture"); model.send(); runCurrent()
        assertFalse(43L in model.drafts.value); assertEquals("Room A fixture", model.drafts.value.getValue(42).body)
        assertEquals(1, api.sends); model.open(42); runCurrent(); assertEquals("Room A fixture", model.state.value.draft)
    }
    @Test fun unchangedRefreshDoesNotInvalidateAnExplicitDiscardIntent() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Discard fixture")
        val original = model.drafts.value.getValue(42)
        model.refresh(); runCurrent(); model.discardDraft(original)
        assertTrue(model.drafts.value.isEmpty()); assertEquals("", model.state.value.draft)
    }
    @Test fun passiveResumeNeverChecksUnknownDeliveryForTheUser() = runTest(dispatcher) {
        model.foreground(); model.open(42); runCurrent(); model.updateDraft("Unconfirmed fixture")
        api.failure = ApiFailure(FailureKind.TIMEOUT); model.send(); runCurrent()
        model.background(); model.foreground(); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertFalse(model.state.value.reconciledAfterUncertain)
        assertFalse(model.canSend()); assertEquals(1, api.sends)
    }

    private class Rooms : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        var sends = 0; var failure: ApiFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(roomId, "room$roomId", "Room $roomId", "",
            "public", 0, 0, 7, "member", false, "none", 0, 0, null), ChatPolicy(emptySet(), null, DisplayPreferences()))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            val rows = (2L..3).filter { beforeId == null || it < beforeId }.takeLast(limit)
                .map { RoomMessage(it, roomId, 8, "Peer", "text", "Fixture", false, "2026-10-20T12:00:00Z") }
            return MessagePage(rows, rows.firstOrNull()?.id, false)
        }
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long, body: String): SendResult {
            sends++; gate?.let { withContext(NonCancellable) { it.await() } }; failure?.let { throw it }
            return SendResult.Message(RoomMessage(4, roomId, 7, "Viewer", "text", body, false, "2026-10-20T12:00:00Z"))
        }
        override suspend fun read(roomId: Long, messageId: Long) = messageId
    }
}
