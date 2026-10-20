package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
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
class DirectStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Directs()
    private lateinit var thread: DirectThreadViewModel
    private lateinit var inbox: DirectInboxViewModel
    @Before fun start() { Dispatchers.setMain(dispatcher); thread = DirectThreadViewModel(api); inbox = DirectInboxViewModel(api) }
    @After fun stop() {
        thread.background(); inbox.background(); api.gate?.complete(Unit); api.searchGate?.complete(Unit)
        Dispatchers.resetMain()
    }
    private fun TestScope.open() { thread.open(8); thread.foreground(); runCurrent(); assertTrue(thread.state.value.loaded) }

    @Test fun wholePeerReadRefetchesAndKeepsUnrenderedRacingArrivalSeparateFromServerUnread() = runTest(dispatcher) {
        open()
        api.onRead = {
            api.rows += row(19)
            thread.viewport(DirectAnchor(18, 0), setOf(18), null, false)
        }
        thread.viewport(DirectAnchor(18, 0), setOf(18), 18, true); runCurrent()
        assertEquals(1, api.reads); assertEquals(listOf(18L, 19L), thread.state.value.messages.map { it.id })
        assertTrue(thread.state.value.messages.all { it.readAt != null })
        assertEquals(setOf(19L), thread.attention.value[8]?.ids)
        assertTrue(thread.attention.value[8]?.additionalMayBeRead == true)
        thread.viewport(DirectAnchor(19, 0), setOf(19), 19, true); runCurrent()
        assertTrue(thread.attention.value[8]?.ids.isNullOrEmpty()); assertEquals(1, api.reads)
        thread.acknowledgeAdditionalRead(); assertNull(thread.attention.value[8])
    }

    @Test fun noReadBeforeActualViewportOrAtAnOlderWindow() = runTest(dispatcher) {
        api.rows = (1L..130L).map { row(it) }; open(); assertEquals(0, api.reads)
        thread.viewport(DirectAnchor(90, 0), setOf(90), null, false); runCurrent(); assertEquals(0, api.reads)
        repeat(10) { thread.older(); runCurrent() }
        thread.viewport(DirectAnchor(1, 0), setOf(1), null, false); runCurrent(); assertEquals(0, api.reads)
    }

    @Test fun confirmedSendClearsDraftEvenIfFollowingCanonicalGetFails() = runTest(dispatcher) {
        open(); thread.updateDraft("Known send fixture"); api.failAfterSend = true
        thread.send(); runCurrent()
        assertEquals(1, api.sends); assertEquals(99L, thread.state.value.confirmedSendId)
        assertEquals("", thread.state.value.draft); assertFalse(thread.state.value.uncertainSend)
        assertTrue(thread.state.value.stale); assertNotNull(thread.state.value.error)
        assertFalse(thread.state.value.messages.any { it.id == 99L }); assertNull(thread.drafts.value[8])
    }

    @Test fun automaticHintsNeverGrantUnknownDeliveryConsentOrReplayThePost() = runTest(dispatcher) {
        open(); thread.updateDraft("Unknown fixture"); api.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        thread.send(); runCurrent(); assertTrue(thread.state.value.uncertainSend)
        thread.invalidate(); runCurrent(); assertFalse(thread.state.value.checkedUnknown); assertFalse(thread.canSend())
        assertEquals("Unknown fixture", thread.state.value.draft); assertEquals(1, api.sends)
        thread.refresh(); runCurrent(); assertTrue(thread.state.value.checkedUnknown)
        thread.allowDuplicateRisk(); assertFalse(thread.state.value.uncertainSend); assertEquals(1, api.sends)
    }

    @Test fun hintsQueueBehindReadWithoutCancellingOrReplayingIt() = runTest(dispatcher) {
        open(); api.gate = CompletableDeferred(); api.gated = "read"
        thread.viewport(DirectAnchor(18, 0), setOf(18), 18, true); runCurrent()
        repeat(4) { thread.invalidate() }; runCurrent()
        assertEquals(1, api.contexts); assertEquals(1, api.reads)
        api.gate!!.complete(Unit); runCurrent()
        assertEquals(2, api.contexts); assertEquals(1, api.reads); assertFalse(thread.state.value.busy)
    }

    @Test fun peerSwitchAndBackgroundPreserveSeparateUnknownDraftsAndLogoutScrubsEverything() = runTest(dispatcher) {
        open(); thread.updateDraft("Peer eight"); api.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        thread.send(); runCurrent(); thread.open(9); runCurrent(); thread.updateDraft("Peer nine")
        thread.open(8); runCurrent()
        assertEquals("Peer eight", thread.state.value.draft); assertTrue(thread.state.value.uncertainSend)
        assertFalse(thread.state.value.checkedUnknown); assertEquals("Peer nine", thread.drafts.value[9]?.body)
        api.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT); runCurrent()
        assertNull(thread.state.value.peerId); assertTrue(thread.state.value.messages.isEmpty())
        assertEquals("", thread.state.value.draft); assertTrue(thread.drafts.value.isEmpty()); assertTrue(thread.attention.value.isEmpty())
    }

    @Test fun backgroundDuringSendRecordsUnknownOutcomeWithoutRetryOnReturn() = runTest(dispatcher) {
        open(); api.gate = CompletableDeferred(); api.gated = "send"
        thread.updateDraft("Pending fixture"); thread.send(); runCurrent(); thread.background(); runCurrent()
        assertTrue(thread.state.value.uncertainSend); assertEquals(1, api.sends)
        thread.foreground(); runCurrent(); assertFalse(thread.state.value.checkedUnknown); assertEquals(1, api.sends)
    }

    @Test fun lateUncancellableContextCannotRepopulateSignedOutIdentity() = runTest(dispatcher) {
        api.gate = CompletableDeferred(); api.gated = "context"; api.ignoreCancellation = true
        thread.open(8); thread.foreground(); runCurrent()
        api.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT); runCurrent()
        api.gate!!.complete(Unit); runCurrent()
        assertNull(thread.state.value.context); assertNull(thread.state.value.peerId); assertTrue(thread.state.value.messages.isEmpty())
    }

    @Test fun genericMessagingUnavailabilityDoesNotHideHistoryOrPreventRead() = runTest(dispatcher) {
        api.relationship = DirectRelationship(false, false); open()
        assertFalse(thread.canSend()); assertFalse(thread.state.value.context!!.relationship.blockedByMe)
        thread.viewport(DirectAnchor(18, 0), setOf(18), 18, true); runCurrent(); assertEquals(1, api.reads)
        thread.setBlocked(false); runCurrent(); assertEquals(1, api.blocks)
        assertFalse(thread.state.value.context!!.relationship.messagingAvailable)
    }

    @Test fun olderLoadingPreservesRawCursorAnchorAndBoundedWindow() = runTest(dispatcher) {
        api.rows = (1L..650L).map { row(it) }; open()
        thread.viewport(DirectAnchor(601, 17), emptySet(), null, false)
        thread.older(); runCurrent(); assertEquals(601L, thread.state.value.anchor?.id); assertEquals(17, thread.state.value.anchor?.offset)
        repeat(10) { thread.older(); runCurrent() }
        assertEquals(500, thread.state.value.messages.size); assertFalse(thread.state.value.latestWindow)
        assertTrue(thread.state.value.newerAvailable)
    }

    @Test fun inboxCoalescesHintsAndRejectsObsoleteUncancellableSearchResults() = runTest(dispatcher) {
        inbox.foreground(); runCurrent(); assertNotNull(inbox.state.value.inbox)
        api.gate = CompletableDeferred(); api.gated = "inbox"; inbox.invalidate(); runCurrent()
        repeat(4) { inbox.invalidate() }; runCurrent(); assertEquals(2, api.inboxes)
        api.gate!!.complete(Unit); runCurrent(); assertEquals(3, api.inboxes)
        api.searchGate = CompletableDeferred(); api.ignoreCancellation = true
        inbox.updateQuery("Pe"); inbox.search(); runCurrent(); inbox.updateQuery("Other")
        api.searchGate!!.complete(Unit); runCurrent()
        assertEquals("Other", inbox.state.value.query); assertTrue(inbox.state.value.results.isEmpty())
        api.session.value = SessionState(generation = 2, phase = SessionPhase.SIGNED_OUT); runCurrent()
        assertEquals("", inbox.state.value.query); assertNull(inbox.state.value.inbox)
    }

    @Test fun whitespaceDraftIsNotSilentlyLostByUsingDifferentTrimSemantics() = runTest(dispatcher) {
        open(); thread.updateDraft("\u00a0"); thread.open(9); runCurrent(); thread.open(8); runCurrent()
        assertEquals("\u00a0", thread.state.value.draft)
    }

    @Test fun emptyRetainedLatestWindowCanRecoverAfterALargeBurstWithoutDereferencingAMissingUpper() = runTest(dispatcher) {
        open(); api.rows = emptyList(); thread.invalidate(); runCurrent(); assertTrue(thread.state.value.messages.isEmpty())
        api.rows = (800L..1450L).map { row(it) }; thread.invalidate(); runCurrent()
        assertEquals(1450L, thread.state.value.messages.last().id); assertTrue(thread.state.value.latestWindow)
    }

    @Test fun disjointRetainedWindowStaysFixedEvenWhenEveryOldRowHasDisappeared() = runTest(dispatcher) {
        open(); thread.viewport(DirectAnchor(18, 9), emptySet(), null, false)
        api.rows = (800L..1450L).map { row(it) }; thread.invalidate(); runCurrent()
        assertTrue(thread.state.value.messages.isEmpty()); assertFalse(thread.state.value.latestWindow)
        assertEquals(18L, thread.state.value.fixedUpper); assertTrue(thread.state.value.newerAvailable)
        thread.invalidate(); runCurrent(); assertTrue(thread.state.value.messages.isEmpty())
        assertEquals(18L, thread.state.value.fixedUpper)
        thread.latest(); runCurrent(); assertEquals(1450L, thread.state.value.messages.last().id)
        assertNull(thread.state.value.fixedUpper)
    }

    @Test fun readFailureDoesNotHotRetryAndExplicitRetryReconcilesBeforeAnotherPost() = runTest(dispatcher) {
        open(); api.readFailure = ApiFailure(FailureKind.TIMEOUT)
        thread.viewport(DirectAnchor(18, 0), setOf(18), 18, true); runCurrent()
        assertEquals(1, api.reads); assertTrue(thread.state.value.uncertainRead)
        thread.invalidate(); runCurrent(); assertEquals(1, api.reads); assertNotNull(thread.state.value.readError)
        api.readFailure = null; thread.retryRead(); runCurrent(); assertEquals(2, api.reads)
        assertNull(thread.state.value.readError); assertFalse(thread.state.value.uncertainRead)
    }

    @Test fun uncertainBlockDisablesSendingUntilFreshStatusButDoesNotReplayBlocking() = runTest(dispatcher) {
        open(); api.blockFailure = ApiFailure(FailureKind.TIMEOUT)
        thread.setBlocked(true); runCurrent(); assertTrue(thread.state.value.uncertainBlock); assertFalse(thread.canSend())
        thread.invalidate(); runCurrent()
        assertFalse(thread.state.value.uncertainBlock); assertEquals(1, api.blocks)
        assertFalse(thread.state.value.context!!.relationship.blockedByMe)
    }

    @Test fun draftsAreBoundedWithoutEvictionAndUserCanExplicitlyFreeASlot() = runTest(dispatcher) {
        thread.foreground()
        for (peer in 8L..107L) { thread.open(peer); runCurrent(); thread.updateDraft("Draft $peer") }
        assertEquals(100, thread.drafts.value.size)
        thread.open(108); runCurrent(); thread.updateDraft("New fixture")
        assertTrue(thread.state.value.draftLimit); assertEquals("", thread.state.value.draft)
        assertEquals("Draft 8", thread.drafts.value[8]?.body)
        thread.discardSavedDraft(8); thread.updateDraft("New fixture")
        assertEquals(100, thread.drafts.value.size); assertEquals("New fixture", thread.drafts.value[108]?.body)
    }

    private class Directs : DirectOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "super_admin", true, true, false))
        var rows = listOf(row(18)); var relationship = DirectRelationship(false, true)
        var contexts = 0; var inboxes = 0; var sends = 0; var reads = 0; var blocks = 0
        var gate: CompletableDeferred<Unit>? = null; var searchGate: CompletableDeferred<Unit>? = null
        var gated = ""; var ignoreCancellation = false; var sendFailure: ApiFailure? = null
        var failAfterSend = false; var historyFailure: ApiFailure? = null; var onRead: (() -> Unit)? = null
        var readFailure: ApiFailure? = null; var blockFailure: ApiFailure? = null
        private suspend fun await(kind: String) {
            if (gated == kind) if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
        }
        override suspend fun context(peerId: Long): DirectContext {
            contexts++; await("context")
            return DirectContext(MessagePerson(peerId, "Peer $peerId"), relationship, privacy, DisplayPreferences(), null)
        }
        override suspend fun inbox(): DirectInbox {
            inboxes++; await("inbox"); return DirectInbox(emptyList(), privacy, DisplayPreferences())
        }
        override suspend fun search(prefix: String, limit: Int): List<MessagePerson> {
            if (ignoreCancellation) withContext(NonCancellable) { searchGate?.await() } else searchGate?.await()
            return listOf(MessagePerson(8, "Peer"))
        }
        override suspend fun history(peerId: Long, beforeId: Long?, limit: Int): DirectPage {
            historyFailure?.let { throw it }
            val page = rows.filter { peerId in setOf(it.sender.id, it.recipient.id) && (beforeId == null || it.id < beforeId) }.takeLast(limit)
            return DirectPage(page, page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun send(peerId: Long, body: String, replyId: Long?): Long {
            sends++; await("send"); sendFailure?.let { throw it }
            rows += row(99, peerId, outgoing = true)
            if (failAfterSend) historyFailure = ApiFailure(FailureKind.HTTP, 500)
            return 99
        }
        override suspend fun read(peerId: Long): Int {
            reads++; await("read"); readFailure?.let { throw it }; onRead?.invoke()
            val count = rows.count { !it.outgoing && it.readAt == null }
            rows = rows.map { row(it.id, read = if (!it.outgoing) "2026-10-20T10:01:00Z" else it.readAt, outgoing = it.outgoing) }
            return count
        }
        override suspend fun setBlocked(peerId: Long, blocked: Boolean): DirectRelationship {
            blocks++; blockFailure?.let { throw it }
            relationship = DirectRelationship(blocked, !blocked && relationship.messagingAvailable); return relationship
        }
    }
    companion object {
        private fun row(id: Long, peer: Long = 8, read: String? = null, outgoing: Boolean = false) = DirectMessage(id,
            MessagePerson(if (outgoing) 7 else peer, "Sender"), MessagePerson(if (outgoing) peer else 7, "Recipient"),
            "Fixture $id", read, "2026-10-20T10:00:00Z", outgoing, false, null, outgoing, outgoing, null, emptyList(), emptyList(), null)
    }
}
