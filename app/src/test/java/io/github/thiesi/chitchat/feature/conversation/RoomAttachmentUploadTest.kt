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
class RoomAttachmentUploadTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Rooms()
    private val wire = Uploads()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val store = ViewModelStore()
    private lateinit var model: ConversationViewModel
    private var entry: String? = null
    private var entrySequence = 0

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        model = ConversationViewModel(api, attachmentBudget = budget, uploads = wire)
        store.put("fixture", model)
    }
    @After fun stop() = runTest(dispatcher) {
        try {
            wire.stop?.complete(Unit)
            api.readGate?.complete(Unit)
            api.contextGate?.complete(Unit)
            val lifetime = model.viewModelScope.coroutineContext[Job]!!
            store.clear(); lifetime.join()
            assertEquals(0L, budget.reservedBytes)
        } finally { Dispatchers.resetMain() }
    }
    private fun open(room: Long = 42) {
        entry = "entry-${++entrySequence}"
        model.foreground(); model.open(room); dispatcher.scheduler.runCurrent()
    }
    private suspend fun prepared(): RoomAttachmentDraft {
        open()
        val originalEntry = entry
        assertTrue(model.selectAttachment(api.session.value, 42, checkNotNull(originalEntry),
            PickedAttachmentRead("private-file", null) { ByteArrayInputStream("prepared".toByteArray()) },
            AttachmentPickSource.FILE) { entry == originalEntry })
        val view = model.drafts.first { it[42]?.attachment?.phase == RoomAttachmentPhase.READY }.getValue(42).attachment!!
        dispatcher.scheduler.runCurrent() // Complete the producer's synchronous resource handoff.
        return view
    }
    private fun send(view: RoomAttachmentDraft): Boolean {
        val originalEntry = checkNotNull(entry)
        return model.sendAttachment(api.session.value, 42, originalEntry, view) { entry == originalEntry }
    }
    private fun view(room: Long = 42) = model.drafts.value.getValue(room).attachment!!

    @Test fun explicitSendFreezesLiteralCaptionReplySessionAndMakesOneAttachmentPost() = runTest(dispatcher) {
        val ready = prepared()
        val original = api.session.value
        model.updateDraft("  /ping literal caption  ")
        assertTrue(model.replyTo(original, 42, model.state.value.messages.first()))
        val reply = model.state.value.composerReply
        assertTrue(send(ready)); runCurrent()
        assertEquals(1, wire.calls)
        assertSame(original, wire.original)
        assertEquals("/ping literal caption", wire.caption)
        assertEquals(reply?.messageId, wire.reply)
        assertEquals(0, api.sends)
        assertFalse(model.updateDraft(original, 42, "changed"))
        assertFalse(send(ready))
        assertFalse(model.canSend())
        wire.ack(); runCurrent()
        assertTrue(model.drafts.value.isEmpty())
        assertEquals("", model.state.value.draft)
        assertNull(model.state.value.composerReply)
        assertEquals(0L, budget.reservedBytes)
        assertEquals(1, wire.calls)
    }

    @Test fun cancellationBeforeQueuedEntryMakesNoPostAndDoesNotLeaveBusyState() = runTest(dispatcher) {
        val ready = prepared()
        assertTrue(send(ready))
        entry = null; model.background(); runCurrent()
        assertEquals(0, wire.calls)
        assertEquals(RoomAttachmentPhase.READY, view().phase)
        assertFalse(model.state.value.busy)
        assertTrue(budget.reservedBytes > 0)
        open()
        assertTrue(send(view())); runCurrent()
        assertEquals(1, wire.calls)
    }

    @Test fun navigationKeepsTheOriginalPostAndCannotAdoptIntoTheNewRoom() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("original caption")
        assertTrue(send(ready)); runCurrent()
        val oldSlot = model.drafts.value.getValue(42).slot
        val before = api.calls.toList()
        model.leavePane(); open(43); model.updateDraft("new room caption")
        assertFalse(wire.cancelled)
        assertEquals(before, api.calls) // No overlapping canonical/read work while POST owns serialization.
        wire.ack(); runCurrent()
        assertEquals(43L, model.state.value.roomId)
        assertEquals("new room caption", model.state.value.draft)
        assertSame(oldSlot, model.drafts.value.getValue(42).slot)
        assertEquals(RoomAttachmentPhase.CONFIRMED, view().phase)
        assertEquals(0L, budget.reservedBytes)
        val confirmed = view()
        assertFalse(model.adoptConfirmedAttachment(api.session.value, 42, confirmed) { true })
        open()
        assertFalse(model.adoptConfirmedAttachment(api.session.value, 42, confirmed) { false })
        assertTrue(model.adoptConfirmedAttachment(api.session.value, 42, confirmed) { true })
        runCurrent()
        assertEquals("", model.state.value.draft)
        assertEquals(1, wire.calls)
    }

    @Test fun confirmedAdoptionPreservesContentEditedAwayAndBackInANewEntry() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("same words")
        assertTrue(send(ready)); runCurrent()
        model.leavePane(); open(43)
        wire.ack(); runCurrent()
        open()
        val confirmed = view()
        model.updateDraft("other words"); model.updateDraft("same words")
        assertTrue(model.adoptConfirmedAttachment(api.session.value, 42, confirmed) { true })
        runCurrent()
        assertEquals("same words", model.state.value.draft)
        assertNull(model.drafts.value.getValue(42).attachment)
        assertEquals(1, wire.calls)
    }

    @Test fun ackStaysConfirmedAndClearsOnlyOriginalDraftEvenIfCanonicalGetFails() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("caption")
        assertTrue(send(ready)); runCurrent()
        api.historyFailure = ApiFailure(FailureKind.NETWORK)
        wire.ack(); runCurrent()
        assertTrue(model.drafts.value.isEmpty())
        assertEquals("", model.state.value.draft)
        assertFalse(model.state.value.uncertainSend)
        assertEquals(FailureKind.NETWORK, model.state.value.error?.kind)
        assertEquals("sent", model.state.value.notice)
        assertEquals(0L, budget.reservedBytes)
        assertEquals(1, wire.calls)
    }

    @Test fun staleEntrySessionAndPolicyCannotStartAPost() = runTest(dispatcher) {
        val ready = prepared(); val original = api.session.value
        assertFalse(model.sendAttachment(original, 42, "entry", ready) { false })
        assertFalse(model.sendAttachment(original.copy(generation = 0), 42, "entry", ready) { true })
        api.session.value = original.copy(attachmentPolicy = AttachmentPolicy.Invalid); runCurrent()
        assertFalse(send(ready))
        assertEquals(0, wire.calls)
        assertSame(ready, view())
    }

    @Test fun lastChunkAfterCancelRemainsUnknownAndCannotBecomeTextOrUploadReplay() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("caption")
        wire.stop = CompletableDeferred()
        assertTrue(send(ready)); runCurrent()
        assertTrue(model.cancelAttachmentUpload(api.session.value, 42, view()) { true }); runCurrent()
        assertEquals(RoomAttachmentPhase.CANCELLING, view().phase)
        assertTrue(model.state.value.busy)
        assertEquals(1, wire.calls)
        wire.facts.value = wire.facts.value.copy(multipartAttemptedUpperBound = 100)
        wire.stop!!.complete(Unit); runCurrent()
        val unknown = view()
        assertEquals(RoomAttachmentPhase.UNKNOWN, unknown.phase)
        assertFalse(model.state.value.uncertainSend) // Text-send uncertainty is a separate authority.
        assertFalse(model.removeAttachment(api.session.value, 42, unknown) { true })
        assertFalse(send(unknown)); model.send()
        assertEquals(0, api.sends)
        model.refresh(); runCurrent()
        assertSame(unknown, view()) // Automatic/manual ordinary history does not grant file reconciliation consent.
        assertEquals(1, wire.calls)
    }

    @Test fun partialAbortReturnsTheSameDraftOnlyAfterTheActualStopBarrier() = runTest(dispatcher) {
        val ready = prepared(); wire.stop = CompletableDeferred()
        assertTrue(send(ready)); runCurrent()
        val selection = view().selection
        assertTrue(model.cancelAttachmentUpload(api.session.value, 42, view()) { true }); runCurrent()
        assertEquals(RoomAttachmentPhase.CANCELLING, view().phase)
        assertFalse(send(view()))
        wire.stop!!.complete(Unit); runCurrent()
        assertEquals(RoomAttachmentPhase.READY, view().phase)
        assertSame(selection, view().selection)
        assertTrue(budget.reservedBytes > 0)
        assertFalse(model.state.value.busy)
        assertEquals(1, wire.calls)
    }

    @Test fun staleCancelCannotAbortAFinishedBodyAndBackgroundKeepsItUnknown() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready)); runCurrent()
        val staleControl = view()
        wire.fullBody() // Flow collector has not yet rendered Finishing; callback must read fresh facts.
        assertFalse(model.cancelAttachmentUpload(api.session.value, 42, staleControl) { true })
        runCurrent(); assertEquals(RoomAttachmentPhase.FINISHING, view().phase)
        entry = null; model.background(); runCurrent()
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(wire.cancelled)
        assertEquals(1, wire.calls)
    }

    @Test fun sessionRetirementCannotFreeAnActualTransportHoldOrPopulateItsSuccessor() = runTest(dispatcher) {
        val ready = prepared(); wire.stop = CompletableDeferred()
        assertTrue(send(ready)); runCurrent()
        val old = api.session.value
        api.session.value = old.copy(generation = 2); runCurrent()
        assertTrue(model.drafts.value.isEmpty())
        assertTrue(budget.reservedBytes > 0)
        open(43)
        wire.stop!!.complete(Unit); runCurrent()
        assertTrue(model.drafts.value.isEmpty())
        assertEquals(43L, model.state.value.roomId)
        assertEquals(0L, budget.reservedBytes)
        assertSame(old, wire.original)
    }

    @Test fun policyChangingAtTransportKeepsReadyWithoutInventingAServerRefusal() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready)); runCurrent()
        wire.result.completeExceptionally(AttachmentUploadPolicyFailure(AttachmentPolicyObservation.Invalid)); runCurrent()
        assertEquals(RoomAttachmentPhase.READY, view().phase)
        assertTrue(view().policyBlocked)
        assertNull(view().uploadError)
        assertTrue(budget.reservedBytes > 0)
        assertEquals(1, wire.calls)
    }

    @Test fun openDraftManagerRetiresBehindModalSendAndCancelActions() = runTest(dispatcher) {
        val ready = prepared()
        model.showDrafts()
        assertFalse(send(ready)); assertEquals(0, wire.calls)
        model.hideDrafts()
        assertTrue(send(ready)); runCurrent()
        val oldControl = view()
        model.showDrafts()
        assertFalse(model.cancelAttachmentUpload(api.session.value, 42, oldControl) { true })
        assertFalse(wire.cancelled)
        model.hideDrafts()
        assertTrue(model.cancelAttachmentUpload(api.session.value, 42, view()) { true }); runCurrent()
        assertEquals(RoomAttachmentPhase.READY, view().phase)
    }

    @Test fun definite413RetainsAFullBodyDraftWithoutUnlockingAnOldSendControl() = runTest(dispatcher) {
        val oldControl = prepared()
        assertTrue(send(oldControl)); runCurrent()
        wire.fullBody()
        wire.result.completeExceptionally(ApiFailure(FailureKind.HTTP, 413, "file_too_large")); runCurrent()
        assertEquals(RoomAttachmentPhase.READY, view().phase)
        assertEquals(413, view().uploadError?.status)
        assertSame(oldControl.selection, view().selection)
        assertTrue(budget.reservedBytes > 0)
        assertFalse(send(oldControl)); assertEquals(1, wire.calls)
    }

    @Test fun knownFinalSizeSurvivesIntoUploadAndUnknownPolicyObservations() = runTest(dispatcher) {
        val ready = prepared()
        assertTrue(send(ready)); runCurrent()
        api.session.value = api.session.value.copy(attachmentPolicy = AttachmentPolicy.Known(4, emptySet(), emptySet(), null))
        runCurrent()
        assertTrue(model.attachmentPolicyObservation(api.session.value, 42, view()) { true } is AttachmentPolicyObservation.TooLarge)
        wire.fullBody(); entry = null; model.background(); runCurrent()
        assertEquals(RoomAttachmentPhase.UNKNOWN, view().phase)
        open()
        assertTrue(model.attachmentPolicyObservation(api.session.value, 42, view()) { true } is AttachmentPolicyObservation.TooLarge)
    }

    @Test fun definiteRoomAccessLossClearsTranscriptButKeepsThePrivateFileDraft() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("private caption")
        assertTrue(send(ready)); runCurrent()
        wire.result.completeExceptionally(ApiFailure(FailureKind.HTTP, 403, "room_forbidden")); runCurrent()
        assertNull("Definite room access loss must retire the transcript", model.state.value.context)
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals("private caption", model.drafts.value.getValue(42).body)
        assertEquals(RoomAttachmentPhase.READY, view().phase)
        assertEquals(403, view().uploadError?.status)
        assertFalse(model.state.value.uncertainSend)
        assertTrue(budget.reservedBytes > 0)
        assertEquals(1, wire.calls)
    }

    @Test fun lateAccessLossCannotClearTheNewRoomsTranscriptOrDraft() = runTest(dispatcher) {
        val ready = prepared()
        assertTrue(send(ready)); runCurrent()
        model.leavePane(); open(43); model.updateDraft("other room")
        wire.result.completeExceptionally(ApiFailure(FailureKind.HTTP, 403, "room_forbidden")); runCurrent()
        assertEquals(43L, model.state.value.context?.room?.id)
        assertTrue(model.state.value.messages.isNotEmpty())
        assertEquals("other room", model.state.value.draft)
        assertNull(model.state.value.error)
        assertEquals(403, view().uploadError?.status)
        assertTrue(budget.reservedBytes > 0)
        assertEquals(1, wire.calls)
    }

    @Test fun retiredOwnerCannotBlockOrClearItsSuccessorsBusyState() = runTest(dispatcher) {
        val ready = prepared(); wire.stop = CompletableDeferred()
        assertTrue(send(ready)); runCurrent()
        api.session.value = api.session.value.copy(generation = 2); runCurrent()
        assertTrue(budget.reservedBytes > 0)
        api.contextGate = CompletableDeferred()
        open(43)
        assertTrue("A new owner must not wait for a retired owner's transport", "context:43" in api.calls)
        assertTrue(model.state.value.busy)
        wire.stop!!.complete(Unit); runCurrent()
        assertTrue("Retired cleanup cannot clear the successor's busy flag", model.state.value.busy)
        assertEquals(0L, budget.reservedBytes)
        api.contextGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals(43L, model.state.value.context?.room?.id)
    }

    @Test fun oldRemoveCannotCrossAnUploadAttemptOnTheSameSelection() = runTest(dispatcher) {
        val oldControl = prepared()
        assertTrue(send(oldControl)); runCurrent()
        assertTrue(model.cancelAttachmentUpload(api.session.value, 42, view()) { true }); runCurrent()
        assertSame(oldControl.selection, view().selection)
        assertEquals(RoomAttachmentPhase.READY, view().phase)
        assertFalse("Old Remove must retire across a send attempt", model.removeAttachment(api.session.value, 42, oldControl) { true })
    }

    @Test fun oldReplaceCannotCrossAnUploadAttemptOnTheSameSelection() = runTest(dispatcher) {
        val oldControl = prepared()
        assertTrue(send(oldControl)); runCurrent()
        assertTrue(model.cancelAttachmentUpload(api.session.value, 42, view()) { true }); runCurrent()
        assertSame(oldControl.selection, view().selection)
        assertFalse("Old Replace must retire across a send attempt", model.selectAttachment(api.session.value, 42,
            checkNotNull(entry), PickedAttachmentRead("replacement", null) { ByteArrayInputStream(byteArrayOf(1)) },
            AttachmentPickSource.FILE, oldControl) { true })
    }

    @Test fun aReadPostAndAnUploadCannotOverlap() = runTest(dispatcher) {
        val ready = prepared(); api.readGate = CompletableDeferred()
        model.viewport(TimelineAnchor(2, 0), 2, true); runCurrent()
        assertTrue(model.state.value.reading)
        assertFalse(send(ready)); assertEquals(0, wire.calls)
        api.readGate!!.complete(Unit); runCurrent()
        assertTrue(send(ready)); runCurrent()
        assertEquals(1, wire.calls)
    }

    private class Uploads : AttachmentUploadOperations {
        var calls = 0
        var original: SessionState? = null
        var caption: String? = null
        var reply: Long? = null
        var cancelled = false
        var stop: CompletableDeferred<Unit>? = null
        val result = CompletableDeferred<RoomUploadReceipt>()
        val facts = MutableStateFlow(UploadTransferFacts(8, multipartSizeBytes = 100, multipartAttemptedUpperBound = 10,
            writerStarted = true, writerRunning = true))
        fun fullBody() { facts.value = facts.value.copy(multipartAttemptedUpperBound = 100,
            requestBodyEndObserved = true, requestBodyEndBytes = 100) }
        fun ack() { fullBody(); result.complete(RoomUploadReceipt(42, 19)) }
        override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt {
            val allocation = checkNotNull(retained.holdUpload(ready))
            calls++; original = expected; this.caption = caption; reply = replyId
            try {
                onTransfer(facts)
                return result.await()
            } catch (cancelled: CancellationException) { this.cancelled = true; throw cancelled }
            finally {
                withContext(NonCancellable) { stop?.await() }
                facts.value = facts.value.copy(writerRunning = false, callbackFinished = true, stopped = true)
                allocation.close()
            }
        }
        override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt = error("Room actor must not use DM")
    }

    private class Rooms : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        val calls = mutableListOf<String>()
        var sends = 0
        var historyFailure: ApiFailure? = null
        var readGate: CompletableDeferred<Unit>? = null
        var contextGate: CompletableDeferred<Unit>? = null
        override suspend fun context(roomId: Long): ConversationContext {
            calls += "context:$roomId"
            contextGate?.await()
            return ConversationContext(RoomInfo(roomId, "room", "Room", "", "public", 0, 0, 7, "member", false,
                "none", 0, 0, null), ChatPolicy(emptySet(), null, DisplayPreferences()))
        }
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            calls += "history:$roomId"; historyFailure?.let { throw it }
            return MessagePage(listOf(RoomMessage(2, roomId, 8, "Peer", "text", "fixture", false, "2026-11-09T12:00:00Z")), 2, false)
        }
        override suspend fun pings(roomId: Long): List<RoomPing> { calls += "pings:$roomId"; return emptyList() }
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
            sends++; return SendResult.Message(RoomMessage(3, roomId, 7, "Viewer", "text", body, false, "2026-11-09T12:00:00Z"))
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { readGate?.await(); return messageId }
    }
}
