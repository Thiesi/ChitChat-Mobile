package io.github.thiesi.chitchat.feature.conversation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAttachmentReviewTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Rooms()
    private val wire = Uploads()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val store = ViewModelStore()
    private lateinit var model: ConversationViewModel
    private var entry = "entry-1"
    private var entryActive = true

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        model = ConversationViewModel(api, attachmentBudget = budget, uploads = wire)
        store.put("fixture", model)
    }
    @After fun stop() = runTest(dispatcher) {
        try {
            api.historyGate?.complete(Unit)
            val lifetime = model.viewModelScope.coroutineContext[Job]!!
            store.clear(); lifetime.join()
            assertEquals(0L, budget.reservedBytes)
        } finally { Dispatchers.resetMain() }
    }
    private fun open(room: Long = 42) {
        model.foreground(); model.open(room); dispatcher.scheduler.runCurrent()
    }
    private suspend fun unknown(caption: String = "caption", reply: Boolean = false): RoomAttachmentDraft {
        open()
        assertTrue(model.selectAttachment(api.session.value, 42, entry,
            PickedAttachmentRead("private-file", null) { ByteArrayInputStream("prepared".toByteArray()) },
            AttachmentPickSource.FILE) { entryActive })
        val ready = model.drafts.first { it[42]?.attachment?.phase == RoomAttachmentPhase.READY }.getValue(42).attachment!!
        dispatcher.scheduler.runCurrent()
        model.updateDraft(caption)
        if (reply) assertTrue(model.replyTo(api.session.value, 42, model.state.value.messages.first()))
        assertTrue(model.sendAttachment(api.session.value, 42, entry, ready) { entryActive })
        dispatcher.scheduler.runCurrent()
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls)
        return view()
    }
    private fun view() = model.drafts.value.getValue(42).attachment!!
    private fun review() = checkNotNull(model.state.value.attachmentReview)
    private fun check(original: RoomAttachmentDraft = view()): Boolean =
        model.checkAttachmentHistory(api.session.value, 42, entry, original) { entryActive }
    private fun accept(original: RoomAttachmentReview = review()): Boolean =
        model.acceptAttachmentDuplicateRisk(api.session.value, 42, entry, original) { entryActive }
    private fun confirm(original: RoomAttachmentReview = review(), evidence: RoomAttachmentEvidence = original.evidence.single()): Boolean =
        model.confirmAttachmentEvidence(api.session.value, 42, entry, original, evidence) { entryActive }
    private fun row(id: Long = 10, sender: Long? = 7, room: Long = 42, digest: String? = view().upload!!.sha256,
        time: String = Instant.ofEpochMilli(view().upload!!.startedAtMillis + 1000).toString(), deleted: Boolean = false) =
        RoomMessage(id, room, sender, "Viewer", "attachment", "caption", deleted, time,
            digest?.let { MessageAttachment(id, "private-file", "application/octet-stream", 8, it, false) })

    @Test fun ordinaryHistoryAndForegroundNeverCreateFileConsent() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row())
        model.refresh(); runCurrent(); model.latest(); runCurrent()
        model.background(); open(); runCurrent()
        assertNull(model.state.value.attachmentReview)
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertFalse(model.canSend()); assertEquals(1, wire.calls)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun noMatchNeedsExplicitRiskChoiceAndNeverPosts() = runTest(dispatcher) {
        unknown(); api.rows = emptyList(); api.mayHaveOlder = true
        val before = api.calls.size
        assertTrue(check()); runCurrent()
        assertEquals(listOf("context:42", "history:42", "pings:42"), api.calls.drop(before))
        val checked = review()
        assertEquals(RoomAttachmentReviewPhase.DUPLICATE_RISK, checked.phase)
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls); assertEquals(0, api.sends)
        entryActive = false; assertFalse(accept(checked))
        entryActive = true; assertTrue(accept(checked))
        assertEquals(RoomAttachmentPhase.READY, view().phase)
        assertEquals("caption", model.state.value.draft)
        assertNull(model.state.value.attachmentReview)
        assertFalse(accept(checked)); assertEquals(1, wire.calls)
    }

    @Test fun hashMatchRequiresAPersonAndClearsOnlyOriginalContent() = runTest(dispatcher) {
        unknown(reply = true); api.rows = listOf(row())
        assertTrue(check()); runCurrent()
        assertEquals(RoomAttachmentReviewPhase.EVIDENCE, review().phase)
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertEquals("private-file", review().evidence.single().filename)
        assertEquals("RoomAttachmentReview", review().toString())
        assertEquals("RoomAttachmentEvidence", review().evidence.single().toString())
        assertFalse(accept())
        assertTrue(confirm())
        assertTrue(model.drafts.value.isEmpty())
        assertEquals("", model.state.value.draft); assertNull(model.state.value.composerReply)
        assertEquals(0L, budget.reservedBytes)
        assertEquals(1, wire.calls); assertEquals(0, api.sends)
        assertEquals(listOf(10L), model.state.value.messages.map { it.id })
    }

    @Test fun evidenceRequiresExactOwnRoomDigestAndStrictlyLaterParseableTimestamp() = runTest(dispatcher) {
        unknown()
        val before = Instant.ofEpochMilli(view().upload!!.startedAtMillis - 1).toString()
        val equal = Instant.ofEpochMilli(view().upload!!.startedAtMillis).toString()
        val matching = row()
        api.rows = listOf(matching, row(11, sender = 8), row(12, sender = null), row(13, room = 43),
            row(14, digest = "0".repeat(64)), row(15, digest = ""), row(16, digest = null), row(17, time = before),
            row(18, time = equal), row(19, time = "not-a-date"), row(20, deleted = true))
        assertTrue(check()); runCurrent()
        assertEquals(1, review().evidence.size)
        assertSame(matching, review().evidence.single().original)
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
    }

    @Test fun rejectionCreatesANewRiskChoiceAndRetiresTheEvidencePress() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); assertTrue(check()); runCurrent()
        val evidence = review()
        assertTrue(model.rejectAttachmentEvidence(api.session.value, 42, entry, evidence) { true })
        assertNotSame(evidence, review()); assertFalse(confirm(evidence))
        assertFalse(accept(evidence)); assertTrue(accept())
        assertEquals(RoomAttachmentPhase.READY, view().phase); assertEquals(1, wire.calls)
    }

    @Test fun failedHistoryOffersNeitherEvidenceNorRiskConsent() = runTest(dispatcher) {
        unknown(); api.historyFailure = ApiFailure(FailureKind.NETWORK)
        assertTrue(check()); runCurrent()
        assertEquals(RoomAttachmentReviewPhase.FAILED, review().phase)
        assertEquals(FailureKind.NETWORK, review().error?.kind)
        assertFalse(accept()); assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(budget.reservedBytes > 0); assertEquals(1, wire.calls)
    }

    @Test fun preActionRevalidationFailureCannotLeaveCheckingStuck() = runTest(dispatcher) {
        unknown(); assertTrue(check())
        api.session.value = api.session.value.copy(refreshRequired = true)
        runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals(RoomAttachmentReviewPhase.FAILED, review().phase)
        assertFalse(accept()); assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
    }

    @Test fun closingAnInFlightCheckCancelsOnlyItsGetWithoutGrantingConsent() = runTest(dispatcher) {
        unknown(); api.historyGate = CompletableDeferred()
        assertTrue(check()); runCurrent(); val checking = review()
        assertEquals(RoomAttachmentReviewPhase.CHECKING, checking.phase)
        assertTrue(model.closeAttachmentReview(api.session.value, 42, entry, checking) { true })
        runCurrent()
        assertNull(model.state.value.attachmentReview); assertFalse(model.state.value.busy)
        api.historyGate!!.complete(Unit); runCurrent()
        assertFalse(accept(checking)); assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls)
    }

    @Test fun configurationWaitsForFreshEntryProofWithoutReplayingTheGet() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); api.historyGate = CompletableDeferred()
        val before = api.calls.count { it == "history:42" }
        assertTrue(check()); runCurrent(); entryActive = false
        assertNull(model.visibleAttachmentReview(api.session.value, 42, entry) { entryActive })
        api.historyGate!!.complete(Unit); runCurrent()
        val completed = review()
        assertFalse(confirm(completed))
        entryActive = true
        assertSame(completed, model.visibleAttachmentReview(api.session.value, 42, entry) { entryActive })
        assertEquals(before + 1, api.calls.count { it == "history:42" })
        assertTrue(confirm(completed)); assertEquals(1, wire.calls)
    }

    @Test fun completedCheckSurvivesRestoredForegroundWithoutSecondGet() = runTest(dispatcher) {
        unknown(); assertTrue(check()); runCurrent()
        val completed = review(); val before = api.calls.toList()
        model.foreground(); runCurrent()
        assertSame("Restoring the same entry must retain its completed private review", completed, model.state.value.attachmentReview)
        assertEquals(before, api.calls)
        assertTrue(accept(completed)); assertEquals(1, wire.calls)
    }

    @Test fun closingPrivateReviewDoesNotRequireSuccessfulSessionRevalidation() = runTest(dispatcher) {
        unknown(); assertTrue(check()); runCurrent(); val completed = review()
        api.session.value = api.session.value.copy(refreshRequired = true); runCurrent()
        assertFalse(accept(completed))
        assertTrue("Closing private choices must remain possible during revalidation", model.closeAttachmentReview(api.session.value, 42, entry, completed) { true })
        assertNull(model.state.value.attachmentReview)
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase); assertEquals(1, wire.calls)
    }

    @Test fun departedEntryCannotPublishALateCheckIntoAnotherRoom() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); api.historyGate = CompletableDeferred(); api.nonCancellableHistory = true
        assertTrue(check()); runCurrent(); val checking = review()
        model.leavePane(); entry = "entry-2"; open(43)
        assertEquals(43L, model.state.value.context?.room?.id)
        api.historyGate!!.complete(Unit); runCurrent()
        assertEquals(43L, model.state.value.roomId); assertNull(model.state.value.attachmentReview)
        assertFalse(accept(checking)); assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls)
    }

    @Test fun genuineBackgroundRetiresEvidenceButKeepsThePrivateUnknownFile() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); assertTrue(check()); runCurrent(); val old = review()
        model.background(); runCurrent(); entry = "entry-returned"; open()
        assertNull(model.state.value.attachmentReview); assertFalse(confirm(old))
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun retiredOwnerCannotRestorePrivateEvidenceFromItsLateGet() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); api.historyGate = CompletableDeferred(); api.nonCancellableHistory = true
        assertTrue(check()); runCurrent(); val old = review()
        api.session.value = api.session.value.copy(generation = 2); runCurrent(); open(43)
        api.historyGate!!.complete(Unit); runCurrent()
        assertTrue(model.drafts.value.isEmpty()); assertEquals(0L, budget.reservedBytes)
        assertNull(model.state.value.attachmentReview); assertFalse(accept(old))
        assertEquals(43L, model.state.value.context?.room?.id)
    }

    @Test fun aNewExplicitCheckRetiresEveryOldChoiceAndClose() = runTest(dispatcher) {
        unknown(); assertTrue(check()); runCurrent(); val old = review()
        assertTrue(check()); runCurrent(); val fresh = review()
        assertNotSame(old.request, fresh.request)
        assertFalse(accept(old))
        assertFalse(model.closeAttachmentReview(api.session.value, 42, entry, old) { true })
        assertSame(fresh, review()); assertTrue(accept(fresh)); assertEquals(1, wire.calls)
    }

    @Test fun ordinaryRefreshRetiresCheckedChoicesAndDoesNotUnlockTheFile() = runTest(dispatcher) {
        unknown(); assertTrue(check()); runCurrent(); val old = review()
        model.refresh(); runCurrent()
        assertNull(model.state.value.attachmentReview); assertFalse(accept(old))
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
    }

    @Test fun retryAfterFailureIsANewExplicitGetWithNoPost() = runTest(dispatcher) {
        unknown(); api.historyFailure = ApiFailure(FailureKind.TIMEOUT)
        assertTrue(check()); runCurrent(); val failed = review()
        api.historyFailure = null; assertTrue(check()); runCurrent()
        assertNotSame(failed.request, review().request); assertFalse(accept(failed))
        assertEquals(RoomAttachmentReviewPhase.DUPLICATE_RISK, review().phase)
        assertEquals(1, wire.calls)
    }

    @Test fun canonicalReplacementRetiresAnOldEvidenceConfirmation() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); assertTrue(check()); runCurrent(); val old = review()
        api.rows = listOf(row(deleted = true)); model.refresh(); runCurrent()
        assertFalse(confirm(old)); assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun confirmedEvidencePreservesEditAwayAndBackAndTheReply() = runTest(dispatcher) {
        unknown(reply = true); val originalReply = model.state.value.composerReply
        model.updateDraft("other"); model.updateDraft("caption")
        api.rows = listOf(row()); assertTrue(check()); runCurrent(); assertTrue(confirm())
        assertEquals("caption", model.state.value.draft); assertSame(originalReply, model.state.value.composerReply)
        assertNull(model.drafts.value.getValue(42).attachment); assertEquals(0L, budget.reservedBytes)
        assertEquals(1, wire.calls)
    }

    @Test fun wholeDraftDiscardRetiresAPendingCheckAndItsLateResult() = runTest(dispatcher) {
        unknown(); api.historyGate = CompletableDeferred(); api.nonCancellableHistory = true
        assertTrue(check()); runCurrent(); val old = review()
        model.discardDraft(model.drafts.value.getValue(42)); runCurrent()
        assertTrue(model.drafts.value.isEmpty()); assertEquals(0L, budget.reservedBytes)
        assertFalse(model.state.value.busy); assertNull(model.state.value.attachmentReview)
        api.historyGate!!.complete(Unit); runCurrent()
        assertTrue(model.drafts.value.isEmpty()); assertFalse(accept(old))
    }

    @Test fun staleEntryCannotStartDisplayOrConsumeACheck() = runTest(dispatcher) {
        val original = unknown(); val before = api.calls.size
        entryActive = false; assertFalse(check(original)); assertEquals(before, api.calls.size)
        entryActive = true; assertTrue(check()); runCurrent(); val checked = review()
        assertNull(model.visibleAttachmentReview(api.session.value, 42, "other-entry") { true })
        assertFalse(model.acceptAttachmentDuplicateRisk(api.session.value, 42, "other-entry", checked) { true })
        entryActive = false; assertFalse(accept(checked)); entryActive = true; assertTrue(accept(checked))
    }

    @Test fun reviewSuppressesReadAndOtherComposerActionsAndCloseIsNotReadProof() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); assertTrue(check()); runCurrent()
        val checked = review(); val message = model.state.value.messages.single()
        model.viewport(TimelineAnchor(message.id, 0), message.id, true); runCurrent()
        assertEquals(0, api.reads); assertFalse(model.state.value.atLatest)
        assertFalse(model.updateDraft(api.session.value, 42, "changed"))
        assertFalse(model.canReplyTo(api.session.value, 42, message)); assertFalse(model.canSend())
        model.showDrafts(); assertFalse(model.state.value.draftsOpen)
        assertTrue(model.closeAttachmentReview(api.session.value, 42, entry, checked) { true }); runCurrent()
        assertEquals(0, api.reads); assertFalse(model.state.value.atLatest)
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
    }

    private class Uploads : AttachmentUploadOperations {
        var calls = 0
        override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt {
            val hold = checkNotNull(retained.holdUpload(ready)); calls++
            val facts = MutableStateFlow(UploadTransferFacts(8, multipartSizeBytes = 100, multipartAttemptedUpperBound = 100,
                writerStarted = true, writerRunning = true, requestBodyEndObserved = true, requestBodyEndBytes = 100))
            try { onTransfer(facts); throw ApiFailure(FailureKind.NETWORK) }
            finally {
                facts.value = facts.value.copy(writerRunning = false, callbackFinished = true, stopped = true)
                hold.close()
            }
        }
        override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt = error("Room only")
    }

    private class Rooms : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        val calls = mutableListOf<String>()
        var rows: List<RoomMessage>? = null
        var historyFailure: ApiFailure? = null
        var historyGate: CompletableDeferred<Unit>? = null
        var nonCancellableHistory = false
        var mayHaveOlder = false
        var sends = 0
        var reads = 0
        override suspend fun context(roomId: Long): ConversationContext {
            calls += "context:$roomId"
            return ConversationContext(RoomInfo(roomId, "room", "Room", "", "public", 0, 0, 7, "member", false,
                "none", 0, 0, null), ChatPolicy(emptySet(), null, DisplayPreferences()))
        }
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            calls += "history:$roomId"
            if (roomId == 42L) {
                if (nonCancellableHistory) withContext(NonCancellable) { historyGate?.await() } else historyGate?.await()
                historyFailure?.let { throw it }
            }
            val messages = rows?.takeIf { roomId == 42L } ?: listOf(RoomMessage(2, roomId, 8, "Peer", "text", "fixture", false, "2026-11-09T12:00:00Z"))
            return MessagePage(messages, messages.firstOrNull()?.id, mayHaveOlder)
        }
        override suspend fun pings(roomId: Long): List<RoomPing> { calls += "pings:$roomId"; return emptyList() }
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
            sends++; return SendResult.Message(RoomMessage(3, roomId, 7, "Viewer", "text", body, false, "2026-11-09T12:00:00Z"))
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { reads++; return messageId }
    }
}
