package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.mutations.AuthorMessageActions
import io.github.thiesi.chitchat.core.mutations.MessageMutationOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAuthorChangeTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Fixture()
    private lateinit var model: ConversationViewModel
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = ConversationViewModel(fake, mutations = fake) }
    @After fun tearDown() { model.cancel(); fake.gate?.complete(Unit); fake.readGate?.complete(Unit); Dispatchers.resetMain() }
    private fun TestScope.open(room: Long = 42) { model.foreground(); model.open(room); runCurrent() }
    private fun own() = model.state.value.messages.first()
    private fun begin(kind: AuthorChangeKind = AuthorChangeKind.EDIT): RoomAuthorRequest {
        assertTrue(model.beginAuthor(fake.session.value, checkNotNull(model.state.value.roomId), own(), kind))
        return checkNotNull(model.state.value.authorDialog).request
    }

    @Test fun unknownHintsOtherAuthorsAndOldInstancesCannotOpenAuthorControls() = runTest(dispatcher) {
        open(); val original = own()
        assertFalse(model.canBeginAuthor(fake.session.value, 42, model.state.value.messages.last(), AuthorChangeKind.EDIT))
        fake.rows[42] = listOf(message(10, hints = null)); model.refresh(); runCurrent()
        assertFalse(model.canBeginAuthor(fake.session.value, 42, own(), AuthorChangeKind.DELETE))
        assertFalse(model.beginAuthor(fake.session.value, 42, original, AuthorChangeKind.EDIT))
        assertNull(model.state.value.authorDialog); assertEquals(0, fake.writes)
    }

    @Test fun muteBlocksEditButNotPermittedOwnDeletion() = runTest(dispatcher) {
        fake.mute = RoomMute(1, "room", 42, null, "Fixture"); open()
        assertFalse(model.canBeginAuthor(fake.session.value, 42, own(), AuthorChangeKind.EDIT))
        assertTrue(model.canBeginAuthor(fake.session.value, 42, own(), AuthorChangeKind.DELETE))
    }

    @Test fun modalPreservesComposerReplyAndSuppressesReadAndSend() = runTest(dispatcher) {
        open(); model.updateDraft("Ordinary draft")
        assertTrue(model.replyTo(fake.session.value, 42, own())); val reply = model.state.value.composerReply
        val request = begin(); model.updateAuthorBody(request, "Edited draft")
        model.updateDraft("Stale composer callback"); model.send(); model.viewport(null, 11, true); runCurrent()
        assertEquals("Ordinary draft", model.state.value.draft); assertSame(reply, model.state.value.composerReply)
        assertEquals("Edited draft", model.state.value.authorDialog?.body)
        assertEquals(0, fake.sends); assertEquals(0, fake.reads)
        model.dismissAuthor(request); assertNull(model.state.value.authorDialog)
        assertEquals("Ordinary draft", model.state.value.draft); assertFalse(model.state.value.atLatest)
    }

    @Test fun editIsLiteralAndAcknowledgedBeforeCanonicalRefreshWithoutAlteringDraft() = runTest(dispatcher) {
        open(); model.updateDraft("Kept draft"); val request = begin()
        model.updateAuthorBody(request, "/ping Bob\nliteral edit")
        model.submitAuthor(request); runCurrent()
        assertEquals(1, fake.writes); assertEquals("/ping Bob\nliteral edit", fake.lastBody)
        assertEquals("/ping Bob\nliteral edit", own().body)
        assertEquals("Kept draft", model.state.value.draft); assertEquals("author_edited", model.state.value.notice)
        assertNull(model.state.value.authorDialog); assertFalse(model.state.value.uncertainSend)
        assertNull(model.state.value.authorUnknown); assertFalse(model.state.value.busy)
    }

    @Test fun emptyAndOverlongEditsNeverCallMutationTransport() = runTest(dispatcher) {
        open(); val request = begin()
        for (body in listOf(" \n\u0000", "x".repeat(4001))) {
            model.updateAuthorBody(request, body); model.submitAuthor(request); runCurrent()
            assertNotNull(model.state.value.authorDialog?.validation)
        }
        assertEquals(0, fake.writes)
    }

    @Test fun canonicalReplacementDisablesOldEditAndDismissedRequestCannotAffectSuccessor() = runTest(dispatcher) {
        open(); val old = begin(); model.updateAuthorBody(old, "Old draft")
        fake.rows[42] = listOf(message(10, body = "Remote edit")); model.refresh(); runCurrent()
        assertFalse(model.authorAvailable(old)); model.submitAuthor(old); assertEquals(0, fake.writes)
        model.dismissAuthor(old); val fresh = begin()
        model.updateAuthorBody(old, "Late text"); model.dismissAuthor(old); model.submitAuthor(old)
        assertSame(fresh, model.state.value.authorDialog?.request)
        assertEquals("Remote edit", model.state.value.authorDialog?.body); assertEquals(0, fake.writes)
    }

    @Test fun successfulDeletionDropsCanonicalBodyAndNeverBecomesUncertainSend() = runTest(dispatcher) {
        open(); model.updateDraft("Unsent"); model.submitAuthor(begin(AuthorChangeKind.DELETE)); runCurrent()
        assertTrue(own().deleted); assertNull(own().body); assertNull(own().attachment); assertNull(own().replyTo)
        assertEquals("Unsent", model.state.value.draft); assertEquals("author_deleted", model.state.value.notice)
        assertFalse(model.state.value.uncertainSend); assertNull(model.state.value.authorUnknown)
    }

    @Test fun failedPostMayAlreadyHaveCommittedAndRequiresExplicitGetThenFreshChoice() = runTest(dispatcher) {
        open(); fake.writeFailure = ApiFailure(FailureKind.HTTP, 500, "metadata_failed")
        val old = begin(); model.updateAuthorBody(old, "Possibly committed"); model.submitAuthor(old); runCurrent()
        val pending = checkNotNull(model.state.value.authorUnknown)
        assertEquals(10L, pending.messageId); assertFalse(model.state.value.uncertainSend)
        assertEquals("Possibly committed", model.state.value.authorDialog?.body); assertEquals(1, fake.writes)
        fake.writeFailure = null; model.refresh(); runCurrent()
        assertSame(pending, model.state.value.authorUnknown) // Passive/general refresh is not author consent.
        assertFalse(model.canBeginAuthor(fake.session.value, 42, own(), AuthorChangeKind.EDIT))
        model.checkAuthorHistory(fake.session.value, pending); runCurrent()
        assertNull(model.state.value.authorUnknown); assertEquals(1, fake.writes)
        assertTrue(model.state.value.authorDialog?.checked == true)
        model.submitAuthor(old); assertEquals(1, fake.writes)
        assertEquals("Possibly committed", model.state.value.messages.first().body)
        assertTrue(model.reviewAuthorProposal(old))
        assertNotSame(old, model.state.value.authorDialog?.request); assertEquals(1, fake.writes)
    }

    @Test fun acknowledgedWriteFollowedByFailedGetStaysConfirmed() = runTest(dispatcher) {
        open(); fake.failGetAfterWrite = true; val request = begin()
        model.updateAuthorBody(request, "Confirmed edit"); model.submitAuthor(request); runCurrent()
        assertEquals("Confirmed edit", own().body); assertEquals("author_edited", model.state.value.notice)
        assertTrue(model.state.value.stale); assertNotNull(model.state.value.error)
        assertNull(model.state.value.authorUnknown); assertFalse(model.state.value.uncertainSend)
        assertEquals(1, fake.writes); assertFalse(model.state.value.busy)
    }

    @Test fun navigationDuringWriteRetainsOnlyOriginalRoomUncertaintyAndRejectsLateResponse() = runTest(dispatcher) {
        open(); model.updateDraft("Room42 draft"); fake.gate = CompletableDeferred()
        val request = begin(); model.updateAuthorBody(request, "In flight"); model.submitAuthor(request); runCurrent()
        assertTrue(model.state.value.authorWriting)
        model.open(43); runCurrent()
        assertEquals(43L, model.state.value.roomId); assertNull(model.state.value.authorUnknown)
        assertNull(model.state.value.authorDialog); assertFalse(model.state.value.uncertainSend)
        fake.gate?.complete(Unit); runCurrent(); assertEquals(43L, own().roomId)
        model.open(42); runCurrent()
        assertEquals(10L, model.state.value.authorUnknown?.messageId)
        assertEquals("Room42 draft", model.state.value.draft); assertFalse(model.state.value.uncertainSend)
        assertEquals(1, fake.writes)
    }

    @Test fun retiredAccountCannotWriteOrRestoreEditorOrUnknownState() = runTest(dispatcher) {
        open(); val oldSession = fake.session.value; val request = begin(); fake.gate = CompletableDeferred()
        model.updateAuthorBody(request, "Account-bound edit")
        model.submitAuthor(request); runCurrent()
        fake.session.value = oldSession.copy(generation = 2, user = SessionUser(9, "Replacement", listOf("member"), 1, false))
        runCurrent(); assertNull(model.state.value.authorDialog); assertNull(model.state.value.authorUnknown)
        fake.gate?.complete(Unit); runCurrent()
        model.submitAuthor(request); assertEquals(1, fake.writes)
        assertTrue(model.state.value.messages.isEmpty()); assertNull(model.state.value.authorUnknown)
        assertFalse(model.beginAuthor(oldSession, 42, request.original, AuthorChangeKind.DELETE))
    }

    @Test fun explicitAuthorGetDoesNotGrantExistingUncertainSendConsent() = runTest(dispatcher) {
        open(); fake.writeFailure = ApiFailure(FailureKind.NETWORK); val request = begin()
        model.updateAuthorBody(request, "Unconfirmed edit"); model.submitAuthor(request); runCurrent()
        val pending = checkNotNull(model.state.value.authorUnknown)
        model.dismissAuthor(request) // Explicitly retire only the editor; the room warning remains.
        fake.writeFailure = null; fake.sendFailure = ApiFailure(FailureKind.NETWORK)
        model.refresh(); runCurrent(); model.updateDraft("Unconfirmed send"); model.send(); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertFalse(model.state.value.reconciledAfterUncertain)
        model.checkAuthorHistory(fake.session.value, pending); runCurrent()
        assertNull(model.state.value.authorUnknown); assertTrue(model.state.value.uncertainSend)
        assertFalse(model.state.value.reconciledAfterUncertain); assertEquals("Unconfirmed send", model.state.value.draft)
        assertEquals(1, fake.sends); assertEquals(1, fake.writes)
    }

    @Test fun failedExplicitGetDoesNotClearPendingChoiceOrReplayPost() = runTest(dispatcher) {
        open(); fake.writeFailure = ApiFailure(FailureKind.TIMEOUT); model.submitAuthor(begin(AuthorChangeKind.DELETE)); runCurrent()
        val pending = checkNotNull(model.state.value.authorUnknown)
        fake.failGetAfterWrite = true; fake.historyFailure = ApiFailure(FailureKind.NETWORK)
        model.checkAuthorHistory(fake.session.value, pending); runCurrent()
        assertSame(pending, model.state.value.authorUnknown); assertEquals(1, fake.writes)
        assertFalse(model.state.value.busy); assertFalse(model.state.value.uncertainSend)
    }

    @Test fun noMutationAdapterMeansNoProductionFacingFakeAuthorAction() = runTest(dispatcher) {
        val ordinary = ConversationViewModel(fake)
        ordinary.foreground(); ordinary.open(42); runCurrent()
        assertFalse(ordinary.canBeginAuthor(fake.session.value, 42, ordinary.state.value.messages.first(), AuthorChangeKind.EDIT))
        ordinary.cancel()
    }

    @Test fun unchangedEditDoesNotPostAndActualEmoteAttachmentTypesRemainEditable() = runTest(dispatcher) {
        open(); val unchanged = begin(); model.submitAuthor(unchanged); runCurrent()
        assertEquals("message_unchanged", model.state.value.authorDialog?.validation); assertEquals(0, fake.writes)
        model.dismissAuthor(unchanged)
        for (type in listOf("emote", "attachment")) {
            fake.rows[42] = listOf(message(10, type = type)); model.refresh(); runCurrent()
            val request = begin(); model.updateAuthorBody(request, "/me remains literal")
            model.submitAuthor(request); runCurrent()
            assertEquals(type, own().type); assertEquals("/me remains literal", own().body)
            assertNull(model.state.value.authorUnknown)
        }
        assertEquals(2, fake.writes)
    }

    @Test fun acknowledgedDeletionRedactsQuotedBodyBeforeAFailedFollowupGet() = runTest(dispatcher) {
        fake.rows[42] = listOf(message(10), message(11, sender = 8, quote = MessageReply("room", 10, true,
            ReplyMessage(10, MessagePerson(7, "Alice"), "text", "Original fixture", false))))
        open(); fake.failGetAfterWrite = true; model.submitAuthor(begin(AuthorChangeKind.DELETE)); runCurrent()
        val quote = model.state.value.messages.last().replyTo?.message
        assertEquals(1, fake.writes); assertTrue(own().deleted); assertTrue(quote?.deleted == true); assertNull(quote?.body)
        assertNull(model.state.value.authorUnknown); assertFalse(model.state.value.uncertainSend)
    }

    @Test fun acknowledgedEditUpdatesAvailableQuoteButNeverUpgradesUnavailableQuote() = runTest(dispatcher) {
        val available = MessageReply("room", 10, true, ReplyMessage(10, MessagePerson(7, "Alice"), "text", "Old", false))
        val unavailable = MessageReply("room", 10, false, null)
        val otherRoom = message(11, room = 43, quote = available)
        val rows = listOf(message(10), message(11, quote = available), message(12, quote = unavailable), otherRoom)
        val changed = applyRoomAuthorResult(rows, message(10, body = "Canonical edit"))
        assertEquals("Canonical edit", changed[1].replyTo?.message?.body)
        assertSame(unavailable, changed[2].replyTo); assertSame(otherRoom, changed[3])
    }

    @Test fun authorUnknownCapacityIsBoundedWithoutSilentEvictionOrPostReplay() = runTest(dispatcher) {
        fake.writeFailure = ApiFailure(FailureKind.NETWORK)
        for (room in 1L..100L) { open(room); model.submitAuthor(begin(AuthorChangeKind.DELETE)); runCurrent() }
        assertEquals(100, fake.writes); open(101)
        assertTrue(model.authorCapacityFull())
        assertFalse(model.beginAuthor(fake.session.value, 101, own(), AuthorChangeKind.DELETE))
        assertEquals(100, fake.writes); assertTrue(model.state.value.authorLimit)
        open(1); val original = checkNotNull(model.state.value.authorUnknown)
        model.checkAuthorHistory(fake.session.value, original); runCurrent()
        open(101); assertFalse(model.authorCapacityFull())
        assertTrue(model.beginAuthor(fake.session.value, 101, own(), AuthorChangeKind.DELETE)); assertEquals(100, fake.writes)
    }

    @Test fun authorSelectionWaitsForAnExistingReadInsteadOfCancellingItsPost() = runTest(dispatcher) {
        open(); fake.readGate = CompletableDeferred(); model.viewport(null, 11, true); runCurrent()
        assertTrue(model.state.value.reading)
        assertFalse(model.beginAuthor(fake.session.value, 42, own(), AuthorChangeKind.DELETE))
        assertEquals(0, fake.writes); fake.readGate?.complete(Unit); runCurrent()
        assertFalse(model.state.value.reading); assertNotNull(begin(AuthorChangeKind.DELETE))
        assertEquals(1, fake.reads); assertEquals(0, fake.writes)
    }

    @Test fun equivalentRefreshRetainsEditTextButUsesTheFreshCanonicalInstanceAndPermissions() = runTest(dispatcher) {
        open(); val request = begin(); val original = request.original; model.updateAuthorBody(request, "Kept private edit")
        val fresh = message(10, hints = AuthorMessageActions(false, true))
        fake.rows[42] = listOf(fresh); model.refresh(); runCurrent()
        assertNotSame(original, request.original); assertSame(fresh, request.original)
        assertEquals("Kept private edit", model.state.value.authorDialog?.body)
        assertTrue(model.authorTargetCurrent(request)); assertFalse(model.authorAvailable(request))
        model.submitAuthor(request); assertEquals(0, fake.writes)
    }

    @Test fun eventHintsCoalesceBehindAuthorPostWithoutCancellingOrReplayingIt() = runTest(dispatcher) {
        model.cancel(); model = ConversationViewModel(fake, Connection(), mutations = fake)
        open(); fake.gate = CompletableDeferred(); model.submitAuthor(begin(AuthorChangeKind.DELETE)); runCurrent()
        val histories = fake.histories
        repeat(50) { model.invalidate() }; runCurrent()
        assertEquals(histories, fake.histories); assertEquals(1, fake.writes); assertTrue(model.state.value.busy)
        fake.gate?.complete(Unit); runCurrent()
        assertEquals(histories + 1, fake.histories); assertEquals(1, fake.writes)
        assertFalse(model.state.value.busy); assertFalse(model.state.value.syncing); assertTrue(own().deleted)
    }

    @Test fun uncommittedFailedEditKeepsProposalThroughGetAndRequiresFreshReviewBeforeAnotherPost() = runTest(dispatcher) {
        open(); fake.commitBeforeFailure = false; fake.writeFailure = ApiFailure(FailureKind.NETWORK)
        val old = begin(); model.updateAuthorBody(old, "Uncommitted proposed text"); model.submitAuthor(old); runCurrent()
        val pending = checkNotNull(model.state.value.authorUnknown)
        assertEquals("Uncommitted proposed text", model.state.value.authorDialog?.body)
        fake.writeFailure = null; model.checkAuthorHistory(fake.session.value, pending); runCurrent()
        assertEquals("Original fixture", model.authorCurrentPreview(old))
        assertEquals("Uncommitted proposed text", model.state.value.authorDialog?.body)
        model.submitAuthor(old); assertEquals(1, fake.writes)
        assertTrue(model.reviewAuthorProposal(old)); val fresh = checkNotNull(model.state.value.authorDialog).request
        assertNotSame(old, fresh); model.submitAuthor(old); assertEquals(1, fake.writes)
        model.submitAuthor(fresh); runCurrent()
        assertEquals(2, fake.writes); assertEquals("Uncommitted proposed text", own().body)
        assertNull(model.state.value.authorUnknown); assertNull(model.state.value.authorDialog)
    }

    @Test fun closingUnknownEditorDropsProposalButKeepsIdAndNeverRestoresItFromGet() = runTest(dispatcher) {
        open(); fake.writeFailure = ApiFailure(FailureKind.NETWORK)
        val request = begin(); model.updateAuthorBody(request, "Private proposed edit"); model.submitAuthor(request); runCurrent()
        val pending = checkNotNull(model.state.value.authorUnknown)
        model.dismissAuthor(request); assertNull(model.state.value.authorDialog); assertSame(pending, model.state.value.authorUnknown)
        model.checkAuthorHistory(fake.session.value, pending); runCurrent()
        assertNull(model.state.value.authorDialog); assertNull(model.state.value.authorUnknown); assertEquals(1, fake.writes)
    }

    private class Fixture : ConversationOperations, MessageMutationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Alice", listOf("member"), 1, false)))
        val rows = mutableMapOf<Long, List<RoomMessage>>()
        var mute: RoomMute? = null
        var writes = 0; var sends = 0; var reads = 0; var histories = 0; var lastBody: String? = null
        var writeFailure: ApiFailure? = null; var historyFailure: ApiFailure? = null; var sendFailure: ApiFailure? = null
        var failGetAfterWrite = false
        var commitBeforeFailure = true
        var gate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(roomId, "fixture", "Fixture", "", "private", 0, 0, 7,
            "member", false, "none", 0, 0, mute), ChatPolicy(emptySet(), null, DisplayPreferences()))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            histories++
            historyFailure?.let { throw it }
            val candidates = rows.getOrPut(roomId) { listOf(message(10, roomId), message(11, roomId, sender = 8)) }
                .filter { beforeId == null || it.id < beforeId }
            val selected = candidates.takeLast(limit)
            return MessagePage(selected, selected.firstOrNull()?.id, candidates.size >= limit)
        }
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
            sends++; sendFailure?.let { throw it }; return SendResult.Message(message(12, roomId, body = body))
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { reads++; readGate?.await(); return messageId }
        override suspend fun editRoom(expected: SessionState, original: RoomMessage, body: String): RoomMessage = write(original, body, false)
        override suspend fun deleteRoom(expected: SessionState, original: RoomMessage): RoomMessage = write(original, "", true)
        private suspend fun write(original: RoomMessage, body: String, deleted: Boolean): RoomMessage {
            writes++; lastBody = body; gate?.let { withContext(NonCancellable) { it.await() } }
            if (!commitBeforeFailure) writeFailure?.let { throw it }
            val result = message(original.id, original.roomId, body = body, deleted = deleted, type = original.type)
            rows[original.roomId] = rows[original.roomId].orEmpty().map { if (it.id == result.id) result else it }
            if (failGetAfterWrite) historyFailure = ApiFailure(FailureKind.NETWORK)
            writeFailure?.let { throw it }
            return result
        }
        override suspend fun editDirect(expected: SessionState, original: DirectMessage, body: String): DirectMessage = error("No DM consumer")
        override suspend fun deleteDirect(expected: SessionState, original: DirectMessage): DirectMessage = error("No DM consumer")
    }
    private class Connection : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(phase = RealtimePhase.LIVE, generation = 1,
            selectedRoomId = 42, presencePhase = PresencePhase.ACTIVE))
        override val events = MutableSharedFlow<RealtimeEvent>()
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }
    companion object {
        private fun message(id: Long, room: Long = 42, sender: Long = 7, body: String = "Original fixture", deleted: Boolean = false,
            hints: AuthorMessageActions? = AuthorMessageActions(true, true), type: String = "text", quote: MessageReply? = null) = RoomMessage(id, room, sender, "Fixture", type,
            body.takeUnless { deleted }, deleted, "2026-10-22 12:00:00+00", replyTo = quote, authorActions = hints)
    }
}
