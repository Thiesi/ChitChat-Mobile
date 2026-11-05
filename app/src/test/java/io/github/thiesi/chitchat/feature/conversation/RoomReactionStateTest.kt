package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.mutations.AuthorMessageActions
import io.github.thiesi.chitchat.core.mutations.MessageMutationOperations
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.reactions.*
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.rooms.RoomInfo
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
class RoomReactionStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Fixture()
    private lateinit var model: ConversationViewModel
    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = ConversationViewModel(fake, mutations = fake, reactions = fake) }
    @After fun tearDown() { model.cancel(); fake.gate?.complete(Unit); fake.historyGate?.complete(Unit); fake.readGate?.complete(Unit); Dispatchers.resetMain() }
    private fun TestScope.open(room: Long = 42) { model.foreground(); model.open(room); runCurrent() }
    private fun row() = model.state.value.messages.first()
    private fun begin(): RoomReactionRequest {
        assertTrue(model.beginReactions(fake.session.value, checkNotNull(model.state.value.roomId), row()))
        return checkNotNull(model.state.value.reactionDialog).request
    }
    private suspend fun target(room: Long = 42) = TargetSnapshot.RoomMessage(
        TargetRequest(fake.session.value, MessageTarget.RoomMessage(room, 10)), fake.context(room),
        fake.history(room, 11, 100), emptyList())

    @Test fun viewerIdsOverrideAnotherActorsFlagAndChoicesAreDesiredNotToggles() = runTest(dispatcher) {
        fake.rows[42] = listOf(message(reactions = listOf(reaction(8, flag = true))))
        open(); val request = begin()
        assertTrue(model.canSetReaction(request, ReactionEmoji.THUMBS_UP, true))
        assertFalse(model.canSetReaction(request, ReactionEmoji.THUMBS_UP, false))
        model.setReaction(request, ReactionEmoji.THUMBS_UP, false); runCurrent(); assertEquals(0, fake.writes)
        model.setReaction(request, ReactionEmoji.THUMBS_UP, true); runCurrent()
        assertEquals(1, fake.writes); assertTrue(row().reactions.single().selectedBy(7))
        assertEquals(listOf(8L, 7L), row().reactions.single().users.map { it.id })
    }

    @Test fun canonicalReplacementOldOwnerAndWrongRoomCannotAuthorizeAChoice() = runTest(dispatcher) {
        open(); val old = row(); val owner = fake.session.value; val request = begin()
        model.dismissReactions(request); fake.rows[42] = listOf(message(body = "Replacement")); model.refresh(); runCurrent()
        assertFalse(model.beginReactions(owner, 42, old)); assertFalse(model.beginReactions(owner, 43, row()))
        fake.session.value = owner.copy(generation = 2); runCurrent(); model.open(42); runCurrent()
        assertFalse(model.beginReactions(owner, 42, row())); model.setReaction(request, ReactionEmoji.HEART, true)
        assertEquals(0, fake.writes)
    }

    @Test fun modalSuppressesReadSendDraftAndAuthorCallbacks() = runTest(dispatcher) {
        fake.rows[42] = listOf(message(sender = 7)); open(); model.updateDraft("Draft")
        assertTrue(model.canBeginAuthor(fake.session.value, 42, row(), AuthorChangeKind.DELETE)); val request = begin()
        assertFalse(model.canSend()); assertFalse(model.updateDraft(fake.session.value, 42, "Old callback"))
        assertFalse(model.canBeginAuthor(fake.session.value, 42, row(), AuthorChangeKind.DELETE))
        model.viewport(TimelineAnchor(10, 0), 10, true); model.send(); runCurrent()
        assertEquals(0, fake.reads); assertEquals(0, fake.sends); assertFalse(model.state.value.atLatest)
        model.dismissReactions(request); assertTrue(model.canSend()); assertFalse(model.state.value.atLatest)
        assertEquals("Draft", model.state.value.draft)
    }

    @Test fun authorModalAndActiveReadPreventReactionSelection() = runTest(dispatcher) {
        fake.rows[42] = listOf(message(sender = 7)); open()
        assertTrue(model.beginAuthor(fake.session.value, 42, row(), AuthorChangeKind.DELETE))
        assertFalse(model.beginReactions(fake.session.value, 42, row()))
        model.dismissAuthor(checkNotNull(model.state.value.authorDialog).request)
        fake.readGate = CompletableDeferred(); model.viewport(TimelineAnchor(10, 0), 10, true); runCurrent()
        assertFalse(model.beginReactions(fake.session.value, 42, row())); assertEquals(1, fake.reads)
        fake.readGate?.complete(Unit); runCurrent(); assertTrue(model.beginReactions(fake.session.value, 42, row()))
    }

    @Test fun mutedDeletedRowAllowsOwnRemovalButNeverAnAdd() = runTest(dispatcher) {
        fake.rows[42] = listOf(message(deleted = true, reactions = listOf(reaction(7, flag = false))))
        fake.mute = RoomMute(1, "room", 42, null, "Fixture"); open(); val request = begin()
        assertFalse(model.canSetReaction(request, ReactionEmoji.HEART, true))
        assertTrue(model.canSetReaction(request, ReactionEmoji.THUMBS_UP, false))
        model.setReaction(request, ReactionEmoji.THUMBS_UP, false); runCurrent()
        assertEquals(1, fake.writes); assertTrue(row().deleted); assertTrue(row().reactions.isEmpty())
    }

    @Test fun latestSessionMuteIsCheckedEvenWhenContextSnapshotHasNotChanged() = runTest(dispatcher) {
        open(); val request = begin()
        fake.session.value = fake.session.value.copy(chatPolicy = policy(mute = RoomMute(1, "global", null, null, "Fixture")))
        runCurrent(); assertFalse(model.canSetReaction(request, ReactionEmoji.HEART, true))
        model.setReaction(request, ReactionEmoji.HEART, true); runCurrent(); assertEquals(0, fake.writes)
    }

    @Test fun unknownPolicyAndIgnoredHiddenRowsFailClosed() = runTest(dispatcher) {
        open(); fake.session.value = fake.session.value.copy(chatPolicy = null); runCurrent()
        assertFalse(model.beginReactions(fake.session.value, 42, row()))
        fake.session.value = fake.session.value.copy(chatPolicy = policy(ignored = setOf(8))); runCurrent()
        assertFalse(model.beginReactions(fake.session.value, 42, row()))
        model.reveal(10); assertTrue(model.beginReactions(fake.session.value, 42, row()))
    }

    @Test fun guestAndNonmemberDoNotAcquireMemberReactionState() = runTest(dispatcher) {
        open(); fake.member = false; model.refresh(); runCurrent()
        assertFalse(model.beginReactions(fake.session.value, 42, row()))
        fake.member = true; fake.session.value = fake.session.value.copy(phase = SessionPhase.GUEST,
            user = SessionUser(7, "Fixture", listOf("guest"), 1, true)); runCurrent(); model.open(42); runCurrent()
        assertFalse(model.beginReactions(fake.session.value, 42, row()))
    }

    @Test fun failedPostRequiresExplicitGetAndNewReviewWithoutReplayingChoice() = runTest(dispatcher) {
        open(); fake.failure = ApiFailure(FailureKind.HTTP, 500, "publication_failed")
        val old = begin(); model.setReaction(old, ReactionEmoji.THUMBS_UP, true); runCurrent()
        val unknown = checkNotNull(model.state.value.reactionUnknown)
        assertEquals(42L, unknown.roomId); assertEquals(10L, unknown.messageId); assertTrue(unknown.selected)
        fake.failure = null; model.refresh(); runCurrent()
        assertSame(unknown, model.state.value.reactionUnknown); assertFalse(model.reviewReactions(old))
        model.setReaction(old, ReactionEmoji.HEART, true); assertEquals(1, fake.writes)
        model.checkReactionHistory(fake.session.value, unknown); runCurrent()
        assertNull(model.state.value.reactionUnknown); assertTrue(model.state.value.reactionDialog?.checked == true)
        model.setReaction(old, ReactionEmoji.HEART, true); assertEquals(1, fake.writes)
        assertTrue(model.reviewReactions(old)); val fresh = checkNotNull(model.state.value.reactionDialog).request
        assertNotSame(old, fresh); model.setReaction(old, ReactionEmoji.HEART, true); assertEquals(1, fake.writes)
        model.setReaction(fresh, ReactionEmoji.HEART, true); runCurrent(); assertEquals(2, fake.writes)
    }

    @Test fun failedExplicitGetRetainsSameUnknownAndDoesNotReplay() = runTest(dispatcher) {
        open(); fake.failure = ApiFailure(FailureKind.NETWORK); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        val unknown = checkNotNull(model.state.value.reactionUnknown); fake.getFailure = ApiFailure(FailureKind.NETWORK)
        model.checkReactionHistory(fake.session.value, unknown); runCurrent()
        assertSame(unknown, model.state.value.reactionUnknown); assertFalse(model.state.value.reactionDialog?.checked == true)
        assertEquals(1, fake.writes)
    }

    @Test fun acknowledgmentSurvivesFailedCanonicalGetAndPreservesCompleteSummary() = runTest(dispatcher) {
        open(); fake.failGetAfterWrite = true; model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        assertEquals(1, fake.writes); assertEquals(ReactionEmoji.HEART.value, row().reactions.single().emoji)
        assertEquals("Fixture body", row().body); assertEquals(8L, row().senderId)
        assertNull(model.state.value.reactionUnknown); assertNull(model.state.value.reactionDialog)
        assertTrue(model.state.value.stale); assertNotNull(model.state.value.error); assertEquals("reaction_changed", model.state.value.notice)
    }

    @Test fun backgroundRetainsIdOnlyUncertaintyAndLateResponseCannotRepopulateAnotherRoom() = runTest(dispatcher) {
        open(); fake.gate = CompletableDeferred(); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        model.background(); model.open(43); model.foreground(); runCurrent()
        fake.gate?.complete(Unit); runCurrent()
        assertEquals(43L, model.state.value.roomId); assertNull(model.state.value.reactionUnknown)
        assertTrue(row().reactions.isEmpty()); assertEquals(1, fake.writes)
        model.open(42); runCurrent(); assertEquals(10L, model.state.value.reactionUnknown?.messageId)
        assertFalse(model.beginReactions(fake.session.value, 42, row()))
    }

    @Test fun ownerRetirementDropsPrivateStateAndIgnoresNoncooperativeWrite() = runTest(dispatcher) {
        open(); fake.gate = CompletableDeferred(); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        fake.session.value = SessionState(generation = 2); runCurrent(); fake.gate?.complete(Unit); runCurrent()
        assertNull(model.state.value.roomId); assertTrue(model.state.value.messages.isEmpty())
        assertNull(model.state.value.reactionDialog); assertNull(model.state.value.reactionUnknown)
    }

    @Test fun passiveCanonicalReplacementNeverReauthorizesOldReactionCallback() = runTest(dispatcher) {
        open(); val old = begin(); fake.rows[42] = listOf(message(body = "Remote replacement")); model.refresh(); runCurrent()
        model.setReaction(old, ReactionEmoji.HEART, true); runCurrent(); assertEquals(0, fake.writes)
        assertTrue(model.canReviewReactions(old)); assertTrue(model.reviewReactions(old))
        val fresh = checkNotNull(model.state.value.reactionDialog).request
        model.setReaction(old, ReactionEmoji.HEART, true); model.dismissReactions(old)
        assertSame(fresh, model.state.value.reactionDialog?.request); assertEquals(0, fake.writes)
        model.setReaction(fresh, ReactionEmoji.HEART, true); runCurrent(); assertEquals(1, fake.writes)
    }

    @Test fun anOpenPickerStopsExposingRowsWhenPolicyBecomesUnknownOrIgnored() = runTest(dispatcher) {
        open(); val request = begin(); assertSame(row(), model.reactionCurrentRow(request))
        fake.session.value = fake.session.value.copy(chatPolicy = policy(ignored = setOf(8))); runCurrent()
        assertNull(model.reactionCurrentRow(request)); assertFalse(model.canReviewReactions(request))
        assertFalse(model.canSetReaction(request, ReactionEmoji.HEART, true))
        model.reveal(10); assertSame(row(), model.reactionCurrentRow(request))
        fake.session.value = fake.session.value.copy(chatPolicy = null); runCurrent()
        assertNull(model.reactionCurrentRow(request)); assertEquals(0, fake.writes)
    }

    @Test fun closingUnknownSelectionKeepsRecoveryAndCannotCloseSuccessor() = runTest(dispatcher) {
        open(); fake.failure = ApiFailure(FailureKind.NETWORK); val old = begin()
        model.setReaction(old, ReactionEmoji.HEART, true); runCurrent()
        val unknown = checkNotNull(model.state.value.reactionUnknown); model.dismissReactions(old)
        assertSame(unknown, model.state.value.reactionUnknown); fake.failure = null
        model.checkReactionHistory(fake.session.value, unknown); runCurrent(); val fresh = begin()
        model.dismissReactions(old); assertSame(fresh, model.state.value.reactionDialog?.request)
    }

    @Test fun invalidationsWaitBehindWriteAndNeverCancelOrReplayIt() = runTest(dispatcher) {
        model.cancel(); model = ConversationViewModel(fake, Connection(), mutations = fake, reactions = fake)
        open(); fake.gate = CompletableDeferred(); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        val reads = fake.histories; repeat(50) { model.invalidate() }; model.refresh(); model.latest(); runCurrent()
        assertEquals(reads, fake.histories); assertEquals(1, fake.writes); assertTrue(model.state.value.reactionWriting)
        fake.gate?.complete(Unit); runCurrent()
        assertEquals(reads + 1, fake.histories); assertEquals(1, fake.writes); assertFalse(model.state.value.busy)
    }

    @Test fun oneHundredUnknownRoomsNeverEvictAndExplicitGetReleasesOneSlot() = runTest(dispatcher) {
        fake.failure = ApiFailure(FailureKind.NETWORK)
        for (room in 1L..100) {
            open(room); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        }
        open(101); assertFalse(model.beginReactions(fake.session.value, 101, row())); assertTrue(model.state.value.reactionLimit)
        open(1); val unknown = checkNotNull(model.state.value.reactionUnknown)
        fake.failure = null; model.checkReactionHistory(fake.session.value, unknown); runCurrent()
        open(101); assertTrue(model.beginReactions(fake.session.value, 101, row())); assertEquals(100, fake.writes)
    }

    @Test fun reactionRecoveryNeverGrantsUncertainSendConsent() = runTest(dispatcher) {
        open(); model.updateDraft("Uncertain send"); fake.sendFailure = ApiFailure(FailureKind.NETWORK)
        model.send(); runCurrent(); model.background(); model.foreground(); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertFalse(model.state.value.reconciledAfterUncertain)
        fake.failure = ApiFailure(FailureKind.NETWORK); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        fake.failure = null; model.checkReactionHistory(fake.session.value, checkNotNull(model.state.value.reactionUnknown)); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertFalse(model.state.value.reconciledAfterUncertain)
        assertEquals("Uncertain send", model.state.value.draft); assertEquals(1, fake.sends)
    }

    @Test fun pureApplicationCannotOverwriteReplacementWithSameNumericId() {
        val old = message(); val replacement = message(body = "Fresh body")
        assertSame(replacement, applyRoomReactionResult(listOf(replacement), old, listOf(reaction(7))).single())
        val applied = applyRoomReactionResult(listOf(old), old, listOf(reaction(7))).single()
        assertSame(old.authorActions, applied.authorActions); assertEquals(old.body, applied.body)
        assertEquals(listOf(7L), applied.reactions.single().users.map { it.id })
    }

    @Test fun targetAdoptionCannotCancelReactionSelectionOrAnActivePost() = runTest(dispatcher) {
        open(); val same = target(); val elsewhere = target(43); val request = begin()
        assertFalse(model.canAdoptTarget(same)); assertFalse(model.prepareTarget(elsewhere))
        fake.gate = CompletableDeferred(); model.setReaction(request, ReactionEmoji.HEART, true); runCurrent()
        assertFalse(model.prepareTarget(elsewhere)); model.dismissReactions(request)
        assertSame(request, model.state.value.reactionDialog?.request); assertEquals(1, fake.writes)
        fake.gate?.complete(Unit); runCurrent(); assertEquals(42L, model.state.value.roomId)
    }

    @Test fun notificationPreparationRestoresDestinationRoomUncertainty() = runTest(dispatcher) {
        open(); fake.failure = ApiFailure(FailureKind.NETWORK); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        val unknown = checkNotNull(model.state.value.reactionUnknown)
        fake.failure = null; val snapshot = target(); open(43)
        assertTrue(model.prepareTarget(snapshot)); assertEquals(42L, model.state.value.roomId)
        assertSame(unknown, model.state.value.reactionUnknown); assertEquals(10L, model.state.value.fixedUpper)
        assertFalse(model.state.value.latestWindow); assertEquals(1, fake.writes)
    }

    @Test fun explicitReactionRecoveryKeepsHistoricalBoundAfterAllRowsVanish() = runTest(dispatcher) {
        open(); assertTrue(model.adoptTarget(target()))
        fake.failure = ApiFailure(FailureKind.NETWORK); val request = begin()
        model.setReaction(request, ReactionEmoji.HEART, true); runCurrent()
        val unknown = checkNotNull(model.state.value.reactionUnknown)
        fake.failure = null; fake.rows[42] = emptyList()
        model.checkReactionHistory(fake.session.value, unknown); runCurrent()
        assertNull(model.state.value.reactionUnknown); assertFalse(model.reviewReactions(request))
        assertTrue(model.state.value.messages.isEmpty()); assertNotNull(model.state.value.context)
        assertEquals(10L, model.state.value.fixedUpper); assertFalse(model.state.value.latestWindow)
        assertEquals(1, fake.writes); assertEquals(0, fake.reads)
    }

    @Test fun reactionRecoveryCannotClearSeparateUnknownAuthorChange() = runTest(dispatcher) {
        fake.rows[42] = listOf(message(sender = 7)); open()
        assertTrue(model.beginAuthor(fake.session.value, 42, row(), AuthorChangeKind.DELETE))
        val author = checkNotNull(model.state.value.authorDialog).request
        fake.authorFailure = ApiFailure(FailureKind.NETWORK); model.submitAuthor(author); runCurrent()
        val unknownAuthor = checkNotNull(model.state.value.authorUnknown)
        model.dismissAuthor(author); model.refresh(); runCurrent()
        fake.failure = ApiFailure(FailureKind.NETWORK); model.setReaction(begin(), ReactionEmoji.HEART, true); runCurrent()
        fake.failure = null; model.checkReactionHistory(fake.session.value, checkNotNull(model.state.value.reactionUnknown)); runCurrent()
        assertSame(unknownAuthor, model.state.value.authorUnknown); assertNull(model.state.value.reactionUnknown)
        assertEquals(1, fake.writes)
    }

    private class Fixture : ConversationOperations, MessageReactionOperations, MessageMutationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Fixture", listOf("member"), 1, false), chatPolicy = policy()))
        val rows = mutableMapOf<Long, List<RoomMessage>>()
        var member = true; var mute: RoomMute? = null
        var writes = 0; var reads = 0; var sends = 0; var histories = 0
        var failure: ApiFailure? = null; var getFailure: ApiFailure? = null; var sendFailure: ApiFailure? = null
        var authorFailure: ApiFailure? = null
        var failGetAfterWrite = false
        var gate: CompletableDeferred<Unit>? = null; var historyGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun context(roomId: Long) = ConversationContext(RoomInfo(roomId, "fixture", "Fixture", "", "private", 0, 0, 7,
            if (member) "member" else null, false, "none", 0, 0, mute), session.value.chatPolicy ?: policy())
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            histories++; historyGate?.let { withContext(NonCancellable) { it.await() } }; getFailure?.let { throw it }
            val candidates = rows.getOrPut(roomId) { listOf(message(room = roomId)) }.filter { beforeId == null || it.id < beforeId }
            val selected = candidates.takeLast(limit)
            return MessagePage(selected, selected.firstOrNull()?.id, candidates.size >= limit)
        }
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
            sends++; sendFailure?.let { throw it }; return SendResult.Message(message(11, roomId, body = body))
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { reads++; readGate?.await(); return messageId }
        override suspend fun setRoom(expected: SessionState, original: RoomMessage, emoji: ReactionEmoji,
            selected: Boolean): List<MessageReaction> = withContext(NonCancellable) {
            writes++; gate?.await()
            val current = rows[original.roomId].orEmpty().first { it.id == original.id }
            val users = current.reactions.firstOrNull { it.emoji == emoji.value }?.users.orEmpty().filterNot { it.id == 7L } +
                if (selected) listOf(MessagePerson(7, "Fixture")) else emptyList()
            val summary = current.reactions.filterNot { it.emoji == emoji.value } +
                if (users.isEmpty()) emptyList() else listOf(MessageReaction(emoji.value, users, false))
            rows[original.roomId] = applyRoomReactionResult(rows[original.roomId].orEmpty(), current, summary)
            if (failGetAfterWrite) getFailure = ApiFailure(FailureKind.NETWORK)
            failure?.let { throw it }; summary
        }
        override suspend fun setDirect(expected: SessionState, original: DirectMessage, emoji: ReactionEmoji, selected: Boolean): List<MessageReaction> = error("No DM consumer")
        override suspend fun editRoom(expected: SessionState, original: RoomMessage, body: String): RoomMessage = error("No author write expected")
        override suspend fun deleteRoom(expected: SessionState, original: RoomMessage): RoomMessage {
            authorFailure?.let { throw it }; error("No author write expected")
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
        private fun policy(ignored: Set<Long> = emptySet(), mute: RoomMute? = null) = ChatPolicy(ignored, mute, DisplayPreferences())
        private fun reaction(user: Long, flag: Boolean = false) = MessageReaction(ReactionEmoji.THUMBS_UP.value, listOf(MessagePerson(user, "Fixture")), flag)
        private fun message(id: Long = 10, room: Long = 42, body: String = "Fixture body", deleted: Boolean = false,
            reactions: List<MessageReaction> = emptyList(), sender: Long = 8) = RoomMessage(id, room, sender, "Fixture", "text", body.takeUnless { deleted },
            deleted, "2026-11-04 12:00:00+00", reactions = reactions, authorActions = AuthorMessageActions(true, true))
    }
}
