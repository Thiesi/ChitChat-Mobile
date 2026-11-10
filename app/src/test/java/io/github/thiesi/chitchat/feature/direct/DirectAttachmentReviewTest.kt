package io.github.thiesi.chitchat.feature.direct

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.mutations.*
import io.github.thiesi.chitchat.core.reactions.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
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
class DirectAttachmentReviewTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Directs()
    private val wire = Uploads()
    private val writes = Writes()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val store = ViewModelStore()
    private lateinit var model: DirectThreadViewModel
    private var entry = "entry-1"
    private var entryActive = true

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        model = DirectThreadViewModel(api, mutations = writes, reactions = writes, attachmentBudget = budget, uploads = wire)
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
    private fun open(peer: Long = 8) {
        model.open(peer); model.foreground(); dispatcher.scheduler.runCurrent()
    }
    private suspend fun unknown(caption: String = "caption", reply: Boolean = false): DirectAttachmentDraft {
        open()
        assertTrue(model.selectAttachment(api.session.value, 8, entry,
            PickedAttachmentRead("private-file", null) { ByteArrayInputStream("prepared".toByteArray()) },
            AttachmentPickSource.FILE) { entryActive })
        val ready = model.drafts.first { it[8]?.attachment?.phase == DirectAttachmentPhase.READY }.getValue(8).attachment!!
        dispatcher.scheduler.runCurrent()
        model.updateDraft(caption)
        if (reply) assertTrue(model.replyTo(api.session.value, 8, model.state.value.messages.first()))
        assertTrue(model.sendAttachment(api.session.value, 8, entry, ready) { entryActive })
        dispatcher.scheduler.runCurrent()
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls)
        return view()
    }
    private fun view() = model.drafts.value.getValue(8).attachment!!
    private fun review() = checkNotNull(model.state.value.attachmentReview)
    private fun check(original: DirectAttachmentDraft = view()): Boolean =
        model.checkAttachmentHistory(api.session.value, 8, entry, original) { entryActive }
    private fun accept(original: DirectAttachmentReview = review()): Boolean =
        model.acceptAttachmentDuplicateRisk(api.session.value, 8, entry, original) { entryActive }
    private fun confirm(original: DirectAttachmentReview = review(), evidence: DirectAttachmentEvidence = original.evidence.single()): Boolean =
        model.confirmAttachmentEvidence(api.session.value, 8, entry, original, evidence) { entryActive }
    private fun row(id: Long = 10, sender: Long? = 7, peer: Long = 8, digest: String? = view().upload!!.sha256,
        time: String = Instant.ofEpochMilli(view().upload!!.startedAtMillis + 1000).toString(), deleted: Boolean = false,
        outgoing: Boolean = true) = DirectMessage(id, MessagePerson(sender ?: 0, "Viewer"), MessagePerson(peer, "Peer"),
            "caption", null, time, outgoing, deleted, null, true, true, null, emptyList(), emptyList(),
            digest?.let { MessageAttachment(id, "private-file", "application/octet-stream", 8, it, false) })

    @Test fun ordinaryHistoryAndForegroundNeverCreateFileConsent() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row())
        model.refresh(); runCurrent(); model.latest(); runCurrent()
        model.background(); open(); runCurrent()
        assertNull(model.state.value.attachmentReview)
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertFalse(model.canSend()); assertEquals(1, wire.calls)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun noMatchNeedsExplicitRiskChoiceAndNeverPosts() = runTest(dispatcher) {
        unknown(); api.rows = emptyList(); api.mayHaveOlder = true
        val before = api.calls.size
        assertTrue(check()); runCurrent()
        assertEquals(listOf("context:8", "history:8"), api.calls.drop(before))
        val checked = review()
        assertEquals(DirectAttachmentReviewPhase.DUPLICATE_RISK, checked.phase)
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls); assertEquals(0, api.sends)
        entryActive = false; assertFalse(accept(checked))
        entryActive = true; assertTrue(accept(checked))
        assertEquals(DirectAttachmentPhase.READY, view().phase)
        assertEquals("caption", model.state.value.draft)
        assertNull(model.state.value.attachmentReview)
        assertFalse(accept(checked)); assertEquals(1, wire.calls)
    }

    @Test fun hashMatchRequiresAPersonAndClearsOnlyOriginalContent() = runTest(dispatcher) {
        unknown(reply = true); api.rows = listOf(row())
        assertTrue(check()); runCurrent()
        assertEquals(DirectAttachmentReviewPhase.EVIDENCE, review().phase)
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertEquals("private-file", review().evidence.single().filename)
        assertEquals("DirectAttachmentReview", review().toString())
        assertEquals("DirectAttachmentEvidence", review().evidence.single().toString())
        assertFalse(accept())
        assertTrue(confirm())
        assertTrue(model.drafts.value.isEmpty())
        assertEquals("", model.state.value.draft); assertNull(model.state.value.composerReply)
        assertEquals(0L, budget.reservedBytes)
        assertEquals(1, wire.calls); assertEquals(0, api.sends)
        assertEquals(listOf(10L), model.state.value.messages.map { it.id })
    }

    @Test fun evidenceRequiresExactOwnPartiesDigestAndStrictlyLaterParseableTimestamp() = runTest(dispatcher) {
        unknown()
        val before = Instant.ofEpochMilli(view().upload!!.startedAtMillis - 1).toString()
        val equal = Instant.ofEpochMilli(view().upload!!.startedAtMillis).toString()
        val matching = row()
        api.rows = listOf(matching, row(11, sender = 8), row(12, sender = null), row(13, peer = 43),
            row(14, digest = "0".repeat(64)), row(15, digest = ""), row(16, digest = null), row(17, time = before),
            row(18, time = equal), row(19, time = "not-a-date"), row(20, deleted = true),
            row(21, outgoing = false), row(0), row(22, sender = 8, peer = 7, outgoing = false))
        assertTrue(check()); runCurrent()
        assertEquals(1, review().evidence.size)
        assertSame(matching, review().evidence.single().original)
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
    }

    @Test fun rejectionCreatesANewRiskChoiceAndRetiresTheEvidencePress() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); assertTrue(check()); runCurrent()
        val evidence = review()
        assertTrue(model.rejectAttachmentEvidence(api.session.value, 8, entry, evidence) { true })
        assertNotSame(evidence, review()); assertFalse(confirm(evidence))
        assertFalse(accept(evidence)); assertTrue(accept())
        assertEquals(DirectAttachmentPhase.READY, view().phase); assertEquals(1, wire.calls)
    }

    @Test fun failedHistoryOffersNeitherEvidenceNorRiskConsent() = runTest(dispatcher) {
        unknown(); api.historyFailure = ApiFailure(FailureKind.NETWORK)
        assertTrue(check()); runCurrent()
        assertEquals(DirectAttachmentReviewPhase.FAILED, review().phase)
        assertEquals(FailureKind.NETWORK, review().error?.kind)
        assertFalse(accept()); assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(budget.reservedBytes > 0); assertEquals(1, wire.calls)
    }

    @Test fun preActionRevalidationFailureCannotLeaveCheckingStuck() = runTest(dispatcher) {
        unknown(); assertTrue(check())
        api.session.value = api.session.value.copy(refreshRequired = true)
        runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals(DirectAttachmentReviewPhase.FAILED, review().phase)
        assertFalse(accept()); assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
    }

    @Test fun closingAnInFlightCheckCancelsOnlyItsGetWithoutGrantingConsent() = runTest(dispatcher) {
        unknown(); api.historyGate = CompletableDeferred()
        assertTrue(check()); runCurrent(); val checking = review()
        assertEquals(DirectAttachmentReviewPhase.CHECKING, checking.phase)
        assertTrue(model.closeAttachmentReview(api.session.value, 8, entry, checking) { true })
        runCurrent()
        assertNull(model.state.value.attachmentReview); assertFalse(model.state.value.busy)
        api.historyGate!!.complete(Unit); runCurrent()
        assertFalse(accept(checking)); assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls)
    }

    @Test fun configurationWaitsForFreshEntryProofWithoutReplayingTheGet() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); api.historyGate = CompletableDeferred()
        val before = api.calls.count { it == "history:8" }
        assertTrue(check()); runCurrent(); entryActive = false
        assertNull(model.visibleAttachmentReview(api.session.value, 8, entry) { entryActive })
        api.historyGate!!.complete(Unit); runCurrent()
        val completed = review()
        assertFalse(confirm(completed))
        entryActive = true
        assertSame(completed, model.visibleAttachmentReview(api.session.value, 8, entry) { entryActive })
        assertEquals(before + 1, api.calls.count { it == "history:8" })
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
        assertTrue("Closing private choices must remain possible during revalidation", model.closeAttachmentReview(api.session.value, 8, entry, completed) { true })
        assertNull(model.state.value.attachmentReview)
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase); assertEquals(1, wire.calls)
    }

    @Test fun departedEntryCannotPublishALateCheckIntoAnotherPeer() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); api.historyGate = CompletableDeferred(); api.nonCancellableHistory = true
        assertTrue(check()); runCurrent(); val checking = review()
        model.leavePane(); entry = "entry-2"; open(43)
        assertEquals(43L, model.state.value.context?.peer?.id)
        api.historyGate!!.complete(Unit); runCurrent()
        assertEquals(43L, model.state.value.peerId); assertNull(model.state.value.attachmentReview)
        assertFalse(accept(checking)); assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertEquals(1, wire.calls)
    }

    @Test fun genuineBackgroundRetiresEvidenceButKeepsThePrivateUnknownFile() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); assertTrue(check()); runCurrent(); val old = review()
        model.background(); runCurrent(); entry = "entry-returned"; open()
        assertNull(model.state.value.attachmentReview); assertFalse(confirm(old))
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun retiredOwnerCannotRestorePrivateEvidenceFromItsLateGet() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); api.historyGate = CompletableDeferred(); api.nonCancellableHistory = true
        assertTrue(check()); runCurrent(); val old = review()
        api.session.value = api.session.value.copy(generation = 2); runCurrent(); open(43)
        api.historyGate!!.complete(Unit); runCurrent()
        assertTrue(model.drafts.value.isEmpty()); assertEquals(0L, budget.reservedBytes)
        assertNull(model.state.value.attachmentReview); assertFalse(accept(old))
        assertEquals(43L, model.state.value.context?.peer?.id)
    }

    @Test fun aNewExplicitCheckRetiresEveryOldChoiceAndClose() = runTest(dispatcher) {
        unknown(); assertTrue(check()); runCurrent(); val old = review()
        assertTrue(check()); runCurrent(); val fresh = review()
        assertNotSame(old.request, fresh.request)
        assertFalse(accept(old))
        assertFalse(model.closeAttachmentReview(api.session.value, 8, entry, old) { true })
        assertSame(fresh, review()); assertTrue(accept(fresh)); assertEquals(1, wire.calls)
    }

    @Test fun ordinaryRefreshRetiresCheckedChoicesAndDoesNotUnlockTheFile() = runTest(dispatcher) {
        unknown(); assertTrue(check()); runCurrent(); val old = review()
        model.refresh(); runCurrent()
        assertNull(model.state.value.attachmentReview); assertFalse(accept(old))
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
    }

    @Test fun retryAfterFailureIsANewExplicitGetWithNoPost() = runTest(dispatcher) {
        unknown(); api.historyFailure = ApiFailure(FailureKind.TIMEOUT)
        assertTrue(check()); runCurrent(); val failed = review()
        api.historyFailure = null; assertTrue(check()); runCurrent()
        assertNotSame(failed.request, review().request); assertFalse(accept(failed))
        assertEquals(DirectAttachmentReviewPhase.DUPLICATE_RISK, review().phase)
        assertEquals(1, wire.calls)
    }

    @Test fun canonicalReplacementRetiresAnOldEvidenceConfirmation() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); assertTrue(check()); runCurrent(); val old = review()
        api.rows = listOf(row(deleted = true)); model.refresh(); runCurrent()
        assertFalse(confirm(old)); assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(budget.reservedBytes > 0)
    }

    @Test fun confirmedEvidencePreservesEditAwayAndBackAndTheReply() = runTest(dispatcher) {
        unknown(reply = true); val originalReply = model.state.value.composerReply
        model.updateDraft("other"); model.updateDraft("caption")
        api.rows = listOf(row()); assertTrue(check()); runCurrent(); assertTrue(confirm())
        assertEquals("caption", model.state.value.draft); assertSame(originalReply, model.state.value.composerReply)
        assertNull(model.drafts.value.getValue(8).attachment); assertEquals(0L, budget.reservedBytes)
        assertEquals(1, wire.calls)
    }

    @Test fun wholeDraftDiscardRetiresAPendingCheckAndItsLateResult() = runTest(dispatcher) {
        unknown(); api.historyGate = CompletableDeferred(); api.nonCancellableHistory = true
        assertTrue(check()); runCurrent(); val old = review()
        assertTrue(model.discardSavedDraft(model.drafts.value.getValue(8)) { true }); runCurrent()
        assertTrue(model.drafts.value.isEmpty()); assertEquals(0L, budget.reservedBytes)
        assertFalse(model.state.value.busy); assertNull(model.state.value.attachmentReview)
        api.historyGate!!.complete(Unit); runCurrent()
        assertTrue(model.drafts.value.isEmpty()); assertFalse(accept(old))
    }

    @Test fun staleEntryCannotStartDisplayOrConsumeACheck() = runTest(dispatcher) {
        val original = unknown(); val before = api.calls.size
        entryActive = false; assertFalse(check(original)); assertEquals(before, api.calls.size)
        entryActive = true; assertTrue(check()); runCurrent(); val checked = review()
        assertNull(model.visibleAttachmentReview(api.session.value, 8, "other-entry") { true })
        assertFalse(model.acceptAttachmentDuplicateRisk(api.session.value, 8, "other-entry", checked) { true })
        entryActive = false; assertFalse(accept(checked)); entryActive = true; assertTrue(accept(checked))
    }

    @Test fun reviewSuppressesReadAttentionAndComposerActionsAndCloseIsNotReadProof() = runTest(dispatcher) {
        unknown()
        val incoming = row(11, sender = 8, peer = 7, outgoing = false)
        api.rows = listOf(row(), incoming)
        assertTrue(check()); runCurrent()
        val checked = review(); val messages = model.state.value.messages
        assertEquals(setOf(11L), model.attention.value.getValue(8).ids)
        model.viewport(DirectAnchor(11, 0), setOf(11), 11, true); runCurrent()
        assertEquals(0, api.reads); assertFalse(model.state.value.atLatest)
        assertEquals(setOf(11L), model.attention.value.getValue(8).ids)
        assertFalse(model.updateDraft(api.session.value, 8, "changed"))
        assertFalse(model.canReplyTo(api.session.value, 8, messages.first())); assertFalse(model.canSend())
        assertFalse(model.canBeginAuthor(api.session.value, 8, messages.first(), AuthorChangeKind.EDIT))
        assertFalse(model.canBeginReactions(api.session.value, 8, messages.first()))
        model.setBlocked(true); model.retryRead(); model.allowDuplicateRisk(); model.older(); runCurrent()
        assertEquals(0, api.blocks); assertEquals(0, api.reads)
        assertSame(checked, review())
        assertTrue(model.closeAttachmentReview(api.session.value, 8, entry, checked) { true }); runCurrent()
        assertEquals(0, api.reads); assertFalse(model.state.value.atLatest)
        assertEquals(setOf(11L), model.attention.value.getValue(8).ids)
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        model.viewport(DirectAnchor(11, 0), setOf(11), 11, true); runCurrent()
        assertEquals("Only a fresh viewport after close grants the existing read proof", 1, api.reads)
    }

    @Test fun currentPrivacyIsRequiredBeforeCheckDisplayAndConsentButNeverToClose() = runTest(dispatcher) {
        unknown(); val before = api.calls.toList()
        api.session.value = api.session.value.copy(privacy = null); runCurrent()
        assertFalse(check()); assertEquals(before, api.calls)
        api.session.value = api.session.value.copy(privacy = api.privacy); runCurrent()
        assertTrue(check()); runCurrent(); val completed = review()
        api.session.value = api.session.value.copy(privacy = null); runCurrent()
        assertNull(model.visibleAttachmentReview(api.session.value, 8, entry) { true })
        assertFalse(accept(completed))
        assertTrue(model.closeAttachmentReview(api.session.value, 8, entry, completed) { true })
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase); assertEquals(1, wire.calls)
    }

    @Test fun privacyLostWhileHistoryIsHeldCannotPublishEvidenceOrRiskConsent() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); api.historyGate = CompletableDeferred()
        assertTrue(check()); runCurrent()
        api.session.value = api.session.value.copy(privacy = null); runCurrent()
        api.historyGate!!.complete(Unit); runCurrent()
        assertEquals(DirectAttachmentReviewPhase.FAILED, review().phase)
        assertEquals(FailureKind.INCOMPATIBLE, review().error?.kind)
        assertTrue(review().evidence.isEmpty()); assertFalse(accept())
        assertEquals(DirectAttachmentPhase.UNKNOWN, view().phase)
        assertTrue(model.state.value.messages.isEmpty()); assertEquals(1, wire.calls)
    }

    @Test fun fileHistoryDoesNotClearAnUncertainBlockAndConfirmationIsNotABlockRetry() = runTest(dispatcher) {
        unknown(); api.blockFailure = ApiFailure(FailureKind.NETWORK)
        model.setBlocked(true); runCurrent(); assertTrue(model.state.value.uncertainBlock)
        api.rows = listOf(row()); assertTrue(check()); runCurrent()
        assertTrue(model.state.value.uncertainBlock); assertTrue(confirm())
        assertTrue(model.state.value.uncertainBlock); assertEquals(1, api.blocks)
        assertEquals(1, wire.calls); assertEquals(0, api.reads)
    }

    @Test fun fileRiskConsentDoesNotClearAPreviouslyUncertainWholePeerRead() = runTest(dispatcher) {
        open(); api.readFailure = ApiFailure(FailureKind.NETWORK)
        model.viewport(DirectAnchor(2, 0), setOf(2), 2, true); runCurrent()
        assertTrue(model.state.value.uncertainRead); assertEquals(1, api.reads)
        unknown(); api.rows = emptyList(); assertTrue(check()); runCurrent()
        model.retryRead(); runCurrent(); assertEquals(1, api.reads)
        assertTrue(accept()); runCurrent()
        assertTrue(model.state.value.uncertainRead); assertEquals(1, api.reads)
        assertEquals(DirectAttachmentPhase.READY, view().phase); assertEquals(1, wire.calls)
    }

    @Test fun blockedPeerHistoryCanOfferEvidenceWithoutGrantingSendPermission() = runTest(dispatcher) {
        unknown(); api.relationship = DirectRelationship(true, false)
        api.rows = emptyList(); assertTrue(check()); runCurrent(); assertTrue(accept())
        assertEquals(DirectAttachmentPhase.READY, view().phase)
        assertFalse(model.sendAttachment(api.session.value, 8, entry, view()) { true })
        assertEquals(1, wire.calls); assertEquals(0, api.blocks)
    }

    @Test fun aLaterExplicitSendHasANewAttemptAndRetiresOldDraftAndReviewChoices() = runTest(dispatcher) {
        unknown(); val oldDraft = model.drafts.value.getValue(8); val oldView = view()
        assertTrue(check()); runCurrent(); val oldReview = review(); assertTrue(accept())
        assertTrue(model.sendAttachment(api.session.value, 8, entry, view()) { true }); runCurrent()
        assertEquals(2, wire.calls); assertNotSame(oldView.upload, view().upload)
        assertFalse(check(oldView)); assertFalse(accept(oldReview))
        assertFalse(model.discardSavedDraft(oldDraft) { true })
        assertTrue(check()); runCurrent(); assertTrue(accept())
        assertEquals(DirectAttachmentPhase.READY, view().phase); assertEquals(2, wire.calls)
    }

    @Test fun evidenceChoiceMustBelongToTheExactCurrentReview() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row(), row(11)); assertTrue(check()); runCurrent()
        val old = review(); val choice = old.evidence.first()
        assertFalse(confirm(old, DirectAttachmentEvidence(choice.original)))
        assertTrue(check()); runCurrent(); val fresh = review()
        assertFalse(confirm(fresh, choice)); assertFalse(confirm(old, choice))
        assertTrue(confirm(fresh, fresh.evidence.last())); assertEquals(0L, budget.reservedBytes)
        assertNull(model.state.value.confirmedSendId); assertEquals(1, wire.calls)
    }

    @Test fun explicitFileReviewPreservesIndependentAuthorAndReactionUnknownMarkers() = runTest(dispatcher) {
        unknown(); api.rows = listOf(row()); model.refresh(); runCurrent()
        val own = model.state.value.messages.single()
        assertTrue(model.beginAuthor(api.session.value, 8, own, AuthorChangeKind.EDIT))
        val author = model.state.value.authorDialog!!.request
        model.updateAuthorBody(author, "new caption"); model.submitAuthor(author); runCurrent()
        val authorUnknown = checkNotNull(model.state.value.authorUnknown)
        model.dismissAuthor(author); model.refresh(); runCurrent()
        assertTrue(model.beginReactions(api.session.value, 8, model.state.value.messages.single()))
        val reaction = model.state.value.reactionDialog!!.request
        model.setReaction(reaction, ReactionEmoji.HEART, true); runCurrent()
        val reactionUnknown = checkNotNull(model.state.value.reactionUnknown)
        model.dismissReactions(reaction)
        assertTrue(check()); runCurrent(); val before = api.calls.toList()
        model.checkAuthorHistory(api.session.value, authorUnknown)
        model.checkReactionHistory(api.session.value, reactionUnknown); runCurrent()
        assertEquals(before, api.calls); assertTrue(confirm())
        assertSame(authorUnknown, model.state.value.authorUnknown)
        assertSame(reactionUnknown, model.state.value.reactionUnknown)
        assertEquals(1, writes.authors); assertEquals(1, writes.reactions)
        assertEquals(1, wire.calls); assertEquals(0, api.sends)
    }

    private class Writes : MessageMutationOperations, MessageReactionOperations {
        var authors = 0; var reactions = 0
        override suspend fun editDirect(expected: SessionState, original: DirectMessage, body: String): DirectMessage {
            authors++; throw ApiFailure(FailureKind.NETWORK)
        }
        override suspend fun deleteDirect(expected: SessionState, original: DirectMessage): DirectMessage = error("Not invoked")
        override suspend fun editRoom(expected: SessionState, original: RoomMessage, body: String): RoomMessage = error("DM only")
        override suspend fun deleteRoom(expected: SessionState, original: RoomMessage): RoomMessage = error("DM only")
        override suspend fun setDirect(expected: SessionState, original: DirectMessage, emoji: ReactionEmoji, selected: Boolean): List<MessageReaction> {
            reactions++; throw ApiFailure(FailureKind.NETWORK)
        }
        override suspend fun setRoom(expected: SessionState, original: RoomMessage, emoji: ReactionEmoji, selected: Boolean): List<MessageReaction> = error("DM only")
    }

    private class Uploads : AttachmentUploadOperations {
        var calls = 0
        override suspend fun direct(expected: SessionState, peerId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): DirectUploadReceipt {
            val hold = checkNotNull(retained.holdUpload(ready)); calls++
            val facts = MutableStateFlow(UploadTransferFacts(8, multipartSizeBytes = 100, multipartAttemptedUpperBound = 100,
                writerStarted = true, writerRunning = true, requestBodyEndObserved = true, requestBodyEndBytes = 100))
            try { onTransfer(facts); throw ApiFailure(FailureKind.NETWORK) }
            finally {
                facts.value = facts.value.copy(writerRunning = false, callbackFinished = true, stopped = true)
                hold.close()
            }
        }
        override suspend fun room(expected: SessionState, roomId: Long, retained: RetainedAttachmentPreparation,
            ready: AttachmentPreparation.Ready, caption: String, replyId: Long?,
            onTransfer: (StateFlow<UploadTransferFacts>) -> Unit): RoomUploadReceipt = error("DM only")
    }

    private class Directs : DirectOperations {
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "admin", true, true, true))
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), privacy = privacy,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())))
        val calls = mutableListOf<String>()
        var rows: List<DirectMessage>? = null
        var historyFailure: ApiFailure? = null
        var historyGate: CompletableDeferred<Unit>? = null
        var nonCancellableHistory = false
        var mayHaveOlder = false
        var sends = 0; var reads = 0; var blocks = 0
        var relationship = DirectRelationship(false, true)
        var readFailure: ApiFailure? = null; var blockFailure: ApiFailure? = null
        override suspend fun context(peerId: Long): DirectContext {
            calls += "context:$peerId"
            return DirectContext(MessagePerson(peerId, "Peer"), relationship, privacy, DisplayPreferences(), null)
        }
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            calls += "history:$peerId"
            if (peerId == 8L) {
                if (nonCancellableHistory) withContext(NonCancellable) { historyGate?.await() } else historyGate?.await()
                historyFailure?.let { throw it }
            }
            val messages = rows?.takeIf { peerId == 8L } ?: listOf(DirectMessage(2, MessagePerson(peerId, "Peer"),
                MessagePerson(7, "Viewer"), "fixture", null, "2026-11-09T12:00:00Z", false, false, null,
                false, false, null, emptyList(), emptyList(), null))
            return DirectPage(messages, messages.firstOrNull()?.id, mayHaveOlder)
        }
        override suspend fun inbox() = DirectInbox(emptyList(), privacy, DisplayPreferences())
        override suspend fun search(prefix: String, limit: Int) = emptyList<MessagePerson>()
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long { sends++; return 19 }
        override suspend fun read(peerId: Long): Int { reads++; readFailure?.let { throw it }; return 1 }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship {
            blocks++; blockFailure?.let { throw it }; return DirectRelationship(blocked, !blocked)
        }
    }
}
