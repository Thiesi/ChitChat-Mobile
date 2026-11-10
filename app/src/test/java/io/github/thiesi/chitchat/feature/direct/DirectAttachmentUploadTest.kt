package io.github.thiesi.chitchat.feature.direct

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
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
class DirectAttachmentUploadTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Directs()
    private val wire = Uploads()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val store = ViewModelStore()
    private lateinit var model: DirectThreadViewModel
    private var entry: String? = null
    private var entrySequence = 0

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        model = DirectThreadViewModel(api, attachmentBudget = budget, uploads = wire)
        store.put("fixture", model)
    }
    @After fun stop() = runTest(dispatcher) {
        try {
            wire.stop?.complete(Unit); api.readGate?.complete(Unit); api.contextGate?.complete(Unit)
            val lifetime = model.viewModelScope.coroutineContext[Job]!!
            store.clear(); lifetime.join()
            assertEquals(0L, budget.reservedBytes)
        } finally { Dispatchers.resetMain() }
    }
    private fun open(peer: Long = 8) {
        entry = "entry-${++entrySequence}"
        model.open(peer); model.foreground(); dispatcher.scheduler.runCurrent()
    }
    private suspend fun prepared(): DirectAttachmentDraft {
        open(); val originalEntry = checkNotNull(entry)
        assertTrue(model.selectAttachment(api.session.value, 8, originalEntry,
            PickedAttachmentRead("private-file", null) { ByteArrayInputStream("prepared".toByteArray()) },
            AttachmentPickSource.FILE) { entry == originalEntry })
        val ready = model.drafts.first { it[8]?.attachment?.phase == DirectAttachmentPhase.READY }.getValue(8).attachment!!
        dispatcher.scheduler.runCurrent()
        return ready
    }
    private fun send(view: DirectAttachmentDraft): Boolean {
        val originalEntry = checkNotNull(entry)
        return model.sendAttachment(api.session.value, 8, originalEntry, view) { entry == originalEntry }
    }
    private fun view() = model.drafts.value.getValue(8).attachment!!

    @Test fun originalLiteralCaptionReplySessionAndOneTransportInvocationAreFrozen() = runTest(dispatcher) {
        val ready = prepared(); val original = api.session.value
        model.updateDraft("  /ping literal caption  ")
        assertTrue(model.replyTo(original, 8, model.state.value.messages.first()))
        val reply = model.state.value.composerReply
        assertTrue(send(ready)); runCurrent()
        assertEquals(1, wire.calls); assertSame(original, wire.original)
        assertEquals("/ping literal caption", wire.caption); assertEquals(reply?.messageId, wire.reply)
        assertFalse(model.updateDraft(original, 8, "changed")); assertFalse(send(ready))
        assertFalse(model.canSend()); assertEquals(0, api.sends)
        wire.ack(); runCurrent()
        assertTrue(model.drafts.value.isEmpty()); assertEquals(0L, budget.reservedBytes)
        assertEquals("", model.state.value.draft); assertNull(model.state.value.composerReply)
        assertEquals(19L, model.state.value.confirmedSendId)
    }

    @Test fun cancellationBeforeQueuedEntrySpendsNoTransportInvocation() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready))
        entry = null; model.background(); runCurrent()
        assertEquals(0, wire.calls); assertFalse(model.state.value.busy)
        assertEquals(DirectAttachmentPhase.READY, view().phase)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun navigationPreservesTheOriginalUploadAndCannotClearAnotherPeerDraft() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("original caption")
        assertTrue(send(ready)); runCurrent(); val before = api.calls.toList()
        model.leavePane(); open(9); model.updateDraft("other peer caption")
        assertFalse(wire.cancelled); assertEquals(before, api.calls)
        wire.ack(); runCurrent()
        assertEquals(9L, model.state.value.peerId); assertEquals("other peer caption", model.state.value.draft)
        assertEquals(DirectAttachmentPhase.CONFIRMED, view().phase); assertEquals(0L, budget.reservedBytes)
        val confirmed = view()
        assertFalse(model.adoptConfirmedAttachment(api.session.value, 8, confirmed) { true })
        open()
        assertFalse(model.adoptConfirmedAttachment(api.session.value, 8, confirmed) { false })
        assertTrue(model.adoptConfirmedAttachment(api.session.value, 8, confirmed) { true }); runCurrent()
        assertEquals("", model.state.value.draft); assertNull(model.drafts.value[8])
        assertEquals("other peer caption", model.drafts.value.getValue(9).body)
        assertEquals(1, wire.calls)
    }

    @Test fun genuineBackgroundKeepsTheActorBusyUntilActualTransportStop() = runTest(dispatcher) {
        val ready = prepared(); wire.stop = CompletableDeferred()
        assertTrue(send(ready)); runCurrent()
        entry = null; model.background(); runCurrent()
        assertTrue(wire.cancelled); assertTrue(model.state.value.busy)
        assertEquals(DirectAttachmentPhase.CANCELLING, view().phase)
        open(9); assertTrue(model.state.value.busy)
        wire.stop!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.busy); assertEquals(9L, model.state.value.context?.peer?.id)
        assertEquals(DirectAttachmentPhase.READY, view().phase)
        assertTrue(budget.reservedBytes > 0) // Surviving draft still owns the allocation.
    }

    @Test fun fullBodyInterruptionIsUnknownAndEveryExistingShortcutStaysLocked() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready)); runCurrent()
        wire.fullBody(); wire.result.completeExceptionally(ApiFailure(FailureKind.NETWORK)); runCurrent()
        val unknown = view(); assertEquals(DirectAttachmentPhase.UNKNOWN, unknown.phase)
        assertFalse(model.removeAttachment(api.session.value, 8, unknown) { true })
        assertFalse(model.selectAttachment(api.session.value, 8, checkNotNull(entry),
            PickedAttachmentRead("replace", null) { ByteArrayInputStream(byteArrayOf(1)) }, AttachmentPickSource.FILE, unknown) { true })
        model.refresh(); runCurrent(); model.latest(); runCurrent(); model.allowDuplicateRisk(); model.send()
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertFalse(model.canSend()); assertEquals(1, wire.calls); assertEquals(0, api.sends)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun definiteServerRefusalKeepsTheSameFileCaptionReplyAndMetadata() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("caption")
        assertTrue(model.replyTo(api.session.value, 8, model.state.value.messages.first()))
        val reply = model.state.value.composerReply
        assertTrue(send(ready)); runCurrent(); wire.fullBody()
        val refusal = ApiFailure(FailureKind.HTTP, 413, "attachment_too_large")
        wire.result.completeExceptionally(refusal); runCurrent()
        assertEquals(DirectAttachmentPhase.READY, view().phase); assertSame(refusal, view().uploadError)
        assertEquals("caption", model.state.value.draft); assertSame(reply, model.state.value.composerReply)
        assertTrue(budget.reservedBytes > 0); assertEquals(1, wire.calls)
    }

    @Test fun acknowledgedUploadNeverBecomesUnknownWhenCanonicalGetFails() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("caption")
        assertTrue(send(ready)); runCurrent(); api.historyFailure = ApiFailure(FailureKind.NETWORK)
        wire.ack(); runCurrent()
        assertNull(model.drafts.value[8]); assertEquals(0L, budget.reservedBytes)
        assertEquals(19L, model.state.value.confirmedSendId); assertFalse(model.state.value.uncertainSend)
        assertEquals(FailureKind.NETWORK, model.state.value.error?.kind); assertEquals(1, wire.calls)
    }

    @Test fun fileBufferCompletionDoesNotDisableCancellationBeforeWholeBodyEnd() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready)); runCurrent()
        wire.facts.value = wire.facts.value.copy(fileBytesBuffered = 8)
        runCurrent(); assertEquals(DirectAttachmentPhase.UPLOADING, view().phase)
        assertTrue(model.cancelAttachmentUpload(api.session.value, 8, view()) { true }); runCurrent()
        assertEquals(DirectAttachmentPhase.READY, view().phase)
    }

    @Test fun heldCancelRechecksTheActualWholeBodyFactsBeforeActing() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready)); runCurrent(); val held = view()
        wire.fullBody() // No UI collector turn between real facts and the held press.
        assertFalse(model.cancelAttachmentUpload(api.session.value, 8, held) { true })
        runCurrent(); assertEquals(DirectAttachmentPhase.FINISHING, view().phase)
        wire.ack(); runCurrent(); assertEquals(1, wire.calls)
    }

    @Test fun aPossiblyCompleteThrowingFinalChunkIsStillUnknown() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready)); runCurrent()
        wire.facts.value = wire.facts.value.copy(multipartAttemptedUpperBound = 100)
        wire.result.completeExceptionally(ApiFailure(FailureKind.NETWORK)); runCurrent()
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
    }

    @Test fun oldRemoveAndReplaceCannotCrossAnUploadAttemptOnTheSameSelection() = runTest(dispatcher) {
        val old = prepared(); assertTrue(send(old)); runCurrent()
        assertTrue(model.cancelAttachmentUpload(api.session.value, 8, view()) { true }); runCurrent()
        assertSame(old.selection, view().selection); assertEquals(DirectAttachmentPhase.READY, view().phase)
        assertFalse(model.removeAttachment(api.session.value, 8, old) { true })
        assertFalse(model.selectAttachment(api.session.value, 8, checkNotNull(entry),
            PickedAttachmentRead("replace", null) { ByteArrayInputStream(byteArrayOf(1)) }, AttachmentPickSource.FILE, old) { true })
        assertTrue(model.removeAttachment(api.session.value, 8, view()) { true })
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun inboxDiscardCannotCrossAnUploadAttemptOnTheSameDraft() = runTest(dispatcher) {
        val ready = prepared(); val old = model.drafts.value.getValue(8)
        assertTrue(send(ready)); runCurrent()
        assertTrue(model.cancelAttachmentUpload(api.session.value, 8, view()) { true }); runCurrent()
        assertFalse(model.discardSavedDraft(old) { true })
        assertTrue(model.discardSavedDraft(model.drafts.value.getValue(8)) { true })
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun inboxDiscardCannotRemoveNewContentAfterAnEditAwayAndBack() = runTest(dispatcher) {
        prepared(); model.updateDraft("caption"); val old = model.drafts.value.getValue(8)
        model.updateDraft("other"); model.updateDraft("caption")
        assertFalse(model.discardSavedDraft(old) { true })
        assertTrue(model.discardSavedDraft(model.drafts.value.getValue(8)) { true })
    }

    @Test fun currentPrivacyAndPolicyMustAuthorizeBeforeAnyTransportInvocation() = runTest(dispatcher) {
        val ready = prepared(); val original = api.session.value
        api.session.value = original.copy(privacy = null); runCurrent()
        assertFalse(send(ready)); assertEquals(0, wire.calls)
        api.session.value = original.copy(attachmentPolicy = AttachmentPolicy.Invalid); runCurrent()
        assertFalse(send(ready)); assertEquals(0, wire.calls)
        assertEquals(DirectAttachmentPhase.READY, view().phase)
    }

    @Test fun newlyKnownGlobalMuteCannotUseTheOlderUnmutedThreadContext() = runTest(dispatcher) {
        val ready = prepared()
        assertNull(model.state.value.context?.mutedEverywhere)
        api.session.value = api.session.value.copy(chatPolicy = ChatPolicy(emptySet(),
            RoomMute(3, "global", null, null, "fixture"), DisplayPreferences()))
        runCurrent()
        assertFalse("The newest session mute must gate upload admission", send(ready))
        assertEquals(0, wire.calls)
        assertEquals(DirectAttachmentPhase.READY, view().phase)
    }

    @Test fun privacyLostAfterTheTapIsRecheckedBeforeTheQueuedOperation() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready))
        api.session.value = api.session.value.copy(privacy = null); runCurrent()
        assertEquals(0, wire.calls); assertEquals(DirectAttachmentPhase.READY, view().phase)
        assertEquals(FailureKind.INCOMPATIBLE, view().uploadError?.kind)
        assertFalse(model.state.value.busy)
    }

    @Test fun lateLocalPolicyFailureIsNotAServerRefusalOrUploadUncertainty() = runTest(dispatcher) {
        val ready = prepared(); wire.beforeBinding = AttachmentUploadPolicyFailure(AttachmentPolicyObservation.Invalid)
        assertTrue(send(ready)); runCurrent()
        assertEquals(DirectAttachmentPhase.READY, view().phase); assertTrue(view().policyBlocked)
        assertNull(view().uploadError); assertTrue(budget.reservedBytes > 0)
    }

    @Test fun genuineBackgroundAfterFullBodyStillProducesUnknown() = runTest(dispatcher) {
        val ready = prepared(); assertTrue(send(ready)); runCurrent(); wire.fullBody(); runCurrent()
        entry = null; model.background(); runCurrent()
        assertTrue(wire.cancelled); assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertFalse(model.state.value.busy); assertTrue(budget.reservedBytes > 0)
    }

    @Test fun retiredOwnerKeepsPhysicalChargeWithoutBlockingOrClearingItsSuccessor() = runTest(dispatcher) {
        val ready = prepared(); wire.stop = CompletableDeferred()
        assertTrue(send(ready)); runCurrent()
        api.session.value = api.session.value.copy(generation = 2); runCurrent()
        assertTrue(budget.reservedBytes > 0); assertTrue(model.drafts.value.isEmpty())
        api.contextGate = CompletableDeferred(); open(9)
        assertTrue("context:9" in api.calls); assertTrue(model.state.value.busy)
        wire.stop!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.busy); assertEquals(0L, budget.reservedBytes)
        api.contextGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.busy); assertEquals(9L, model.state.value.context?.peer?.id)
    }

    @Test fun readPostAndUploadCannotOverlapAndUploadGrantsNoReadProof() = runTest(dispatcher) {
        val ready = prepared(); api.readGate = CompletableDeferred()
        model.viewport(DirectAnchor(2, 0), setOf(2L), 2, true); runCurrent()
        assertEquals(1, api.reads); assertTrue(model.state.value.busy)
        assertFalse(send(ready)); assertEquals(0, wire.calls)
        api.readGate!!.complete(Unit); runCurrent()
        assertTrue(send(ready)); runCurrent(); wire.ack(); runCurrent()
        assertEquals(1, api.reads); assertFalse(model.state.value.atLatest)
    }

    @Test fun independentReadUncertaintySurvivesUploadAndItsAcknowledgmentGet() = runTest(dispatcher) {
        val ready = prepared(); api.readFailure = ApiFailure(FailureKind.NETWORK)
        model.viewport(DirectAnchor(2, 0), setOf(2L), 2, true); runCurrent()
        assertTrue(model.state.value.uncertainRead)
        model.refresh(); runCurrent()
        assertTrue(send(ready)); runCurrent(); wire.ack(); runCurrent()
        assertTrue(model.state.value.uncertainRead); assertEquals(1, api.reads)
        assertFalse(model.state.value.uncertainSend)
    }

    @Test fun blockUncertaintyDisablesUploadAndAnUploadDisablesTheOtherWrites() = runTest(dispatcher) {
        val ready = prepared(); api.blockFailure = ApiFailure(FailureKind.NETWORK)
        model.setBlocked(true); runCurrent()
        assertTrue(model.state.value.uncertainBlock); assertFalse(send(ready)); assertEquals(0, wire.calls)
        model.refresh(); runCurrent() // Existing explicit relationship reconciliation, not file consent.
        assertTrue(send(ready)); runCurrent()
        val blocks = api.blocks; model.setBlocked(true); model.send(); runCurrent()
        assertEquals(blocks, api.blocks); assertEquals(0, api.sends)
    }

    @Test fun laterEditAwayAndBackSurvivesPrivateAcknowledgmentAdoption() = runTest(dispatcher) {
        val ready = prepared(); model.updateDraft("caption")
        assertTrue(send(ready)); runCurrent(); model.leavePane(); open(9)
        wire.ack(); runCurrent(); val confirmed = view()
        open(); model.updateDraft("other"); model.updateDraft("caption")
        assertTrue(model.adoptConfirmedAttachment(api.session.value, 8, confirmed) { true }); runCurrent()
        assertEquals("caption", model.state.value.draft); assertNull(model.drafts.value.getValue(8).attachment)
        assertEquals(0L, budget.reservedBytes)
    }

    private class Uploads : AttachmentUploadOperations {
        var calls = 0; var original: SessionState? = null; var caption: String? = null; var reply: Long? = null
        var cancelled = false; var stop: CompletableDeferred<Unit>? = null; var beforeBinding: Exception? = null
        val result = CompletableDeferred<DirectUploadReceipt>()
        val facts = MutableStateFlow(UploadTransferFacts(8, multipartSizeBytes = 100, multipartAttemptedUpperBound = 10,
            writerStarted = true, writerRunning = true))
        fun fullBody() { facts.value = facts.value.copy(multipartAttemptedUpperBound = 100,
            requestBodyEndObserved = true, requestBodyEndBytes = 100) }
        fun ack() { fullBody(); result.complete(DirectUploadReceipt(8, 19)) }
        override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt {
            val hold = checkNotNull(retained.holdUpload(ready)); calls++
            original = expected; this.caption = caption; reply = replyId
            try { beforeBinding?.let { throw it }; onTransfer(facts); return result.await() }
            catch (cancelled: CancellationException) { this.cancelled = true; throw cancelled }
            finally {
                withContext(NonCancellable) { stop?.await() }
                facts.value = facts.value.copy(writerRunning = false, callbackFinished = true, stopped = true)
                hold.close()
            }
        }
        override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt = error("DM only")
    }
    private class Directs : DirectOperations {
        private val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "admin", true, true, true))
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), privacy = privacy))
        val calls = mutableListOf<String>()
        var sends = 0; var reads = 0; var blocks = 0; private var read = false
        var historyFailure: ApiFailure? = null; var readFailure: ApiFailure? = null; var blockFailure: ApiFailure? = null
        var readGate: CompletableDeferred<Unit>? = null; var contextGate: CompletableDeferred<Unit>? = null
        override suspend fun context(peerId: Long): DirectContext {
            calls += "context:$peerId"; contextGate?.await()
            return DirectContext(MessagePerson(peerId, "Peer"), DirectRelationship(false, true), privacy, DisplayPreferences(), null)
        }
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            calls += "history:$peerId"; historyFailure?.let { throw it }
            val row = DirectMessage(2, MessagePerson(peerId, "Peer"), MessagePerson(7, "Viewer"), "fixture",
                if (read) "2026-11-09T12:01:00Z" else null, "2026-11-09T12:00:00Z", false, false, null,
                false, false, null, emptyList(), emptyList(), null)
            return DirectPage(listOf(row), 2, false)
        }
        override suspend fun inbox() = DirectInbox(emptyList(), privacy, DisplayPreferences())
        override suspend fun search(prefix: String, limit: Int) = emptyList<MessagePerson>()
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long { sends++; return 19 }
        override suspend fun read(peerId: Long): Int { reads++; readFailure?.let { throw it }; readGate?.await(); read = true; return 1 }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship {
            blocks++; blockFailure?.let { throw it }; return DirectRelationship(blocked, !blocked)
        }
    }
}
