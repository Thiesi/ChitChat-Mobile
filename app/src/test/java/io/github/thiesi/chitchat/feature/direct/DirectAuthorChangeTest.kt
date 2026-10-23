package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.mutations.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectAuthorChangeTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Directs()
    private val changes = Changes(api)
    private lateinit var model: DirectThreadViewModel
    @Before fun setup() { Dispatchers.setMain(dispatcher); model = DirectThreadViewModel(api, mutations = changes) }
    @After fun teardown() { model.background(); changes.gate?.complete(Unit); Dispatchers.resetMain() }
    private fun TestScope.open(peer: Long = 8) {
        model.open(peer); model.foreground(); runCurrent(); assertTrue(model.state.value.loaded)
    }
    private fun choose(kind: AuthorChangeKind = AuthorChangeKind.EDIT): DirectAuthorRequest {
        val value = model.state.value
        assertTrue(model.beginAuthor(api.session.value, value.peerId, value.messages.first(), kind))
        return checkNotNull(model.state.value.authorDialog).request
    }

    @Test fun otherAuthorsUnknownPermissionsAndNonCanonicalInstancesCannotOpen() = runTest(dispatcher) {
        api.rows = listOf(row(10, outgoing = false, canEdit = true, canDelete = true)); open()
        val owner = api.session.value
        assertFalse(model.canBeginAuthor(owner, 8, model.state.value.messages.first(), AuthorChangeKind.DELETE))
        api.rows = listOf(row(10, canEdit = false, canDelete = false)); model.invalidate(); runCurrent()
        assertFalse(model.canBeginAuthor(owner, 8, model.state.value.messages.first(), AuthorChangeKind.EDIT))
        api.rows = listOf(row(10)); model.invalidate(); runCurrent()
        assertFalse(model.canBeginAuthor(owner, 8, row(10), AuthorChangeKind.EDIT))
        assertFalse(model.canBeginAuthor(owner, 9, model.state.value.messages.first(), AuthorChangeKind.EDIT))
    }
    @Test fun blockAndMuteDisableEditButNotPermittedAuthorDeletion() = runTest(dispatcher) {
        api.relationship = DirectRelationship(true, false)
        api.mute = RoomMute(1, "everywhere", null, null, "Fixture")
        api.session.value = api.session.value.copy(chatPolicy = ChatPolicy(emptySet(), api.mute, DisplayPreferences()))
        open(); val current = model.state.value.messages.first()
        assertFalse(model.canBeginAuthor(api.session.value, 8, current, AuthorChangeKind.EDIT))
        val request = choose(AuthorChangeKind.DELETE); model.submitAuthor(request); runCurrent()
        assertEquals(1, changes.deletes); assertTrue(model.state.value.messages.first().deleted)
        assertFalse(model.state.value.uncertainSend); assertNull(model.state.value.authorUnknown)
    }
    @Test fun newestConfirmedSessionMuteOverridesEarlierContext() = runTest(dispatcher) {
        open(); api.session.value = api.session.value.copy(chatPolicy = ChatPolicy(emptySet(),
            RoomMute(1, "everywhere", null, null, "Fixture"), DisplayPreferences()))
        assertFalse(model.canBeginAuthor(api.session.value, 8, model.state.value.messages.first(), AuthorChangeKind.EDIT))
        assertTrue(model.canBeginAuthor(api.session.value, 8, model.state.value.messages.first(), AuthorChangeKind.DELETE))
    }
    @Test fun modalPreservesOrdinaryDraftReplyAndSuppressesReadSendAndBlock() = runTest(dispatcher) {
        api.rows = listOf(row(10), row(11, outgoing = false, read = null))
        open(); val owner = api.session.value; val current = model.state.value.messages.first()
        assertTrue(model.replyTo(owner, 8, current)); val reply = model.state.value.composerReply
        model.updateDraft("Ordinary\ndraft"); val request = choose()
        model.updateAuthorBody(request, "Private proposal")
        assertFalse(model.updateDraft(owner, 8, "Stale composer callback"))
        model.cancelReply(owner, 8, checkNotNull(reply)); model.discardSavedDraft(8)
        model.send(); model.setBlocked(owner, 8, true)
        model.viewport(DirectAnchor(11, 0), setOf(11), 11, true); runCurrent()
        assertEquals("Ordinary\ndraft", model.state.value.draft); assertSame(reply, model.state.value.composerReply)
        assertEquals(0, api.sends); assertEquals(0, api.reads); assertEquals(0, api.blocks)
        assertFalse(model.state.value.atLatest)
    }
    @Test fun editIsLiteralAndPreservesNormalDraftAndSelectedReply() = runTest(dispatcher) {
        open(); model.updateDraft("Unsent answer")
        model.replyTo(api.session.value, 8, model.state.value.messages.first())
        val reply = model.state.value.composerReply; val request = choose()
        model.updateAuthorBody(request, "  /me literal\nsecond line  "); model.submitAuthor(request); runCurrent()
        assertEquals("/me literal\nsecond line", changes.body); assertEquals(1, changes.edits)
        assertEquals("/me literal\nsecond line", model.state.value.messages.first().body)
        assertEquals("Unsent answer", model.state.value.draft); assertSame(reply, model.state.value.composerReply)
        assertNull(model.state.value.authorDialog); assertNull(model.state.value.authorUnknown); assertEquals(0, api.sends)
    }
    @Test fun emptyOverlongAndUnchangedEditsNeverPost() = runTest(dispatcher) {
        open(); val request = choose()
        for ((body, error) in listOf(" \n\t\u0000" to "empty_message", "😀".repeat(4001) to "message_too_long",
            " Original " to "message_unchanged")) {
            model.updateAuthorBody(request, body); model.submitAuthor(request); runCurrent()
            assertEquals(error, model.state.value.authorDialog?.validation)
        }
        assertEquals(0, changes.edits)
    }
    @Test fun fourThousandUnicodeCodepointsAreAcceptedWithoutTruncation() = runTest(dispatcher) {
        open(); val request = choose(); val body = "😀".repeat(4000)
        model.updateAuthorBody(request, body); model.submitAuthor(request); runCurrent()
        assertEquals(body, changes.body); assertEquals(body, model.state.value.messages.first().body)
    }
    @Test fun changedCanonicalRevisionInvalidatesOldSaveAndOldDismissCannotCloseSuccessor() = runTest(dispatcher) {
        open(); val old = choose(); model.updateAuthorBody(old, "Old proposal")
        api.rows = listOf(row(10, body = "Remote edit", edited = "2026-10-20T10:01:00Z")); model.invalidate(); runCurrent()
        assertFalse(model.authorTargetCurrent(old)); model.submitAuthor(old); runCurrent(); assertEquals(0, changes.edits)
        model.dismissAuthor(old); val fresh = choose(); model.updateAuthorBody(old, "Stale callback"); model.dismissAuthor(old)
        assertSame(fresh, model.state.value.authorDialog?.request); assertEquals("Remote edit", model.state.value.authorDialog?.body)
    }
    @Test fun equivalentRefreshRebindsFreshPermissionsWithoutDroppingProposal() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Kept proposal")
        api.rows = listOf(row(10, read = "2026-10-20T10:01:00Z")); model.invalidate(); runCurrent()
        assertTrue(model.authorAvailable(request)); assertEquals("Kept proposal", model.state.value.authorDialog?.body)
        api.rows = listOf(row(10, canEdit = false)); model.invalidate(); runCurrent()
        assertTrue(model.authorTargetCurrent(request)); assertFalse(model.authorAvailable(request))
        model.submitAuthor(request); runCurrent(); assertEquals(0, changes.edits)
    }
    @Test fun acknowledgedDeletionRedactsVisibleQuotesBeforeFailedHistoryGet() = runTest(dispatcher) {
        api.rows = listOf(row(10), row(11, outgoing = false, reply = DirectReply("direct", 10, true,
            DirectReplyTarget(10, MessagePerson(7, "Member"), "Original", false))))
        open(); val request = choose(AuthorChangeKind.DELETE); changes.failGetAfterAck = true
        model.submitAuthor(request); runCurrent()
        assertTrue(model.state.value.messages.first().deleted); assertNull(model.state.value.messages.first().body)
        val quote = checkNotNull(model.state.value.messages.last().replyTo?.message)
        assertTrue(quote.deleted); assertEquals("", quote.body); assertTrue(model.state.value.stale)
        assertNull(model.state.value.authorUnknown); assertEquals("author_deleted", model.state.value.authorNotice)
        assertEquals(1, changes.deletes); assertFalse(model.state.value.uncertainSend)
    }
    @Test fun committedButUnconfirmedEditNeedsExplicitGetAndFreshChoiceNotReplay() = runTest(dispatcher) {
        open(); val owner = api.session.value; val old = choose(); model.updateAuthorBody(old, "Committed proposal")
        changes.failure = ApiFailure(FailureKind.HTTP, 500); changes.commitBeforeFailure = true
        model.submitAuthor(old); runCurrent(); val marker = checkNotNull(model.state.value.authorUnknown)
        assertFalse(model.authorAvailable(old)); assertEquals("Committed proposal", model.state.value.authorDialog?.body)
        model.invalidate(); runCurrent(); assertSame(marker, model.state.value.authorUnknown)
        model.checkAuthorHistory(owner, marker); runCurrent()
        assertNull(model.state.value.authorUnknown); assertTrue(checkNotNull(model.state.value.authorDialog).checked)
        model.submitAuthor(old); runCurrent(); assertEquals(1, changes.edits)
        assertTrue(model.reviewAuthorProposal(old)); val fresh = checkNotNull(model.state.value.authorDialog).request
        model.submitAuthor(fresh); runCurrent(); assertEquals("message_unchanged", model.state.value.authorDialog?.validation)
        assertEquals(1, changes.edits); changes.failure = null
        model.updateAuthorBody(fresh, "A different explicit choice"); model.submitAuthor(fresh); runCurrent()
        assertEquals(2, changes.edits); assertEquals("A different explicit choice", model.state.value.messages.first().body)
    }
    @Test fun forbiddenPostIsAlsoUncertainAndAFailedRecoveryGetCannotClearIt() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Proposal")
        changes.failure = ApiFailure(FailureKind.HTTP, 403, "forbidden"); model.submitAuthor(request); runCurrent()
        val marker = checkNotNull(model.state.value.authorUnknown); api.historyFailure = ApiFailure(FailureKind.HTTP, 500)
        model.checkAuthorHistory(api.session.value, marker); runCurrent()
        assertSame(marker, model.state.value.authorUnknown); assertEquals(1, changes.edits)
        assertEquals("Proposal", model.state.value.authorDialog?.body)
    }
    @Test fun closingAndBackgroundDropProposalButKeepOnlyOriginalPeerUncertainty() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Private proposal")
        changes.failure = ApiFailure(FailureKind.TIMEOUT); model.submitAuthor(request); runCurrent()
        val marker = checkNotNull(model.state.value.authorUnknown); model.dismissAuthor(request)
        assertNull(model.state.value.authorDialog); model.background(); model.foreground(); runCurrent()
        assertSame(marker, model.state.value.authorUnknown); assertNull(model.state.value.authorDialog)
        model.updateAuthorBody(request, "Old callback"); assertNull(model.state.value.authorDialog)
    }
    @Test fun authorGetDoesNotGrantExistingUncertainSendConsent() = runTest(dispatcher) {
        open(); model.updateDraft("Unknown send"); api.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        model.send(); runCurrent(); model.invalidate(); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertFalse(model.state.value.checkedUnknown)
        val request = choose(); model.updateAuthorBody(request, "Edit proposal"); changes.failure = ApiFailure(FailureKind.TIMEOUT)
        model.submitAuthor(request); runCurrent(); val marker = checkNotNull(model.state.value.authorUnknown)
        model.checkAuthorHistory(api.session.value, marker); runCurrent(); model.allowDuplicateRisk()
        assertTrue(model.state.value.uncertainSend); assertFalse(model.state.value.checkedUnknown)
        assertEquals("Unknown send", model.state.value.draft); assertEquals(1, api.sends)
    }
    @Test fun accountRetirementRejectsLatePostWithoutRestoringPrivateState() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Private proposal")
        changes.gate = CompletableDeferred(); changes.ignoreCancellation = true; model.submitAuthor(request); runCurrent()
        api.session.value = api.session.value.copy(generation = 2, user = SessionUser(9, "Replacement", listOf("member"), 1, false))
        runCurrent(); changes.gate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.peerId); assertNull(model.state.value.authorDialog); assertNull(model.state.value.authorUnknown)
        assertTrue(model.state.value.messages.isEmpty()); assertTrue(model.drafts.value.isEmpty())
        model.submitAuthor(request); model.updateAuthorBody(request, "Stale callback"); assertEquals(1, changes.edits)
    }
    @Test fun peerNavigationDuringPostRetainsOriginalIdsButNeverTheProposalOrLateResponse() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Private proposal")
        changes.gate = CompletableDeferred(); changes.ignoreCancellation = true; model.submitAuthor(request); runCurrent()
        model.open(9); runCurrent(); assertEquals(9L, model.state.value.peerId)
        changes.gate!!.complete(Unit); runCurrent(); assertEquals(9L, model.state.value.peerId)
        assertNull(model.state.value.authorUnknown); assertNull(model.state.value.authorDialog)
        model.open(8); runCurrent(); assertEquals(10L, model.state.value.authorUnknown?.messageId)
        assertNull(model.state.value.authorDialog); assertEquals(1, changes.edits)
    }
    @Test fun queuedInvalidationsWaitBehindAuthorPostAndNeverReplayIt() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Proposal")
        changes.gate = CompletableDeferred(); model.submitAuthor(request); runCurrent(); val before = api.histories
        repeat(3) { model.invalidate() }; runCurrent(); assertEquals(before, api.histories)
        assertTrue(model.state.value.authorWriting); changes.gate!!.complete(Unit); runCurrent()
        assertEquals(1, changes.edits); assertFalse(model.state.value.busy); assertEquals("Proposal", model.state.value.messages.first().body)
    }
    @Test fun pureQuoteUpdateNeverTouchesAnotherPeerOrEnablesUnavailableTargets() {
        val unavailable = row(11, reply = DirectReply("direct", 10, false, null))
        val otherPeer = row(12, peer = 9, reply = DirectReply("direct", 10, true,
            DirectReplyTarget(10, MessagePerson(7, "Member"), "Other", false)))
        val result = row(10, body = "New")
        val applied = applyDirectAuthorResult(listOf(unavailable, otherPeer), result)
        assertSame(unavailable, applied[0]); assertSame(otherPeer, applied[1])
    }
    @Test fun malformedAcknowledgmentRemainsUncertainInTheOriginalPeer() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Proposal"); changes.wrongPeerResult = true
        model.submitAuthor(request); runCurrent()
        assertEquals(8L, model.state.value.authorUnknown?.peerId); assertEquals(10L, model.state.value.authorUnknown?.messageId)
        assertEquals(FailureKind.INCOMPATIBLE, model.state.value.error?.kind); assertEquals(1, changes.edits)
    }
    @Test fun guestAndStaleOwnerCallbacksCannotOpenOrSubmit() = runTest(dispatcher) {
        open(); val owner = api.session.value; val original = model.state.value.messages.first(); val request = choose()
        api.session.value = api.session.value.copy(user = SessionUser(7, "Guest", listOf("guest"), 1, true)); runCurrent()
        assertFalse(model.beginAuthor(owner, 8, original, AuthorChangeKind.DELETE)); model.submitAuthor(request)
        assertNull(model.state.value.authorDialog); assertEquals(0, changes.edits); assertEquals(0, changes.deletes)
    }
    @Test fun oneHundredUnknownPeersNeverEvictAndExplicitGetReleasesCapacity() = runTest(dispatcher) {
        changes.failure = ApiFailure(FailureKind.HTTP, 500)
        for (peer in 100L..199L) {
            api.rows = api.rows + row(1000 + peer, peer = peer)
            open(peer); val request = choose(); model.updateAuthorBody(request, "Proposal $peer")
            model.submitAuthor(request); runCurrent(); assertNotNull(model.state.value.authorUnknown)
        }
        api.rows = api.rows + row(1200, peer = 200); open(200)
        assertTrue(model.authorCapacityFull())
        assertFalse(model.beginAuthor(api.session.value, 200, model.state.value.messages.first(), AuthorChangeKind.EDIT))
        assertTrue(model.state.value.authorLimit); assertEquals(100, changes.edits)
        open(100); val first = checkNotNull(model.state.value.authorUnknown)
        assertEquals(1100L, first.messageId); model.checkAuthorHistory(api.session.value, first); runCurrent()
        assertNull(model.state.value.authorUnknown); open(200); assertFalse(model.authorCapacityFull())
        assertTrue(model.canBeginAuthor(api.session.value, 200, model.state.value.messages.first(), AuthorChangeKind.EDIT))
        assertEquals(100, changes.edits)
    }
    @Test fun targetLookupCannotCancelAnActiveAuthorPostOrAdoptItsWindow() = runTest(dispatcher) {
        open(); val owner = api.session.value
        val target = io.github.thiesi.chitchat.core.targets.TargetRequest(owner,
            io.github.thiesi.chitchat.core.targets.MessageTarget.DirectMessage(8, 10))
        val snapshot = io.github.thiesi.chitchat.core.targets.TargetSnapshot.DirectMessage(target,
            api.context(8), api.history(8, null, 50))
        val request = choose(); model.updateAuthorBody(request, "Proposal"); changes.gate = CompletableDeferred()
        model.submitAuthor(request); runCurrent()
        assertFalse(model.canPrepareTarget(snapshot)); assertFalse(model.prepareTarget(snapshot)); assertFalse(model.adoptTarget(snapshot))
        model.targetDenied(target, ApiFailure(FailureKind.HTTP, 404, "message_not_found")); model.dismissAuthor(request)
        assertTrue(model.state.value.busy); assertSame(request, model.state.value.authorDialog?.request)
        changes.gate!!.complete(Unit); runCurrent()
        assertEquals(1, changes.edits); assertNull(model.state.value.authorUnknown)
        assertEquals("Proposal", model.state.value.messages.first().body)
    }
    @Test fun resourceDenialRetiresProposalAndPrivateRowsButNotDraftOrUnknownIds() = runTest(dispatcher) {
        open(); val owner = api.session.value; model.updateDraft("Ordinary draft")
        val request = choose(); model.updateAuthorBody(request, "Private proposal"); changes.failure = ApiFailure(FailureKind.HTTP, 500)
        model.submitAuthor(request); runCurrent(); val marker = checkNotNull(model.state.value.authorUnknown)
        model.targetDenied(io.github.thiesi.chitchat.core.targets.TargetRequest(owner,
            io.github.thiesi.chitchat.core.targets.MessageTarget.DirectMessage(8, 10)), ApiFailure(FailureKind.HTTP, 403, "forbidden"))
        assertNull(model.state.value.authorDialog); assertTrue(model.state.value.messages.isEmpty()); assertFalse(model.state.value.loaded)
        assertEquals("Ordinary draft", model.state.value.draft); assertSame(marker, model.state.value.authorUnknown)
        assertEquals(1, changes.edits); assertEquals(0, api.sends)
    }
    @Test fun retiredUnknownMarkerCannotClearASameIdSuccessor() = runTest(dispatcher) {
        open(); val owner = api.session.value; changes.failure = ApiFailure(FailureKind.TIMEOUT)
        val first = choose(); model.updateAuthorBody(first, "First proposal"); model.submitAuthor(first); runCurrent()
        val old = checkNotNull(model.state.value.authorUnknown); model.checkAuthorHistory(owner, old); runCurrent()
        model.dismissAuthor(first); val second = choose(); model.updateAuthorBody(second, "Second proposal")
        model.submitAuthor(second); runCurrent(); val current = checkNotNull(model.state.value.authorUnknown); val histories = api.histories
        model.checkAuthorHistory(owner, old); runCurrent()
        assertSame(current, model.state.value.authorUnknown); assertEquals(histories, api.histories); assertEquals(2, changes.edits)
    }
    @Test fun laterCanonicalTombstoneInAnEditAcknowledgmentIsConfirmedNotReplayed() = runTest(dispatcher) {
        open(); val request = choose(); model.updateAuthorBody(request, "Proposal"); changes.concurrentDelete = true
        model.submitAuthor(request); runCurrent()
        assertTrue(model.state.value.messages.first().deleted); assertNull(model.state.value.messages.first().body)
        assertNull(model.state.value.authorUnknown); assertNull(model.state.value.authorDialog); assertEquals(1, changes.edits)
    }

    private class Directs : DirectOperations {
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "super_admin", true, true, false))
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Member", listOf("member"), 1, false), privacy = privacy,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())))
        var rows = listOf(row(10)); var relationship = DirectRelationship(false, true); var mute: RoomMute? = null
        var historyFailure: ApiFailure? = null; var sendFailure: ApiFailure? = null
        var histories = 0; var sends = 0; var reads = 0; var blocks = 0
        override suspend fun context(peerId: Long) = DirectContext(MessagePerson(peerId, "Peer"), relationship,
            privacy, DisplayPreferences(), mute)
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            histories++; historyFailure?.let { throw it }
            val page = rows.filter { peerId in setOf(it.sender.id, it.recipient.id) && (beforeId == null || it.id < beforeId) }.takeLast(limit)
            return DirectPage(page, page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun inbox() = DirectInbox(emptyList(), privacy, DisplayPreferences())
        override suspend fun search(prefix: String, limit: Int) = emptyList<MessagePerson>()
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long { sends++; sendFailure?.let { throw it }; return 99 }
        override suspend fun read(peerId: Long): Int { reads++; return 0 }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship {
            blocks++; relationship = DirectRelationship(blocked, !blocked); return relationship
        }
    }
    private class Changes(private val api: Directs) : MessageMutationOperations {
        var edits = 0; var deletes = 0; var body: String? = null; var failure: ApiFailure? = null
        var commitBeforeFailure = false; var failGetAfterAck = false; var wrongPeerResult = false; var concurrentDelete = false
        var gate: CompletableDeferred<Unit>? = null; var ignoreCancellation = false
        private suspend fun mutate(original: DirectMessage, text: String?, delete: Boolean): DirectMessage {
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
            val changed = DirectMessage(original.id, original.sender, original.recipient, text, original.readAt,
                original.createdAt, original.outgoing, delete, "2026-10-20T10:05:00Z", !delete, !delete,
                original.replyTo.takeUnless { delete }, if (delete) emptyList() else original.mentions,
                original.reactions, original.attachment.takeUnless { delete })
            if (failure == null || commitBeforeFailure) api.rows = api.rows.map { if (it.id == original.id) changed else it }
            failure?.let { throw it }
            if (failGetAfterAck) api.historyFailure = ApiFailure(FailureKind.HTTP, 500)
            return if (wrongPeerResult) row(original.id, peer = 9, body = text) else changed
        }
        override suspend fun editDirect(expected: SessionState, original: DirectMessage, body: String): DirectMessage {
            edits++; this.body = body.phpTrim(); return mutate(original, this.body.takeUnless { concurrentDelete }, concurrentDelete)
        }
        override suspend fun deleteDirect(expected: SessionState, original: DirectMessage): DirectMessage {
            deletes++; return mutate(original, null, true)
        }
        override suspend fun editRoom(expected: SessionState, original: RoomMessage, body: String): RoomMessage = error("Not a room operation")
        override suspend fun deleteRoom(expected: SessionState, original: RoomMessage): RoomMessage = error("Not a room operation")
    }
    companion object {
        private fun row(id: Long, peer: Long = 8, body: String? = "Original", outgoing: Boolean = true,
            read: String? = "2026-10-20T10:00:00Z", edited: String? = null, canEdit: Boolean = outgoing,
            canDelete: Boolean = outgoing, reply: DirectReply? = null) = DirectMessage(id,
            MessagePerson(if (outgoing) 7 else peer, "Sender"), MessagePerson(if (outgoing) peer else 7, "Recipient"),
            body, read, "2026-10-20T10:00:00Z", outgoing, false, edited, canEdit, canDelete, reply, emptyList(), emptyList(), null)
    }
}
