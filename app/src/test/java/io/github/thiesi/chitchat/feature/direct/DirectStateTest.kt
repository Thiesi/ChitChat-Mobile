package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.targets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private suspend fun target(id: Long = 18, peer: Long = 8): TargetSnapshot.DirectMessage {
        api.session.value = api.session.value.copy(privacy = api.privacy,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences()))
        return TargetSnapshot.DirectMessage(TargetRequest(api.session.value, MessageTarget.DirectMessage(peer, id)),
            api.context(peer), api.history(peer, beforeTarget(id)))
    }
    @Test fun replyAdoptionPreservesTheDraftAndCannotReadNewerIncomingMessages() = runTest(dispatcher) {
        open(); thread.updateDraft("Keep DM reply draft"); assertTrue(thread.adoptTarget(target()))
        assertEquals("Keep DM reply draft", thread.state.value.draft)
        assertEquals(18L, thread.state.value.fixedUpper); assertEquals(18L, thread.state.value.highlightedMessageId)
        assertEquals(18L, thread.state.value.anchor?.id); assertFalse(thread.state.value.latestWindow)
        thread.viewport(DirectAnchor(18, 0), setOf(18), 18, true); runCurrent(); assertEquals(0, api.reads)
        api.rows = listOf(row(80)); thread.invalidate(); runCurrent(); thread.refresh(); runCurrent()
        assertTrue(thread.state.value.messages.isEmpty()); assertEquals(18L, thread.state.value.fixedUpper)
        assertNull(thread.state.value.highlightedMessageId); assertTrue(thread.state.value.anchorLost)
        thread.background(); thread.foreground(); runCurrent(); assertTrue(thread.state.value.messages.isEmpty())
        thread.latest(); runCurrent(); assertEquals(80L, thread.state.value.messages.single().id)
        assertNull(thread.state.value.fixedUpper); assertNull(thread.state.value.highlightedMessageId)
    }
    @Test fun notificationPreparationInstallsAnotherPeersExactWindowWithoutEntryGetOrRead() = runTest(dispatcher) {
        open(); thread.updateDraft("Old peer draft"); api.rows = listOf(row(18, 9), row(80, 9))
        val snapshot = target(18, 9); val contexts = api.contexts
        assertTrue(thread.prepareTarget(snapshot)); runCurrent()
        assertEquals(contexts, api.contexts); assertEquals(9L, thread.state.value.peerId)
        assertEquals(listOf(18L), thread.state.value.messages.map { it.id }); assertEquals(18L, thread.state.value.fixedUpper)
        assertEquals(18L, thread.state.value.highlightedMessageId); assertFalse(thread.state.value.latestWindow)
        assertFalse(thread.state.value.atLatest); assertFalse(thread.state.value.checkedUnknown)
        assertEquals("Old peer draft", thread.drafts.value[8]?.body)
        thread.foreground(); runCurrent(); thread.viewport(DirectAnchor(18, 0), setOf(18), 18, true); runCurrent()
        assertEquals(0, api.reads); assertEquals(0, api.sends)
        assertEquals(listOf(18L), thread.state.value.messages.map { it.id })
    }
    @Test fun notificationPreparationRetainsUnknownDeliveryAcrossPeersWithoutGrantingResendConsent() = runTest(dispatcher) {
        open(); thread.updateDraft("Unknown old peer draft"); api.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        thread.send(); runCurrent(); assertTrue(thread.state.value.uncertainSend)
        api.rows = listOf(row(18, 9)); assertTrue(thread.prepareTarget(target(18, 9))); thread.updateDraft("Other peer draft")
        api.rows = listOf(row(18)); assertTrue(thread.prepareTarget(target()))
        assertEquals("Unknown old peer draft", thread.state.value.draft); assertTrue(thread.state.value.uncertainSend)
        assertFalse(thread.state.value.checkedUnknown); assertEquals("Other peer draft", thread.drafts.value[9]?.body)
        thread.allowDuplicateRisk(); assertTrue(thread.state.value.uncertainSend); assertEquals(1, api.sends)
    }
    @Test fun notificationPreparationRejectsAnActiveSendWithoutCancellingIt() = runTest(dispatcher) {
        open(); val snapshot = target(); thread.updateDraft("Pending send"); api.gate = CompletableDeferred(); api.gated = "send"
        thread.send(); runCurrent(); assertFalse(thread.canPrepareTarget(snapshot)); assertFalse(thread.prepareTarget(snapshot))
        assertTrue(thread.state.value.sending); assertEquals(1, api.sends)
        api.gate!!.complete(Unit); runCurrent(); assertTrue(thread.prepareTarget(snapshot)); assertEquals(1, api.sends)
    }
    @Test fun notificationPreparationRejectsAnActiveWholePeerRead() = runTest(dispatcher) {
        open(); val snapshot = target(); api.gate = CompletableDeferred(); api.gated = "read"
        thread.viewport(DirectAnchor(18, 0), setOf(18), 18, true); runCurrent()
        assertEquals(1, api.reads); assertTrue(thread.state.value.busy); assertFalse(thread.prepareTarget(snapshot))
        assertEquals(8L, thread.state.value.peerId); api.gate!!.complete(Unit); runCurrent()
        assertTrue(thread.prepareTarget(snapshot)); assertEquals(1, api.reads)
    }
    @Test fun notificationPreparationRejectsStaleOwnerMissingTargetAndUnknownPolicy() = runTest(dispatcher) {
        open(); val snapshot = target(); assertFalse(thread.prepareTarget(target(99)))
        api.session.value = api.session.value.copy(chatPolicy = null)
        assertFalse(thread.prepareTarget(snapshot)); assertEquals(8L, thread.state.value.peerId)
        api.session.value = api.session.value.copy(generation = 2)
        assertFalse(thread.prepareTarget(snapshot)); runCurrent(); assertNull(thread.state.value.peerId)
    }
    @Test fun notificationPreparationUsesCurrentMuteButNeverTreatsRoomIgnoreAsADirectBlock() = runTest(dispatcher) {
        open(); api.rows = listOf(row(18, 9)); val snapshot = target(18, 9)
        val mute = RoomMute(1, "everywhere", null, null, "Fixture reason")
        api.session.value = api.session.value.copy(chatPolicy = ChatPolicy(setOf(9), mute, DisplayPreferences()))
        assertTrue(thread.prepareTarget(snapshot)); assertSame(mute, thread.state.value.context?.mutedEverywhere)
        assertEquals(listOf(18L), thread.state.value.messages.map { it.id })
    }
    @Test fun adoptionCannotCancelAnActiveWriteOrSupplyUnknownSendConsent() = runTest(dispatcher) {
        open(); val snapshot = target(); thread.updateDraft("Unknown DM reply")
        api.gate = CompletableDeferred(); api.gated = "send"; thread.send(); runCurrent()
        assertFalse(thread.adoptTarget(snapshot)); assertEquals(1, api.sends)
        api.sendFailure = ApiFailure(FailureKind.TIMEOUT); api.gate!!.complete(Unit); runCurrent()
        assertTrue(thread.adoptTarget(snapshot)); assertTrue(thread.state.value.uncertainSend)
        assertFalse(thread.state.value.checkedUnknown); assertEquals("Unknown DM reply", thread.state.value.draft)
        thread.allowDuplicateRisk(); assertTrue(thread.state.value.uncertainSend); assertEquals(1, api.sends)
    }
    @Test fun wrongPeerMissingTargetBackgroundAndOriginalAccountGuardsFailClosed() = runTest(dispatcher) {
        open(); val snapshot = target(); assertFalse(thread.adoptTarget(target(18, 9)))
        assertFalse(thread.adoptTarget(target(99))); thread.background(); assertFalse(thread.adoptTarget(snapshot))
        thread.foreground(); runCurrent(); val original = api.session.value
        api.session.value = api.session.value.copy(generation = 2)
        assertFalse(thread.adoptTarget(snapshot)); thread.open(9, original)
        assertEquals(8L, thread.state.value.peerId); runCurrent(); assertNull(thread.state.value.peerId)
    }
    @Test fun adoptionRebindsMuteAndRoomIgnoreNeverBlocksADirectTarget() = runTest(dispatcher) {
        open(); val snapshot = target(); val mute = RoomMute(3, "everywhere", null, null, "Fixture mute")
        api.session.value = api.session.value.copy(chatPolicy = ChatPolicy(setOf(8), mute, DisplayPreferences()))
        assertTrue(thread.adoptTarget(snapshot)); assertSame(mute, thread.state.value.context?.mutedEverywhere)
        assertEquals(18L, thread.state.value.messages.single().id); assertFalse(thread.canSend())
    }
    @Test fun explicitCurrentEntryCanReplaceTheOldScopeBeforeTheCollectorRuns() = runTest(dispatcher) {
        open(); val old = target(); thread.updateDraft("Old generation draft")
        val replacement = api.session.value.copy(generation = 2); api.session.value = replacement
        thread.open(8, replacement); runCurrent()
        assertTrue(thread.belongsTo(replacement)); assertFalse(thread.belongsTo(old.request.original))
        assertEquals("", thread.state.value.draft); assertTrue(thread.drafts.value.isEmpty())
        assertFalse(thread.adoptTarget(old)); assertEquals(8L, thread.state.value.peerId)
    }
    @Test fun oldRenderedInputSendAndBlockCannotActOnTheSuccessorPeerOrAccount() = runTest(dispatcher) {
        open(); val original = api.session.value
        thread.open(9); runCurrent(); thread.updateDraft("Current peer draft")
        assertFalse(thread.updateDraft(original, 8, "Queued old input"))
        thread.send(original, 8); thread.setBlocked(original, 8, true); runCurrent()
        assertEquals("Current peer draft", thread.state.value.draft); assertEquals(0, api.sends); assertEquals(0, api.blocks)
        api.session.value = api.session.value.copy(generation = 2)
        assertFalse(thread.updateDraft(original, 9, "Old account input"))
        thread.send(original, 9); thread.setBlocked(original, 9, true)
        assertEquals(0, api.sends); assertEquals(0, api.blocks); runCurrent(); assertTrue(thread.drafts.value.isEmpty())
    }
    @Test fun confirmedTargetAccessLossRetiresContentButKeepsAnInterruptedSendUnknown() = runTest(dispatcher) {
        open(); val snapshot = target(); val denied = ApiFailure(FailureKind.HTTP, 403, "forbidden")
        thread.targetDenied(target(18, 9).request, denied)
        thread.targetDenied(snapshot.request, ApiFailure(FailureKind.HTTP, 403, "csrf_failed"))
        assertTrue(thread.state.value.messages.isNotEmpty())
        thread.updateDraft("Draft survives peer access loss"); api.gate = CompletableDeferred(); api.gated = "send"
        thread.send(); runCurrent(); thread.targetDenied(snapshot.request, denied); runCurrent()
        assertTrue(thread.state.value.messages.isEmpty()); assertNull(thread.state.value.context)
        assertTrue(thread.state.value.uncertainSend); assertFalse(thread.state.value.checkedUnknown)
        assertEquals("Draft survives peer access loss", thread.state.value.draft); assertEquals(1, api.sends)
        api.gate!!.complete(Unit); runCurrent(); assertTrue(thread.state.value.messages.isEmpty()); assertEquals(1, api.sends)
    }

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
        val stableAttention = thread.attention.value
        thread.viewport(DirectAnchor(18, 1), setOf(18), null, false)
        assertSame(stableAttention, thread.attention.value) // Scrolling pixels do not emit unchanged attention/UI state.
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

    @Test fun newOwnMessageFromAnotherDeviceOffersJumpWithoutForcingAnAwayViewport() = runTest(dispatcher) {
        open(); thread.viewport(DirectAnchor(18, 9), emptySet(), null, false)
        val scroll = thread.state.value.scrollRequest
        api.rows += row(19, outgoing = true); thread.invalidate(); runCurrent()
        assertTrue(thread.state.value.newerAvailable); assertEquals(scroll, thread.state.value.scrollRequest)
        assertTrue(thread.attention.value[8]?.ids.isNullOrEmpty()) // Own arrival is not an incoming unread count.
        thread.invalidate(); runCurrent(); assertTrue(thread.state.value.newerAvailable)
        thread.viewport(DirectAnchor(19, 0), emptySet(), 19, true); runCurrent()
        assertFalse(thread.state.value.newerAvailable); assertEquals(0, api.sends)
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
        thread.discardSavedDraft(thread.drafts.value.getValue(8)) { true }; thread.updateDraft("New fixture")
        assertEquals(100, thread.drafts.value.size); assertEquals("New fixture", thread.drafts.value[108]?.body)
    }

    @Test fun replyOnlyDraftsFollowTheirOwnPeersAndExplicitDiscard() = runTest(dispatcher) {
        api.rows = listOf(row(18), row(28, peer = 9)); open()
        assertTrue(thread.replyTo(api.session.value, 8, thread.state.value.messages.first()))
        val first = thread.state.value.composerReply!!
        thread.open(9); runCurrent(); assertTrue(thread.replyTo(api.session.value, 9, thread.state.value.messages.first()))
        val second = thread.state.value.composerReply!!
        thread.open(8); runCurrent(); assertSame(first, thread.state.value.composerReply)
        assertEquals("", thread.drafts.value.getValue(8).body)
        thread.open(9); runCurrent(); assertSame(second, thread.state.value.composerReply)
        thread.discardSavedDraft(thread.drafts.value.getValue(9)) { true }; assertNull(thread.state.value.composerReply)
        assertSame(first, thread.drafts.value.getValue(8).composerReply); assertEquals(0, api.sends)
    }
    @Test fun oldReplyCancelAndReplacedRowsCannotChangeTheSuccessor() = runTest(dispatcher) {
        open(); val owner = api.session.value; val originalRow = thread.state.value.messages.first()
        assertTrue(thread.replyTo(owner, 8, originalRow)); val original = thread.state.value.composerReply!!
        assertTrue(thread.replyTo(owner, 8, originalRow)); val successor = thread.state.value.composerReply!!
        thread.cancelReply(owner, 8, original); assertSame(successor, thread.state.value.composerReply)
        api.rows = listOf(row(18)); thread.refresh(); runCurrent(); assertFalse(thread.replyTo(owner, 8, originalRow))
        thread.updateDraft("Keep authored text"); thread.cancelReply(owner, 8, successor)
        assertNull(thread.state.value.composerReply); assertEquals("Keep authored text", thread.state.value.draft)
    }
    @Test fun pendingDirectSendFreezesItsReplyAndSuccessClearsBothDraftParts() = runTest(dispatcher) {
        api.rows = listOf(row(18), row(19)); open(); val owner = api.session.value
        assertTrue(thread.replyTo(owner, 8, thread.state.value.messages.first()))
        val original = thread.state.value.composerReply!!; thread.updateDraft("Answer")
        api.gate = CompletableDeferred(); api.gated = "send"; thread.send(); runCurrent()
        assertEquals(18L, api.sentReply); assertTrue(thread.state.value.sending)
        assertFalse(thread.replyTo(owner, 8, thread.state.value.messages.last()))
        thread.cancelReply(owner, 8, original); assertSame(original, thread.state.value.composerReply)
        api.gate!!.complete(Unit); runCurrent()
        assertNull(thread.state.value.composerReply); assertEquals("", thread.state.value.draft)
        assertFalse(8L in thread.drafts.value); assertEquals(1, api.sends)
    }
    @Test fun unknownDirectReplySurvivesPeerSwitchAndCancelKeepsTheUnknownGuard() = runTest(dispatcher) {
        api.rows = listOf(row(18), row(28, peer = 9)); open(); val owner = api.session.value
        assertTrue(thread.replyTo(owner, 8, thread.state.value.messages.first()))
        val original = thread.state.value.composerReply!!; thread.updateDraft("Answer")
        api.sendFailure = ApiFailure(FailureKind.TIMEOUT); thread.send(); runCurrent()
        thread.open(9); runCurrent(); thread.open(8); runCurrent()
        assertSame(original, thread.state.value.composerReply); assertTrue(thread.state.value.uncertainSend)
        thread.cancelReply(owner, 8, original)
        assertNull(thread.state.value.composerReply); assertTrue(thread.state.value.uncertainSend)
        assertFalse(thread.canSend()); assertEquals("Answer", thread.state.value.draft); assertEquals(1, api.sends)
    }
    @Test fun onlyReplyMemosUseTheSameBoundedDraftCapacityWithoutEviction() = runTest(dispatcher) {
        api.rows = (8L..108L).map { row(it * 10, peer = it) }; thread.foreground()
        for (peer in 8L..107L) { thread.open(peer); runCurrent()
            assertTrue(thread.replyTo(api.session.value, peer, thread.state.value.messages.first())) }
        assertEquals(100, thread.drafts.value.size)
        thread.open(108); runCurrent()
        assertFalse(thread.canReplyTo(api.session.value, 108, thread.state.value.messages.first()))
        assertFalse(thread.replyTo(api.session.value, 108, thread.state.value.messages.first()))
        assertEquals(100, thread.drafts.value.size)
        assertNotNull(thread.drafts.value[8]?.composerReply); thread.discardSavedDraft(thread.drafts.value.getValue(8)) { true }
        assertTrue(thread.replyTo(api.session.value, 108, thread.state.value.messages.first()))
        assertEquals(100, thread.drafts.value.size)
    }
    @Test fun ownerReplacementPurgesReplyMemosAndRejectsTheOldRow() = runTest(dispatcher) {
        open(); val owner = api.session.value; val row = thread.state.value.messages.first()
        assertTrue(thread.replyTo(owner, 8, row))
        api.session.value = owner.copy(generation = owner.generation + 1); runCurrent()
        assertNull(thread.state.value.composerReply); assertTrue(thread.drafts.value.isEmpty())
        assertFalse(thread.replyTo(owner, 8, row)); assertEquals(0, api.sends)
    }
    @Test fun blockingDisablesReplyButDoesNotInventAHistoryOrCopyRestriction() = runTest(dispatcher) {
        open(); val owner = api.session.value; val originalRow = thread.state.value.messages.first()
        assertTrue(thread.replyTo(owner, 8, originalRow)); val selected = thread.state.value.composerReply!!
        thread.setBlocked(true); runCurrent()
        assertTrue(thread.canUseMessage(owner, 8, thread.state.value.messages.first()))
        assertFalse(thread.canReplyTo(owner, 8, thread.state.value.messages.first()))
        assertSame(selected, thread.state.value.composerReply)
        thread.cancelReply(owner, 8, selected); assertNull(thread.state.value.composerReply)
        assertFalse(thread.canSend())
    }
    @Test fun removingARejectedReplyKeepsItsTextButDoesNotResetStalenessOrSend() = runTest(dispatcher) {
        open(); val owner = api.session.value
        assertTrue(thread.replyTo(owner, 8, thread.state.value.messages.first()))
        val original = thread.state.value.composerReply!!; thread.updateDraft("Keep answer")
        api.sendFailure = ApiFailure(FailureKind.HTTP, 400, "invalid_reply_target"); thread.send(); runCurrent()
        assertEquals("invalid_reply_target", thread.state.value.error?.code); assertFalse(thread.state.value.uncertainSend)
        thread.cancelReply(owner, 8, original)
        assertNull(thread.state.value.error); assertTrue(thread.state.value.stale)
        assertEquals("Keep answer", thread.state.value.draft); thread.send(); runCurrent(); assertEquals(1, api.sends)
        thread.refresh(); runCurrent(); assertTrue(thread.canSend()); assertNull(thread.state.value.composerReply)
    }
    // Send when back online (S4b) -----------------------------------------------------------------

    private val connection = Connection()
    private var revalidations = 0
    /** Null succeeds and moves the bootstrap sequence, as SessionRepository does; otherwise this failure stays. */
    private var revalidationError: (() -> ApiFailure?)? = null
    private var revalidationGate: CompletableDeferred<Unit>? = null
    private fun waitingThread() {
        thread.background()
        thread = DirectThreadViewModel(api, connection, revalidate = {
            revalidations++
            revalidationGate?.await()
            val error = revalidationError?.invoke()
            api.session.value = if (error == null) api.session.value.copy(error = null, retryAtMillis = 0,
                bootstrapSequence = api.session.value.bootstrapSequence + 1)
            else api.session.value.copy(error = error, retryAtMillis =
                if (error.status == 429 && (error.retryAfterSeconds ?: 0) > 0) System.currentTimeMillis() + error.retryAfterSeconds!! * 1000 else 0)
        })
    }
    private fun TestScope.offline() {
        connection.state.value = connection.state.value.copy(phase = RealtimePhase.OFFLINE); thread.connectionLost(); runCurrent()
    }
    private fun TestScope.backOnline() {
        connection.state.value = connection.state.value.copy(phase = RealtimePhase.LIVE, reconcileVersion = connection.state.value.reconcileVersion + 1)
        thread.invalidate(); advanceUntilIdle()
    }
    private fun TestScope.waitWith(text: String) {
        waitingThread(); open(); advanceUntilIdle()
        thread.updateDraft(text); offline()
        assertFalse("Offline, an ordinary send isn't possible", thread.canSend())
        assertTrue(thread.canWaitToSend())
        thread.send(); runCurrent()
        assertTrue(thread.state.value.waitingToSend)
    }

    @Test fun anOfflineDirectDraftWaitsThenSendsExactlyTheFrozenTextOnce() = runTest(dispatcher) {
        waitWith("Frozen direct fixture")
        assertEquals("Waiting is not an attempt", 0, api.sends)
        assertFalse("The waiting text is read-only", thread.updateDraft(api.session.value, 8, "Changed"))
        backOnline()
        assertEquals(1, revalidations)
        assertEquals(listOf("Frozen direct fixture"), api.sentBodies)
        assertFalse(thread.state.value.waitingToSend)
        assertEquals("", thread.state.value.draft)
    }

    @Test fun aDirectWaitNeverSendsOnBareLiveAndWaitsForTheNextReconnect() = runTest(dispatcher) {
        revalidationError = { ApiFailure(FailureKind.HTTP, 503, "unavailable") }
        waitWith("Patient direct fixture")
        backOnline()
        assertEquals("No re-request on its own", 1, revalidations)
        assertEquals("No send on bare LIVE", 0, api.sends)
        assertTrue(thread.state.value.waitingToSend)
        revalidationError = null
        offline(); backOnline()
        assertEquals(2, revalidations)
        assertEquals(listOf("Patient direct fixture"), api.sentBodies)
    }

    @Test fun aKnown429OnDirectRevalidationIsWaitedOutThenRevalidatedOnce() = runTest(dispatcher) {
        var failures = 1
        revalidationError = { if (failures-- > 0) ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 5) else null }
        waitWith("Later direct fixture")
        backOnline()
        assertEquals(2, revalidations)
        assertEquals(listOf("Later direct fixture"), api.sentBodies)
    }

    @Test fun cancelLeavingAndAnotherConversationAreNeutral() = runTest(dispatcher) {
        waitWith("Kept direct fixture")
        thread.cancelWaiting()
        assertFalse(thread.state.value.waitingToSend)
        assertEquals("Kept direct fixture", thread.state.value.draft)
        assertFalse(thread.state.value.uncertainSend)
        thread.send(); runCurrent(); assertTrue(thread.state.value.waitingToSend)
        thread.leavePane(); runCurrent()
        assertFalse("Leaving the conversation drops the flag", thread.state.value.waitingToSend)
        thread.open(8); thread.foreground(); runCurrent()
        thread.send(); runCurrent(); assertTrue(thread.state.value.waitingToSend)
        thread.open(9); runCurrent()
        assertFalse("Another conversation drops it too", thread.state.value.waitingToSend)
        backOnline()
        assertEquals("Nothing sends without a new Send press", 0, api.sends)
        thread.open(8); runCurrent()
        assertEquals("The text stays an ordinary draft", "Kept direct fixture", thread.state.value.draft)
    }

    @Test fun anAmbiguousDirectResultIsUncertainWithNoSecondAttempt() = runTest(dispatcher) {
        waitWith("Once direct fixture")
        api.sendFailure = ApiFailure(FailureKind.TIMEOUT)
        backOnline()
        assertEquals(1, api.sends)
        assertTrue(thread.state.value.uncertainSend)
        assertFalse(thread.state.value.waitingToSend)
        offline(); assertFalse("An uncertain draft can't wait", thread.canWaitToSend())
        backOnline()
        assertEquals("Never a second automatic attempt", 1, api.sends)
    }

    @Test fun theDirectReplyTargetIsFrozen() = runTest(dispatcher) {
        waitingThread(); open(); advanceUntilIdle()
        val original = thread.state.value.messages.first()
        assertTrue(thread.replyTo(api.session.value, 8, original))
        thread.updateDraft("Reply direct fixture"); offline(); thread.send(); runCurrent()
        val reply = checkNotNull(thread.state.value.composerReply)
        thread.cancelReply(api.session.value, 8, reply)
        assertSame(reply, thread.state.value.composerReply)
        backOnline()
        assertEquals(original.id, api.sentReply)
    }

    @Test fun messagingBecomingUnavailableWhileOfflineMeansNoSend() = runTest(dispatcher) {
        waitWith("Refused direct fixture")
        api.relationship = DirectRelationship(false, false)
        backOnline()
        assertEquals("The re-read relationship refuses: no send", 0, api.sends)
        assertFalse(thread.state.value.waitingToSend)
        assertEquals("Refused direct fixture", thread.state.value.draft)
        assertEquals(false, thread.state.value.context?.relationship?.messagingAvailable)
    }

    @Test fun aManualDirectSendWhileLiveButNotYetRevalidatedCannotPostASecondCopy() = runTest(dispatcher) {
        revalidationGate = CompletableDeferred()
        waitWith("Single direct fixture")
        connection.state.value = connection.state.value.copy(phase = RealtimePhase.LIVE, reconcileVersion = 1)
        thread.invalidate(); runCurrent()
        assertEquals(1, revalidations)
        assertFalse(thread.canSend())
        thread.send(); advanceUntilIdle()
        assertEquals(0, api.sends)
        revalidationGate?.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("Single direct fixture"), api.sentBodies)
    }

    private class Connection : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(phase = RealtimePhase.LIVE, generation = 1))
        override val events = MutableSharedFlow<RealtimeEvent>()
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }

    private class Directs : DirectOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        val privacy = MessagePrivacy(DirectPrivacy(false, false, "admin", "permanently", 0),
            RevisionPrivacy(false, "super_admin", true, true, false))
        var rows = listOf(row(18)); var relationship = DirectRelationship(false, true)
        var contexts = 0; var inboxes = 0; var sends = 0; var reads = 0; var blocks = 0; var sentReply: Long? = null
        val sentBodies = mutableListOf<String>()
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
            sends++; sentBodies += body; sentReply = replyId; await("send"); sendFailure?.let { throw it }
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
