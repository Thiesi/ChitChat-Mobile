package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.mutations.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.reactions.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.targets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectReactionStateTest {
    private val dispatcher=StandardTestDispatcher()
    private val api=Fixture()
    private lateinit var model: DirectThreadViewModel
    @Before fun setup() { Dispatchers.setMain(dispatcher); model=DirectThreadViewModel(api,mutations=api,reactions=api) }
    @After fun teardown() { model.background(); api.gate?.complete(Unit); api.readGate?.complete(Unit); Dispatchers.resetMain() }
    private fun TestScope.open(peer: Long=8) { model.open(peer); model.foreground(); runCurrent(); assertTrue(model.state.value.loaded) }
    private fun row()=model.state.value.messages.first()
    private fun begin(): DirectReactionRequest {
        assertTrue(model.beginReactions(api.session.value,model.state.value.peerId,row()))
        return checkNotNull(model.state.value.reactionDialog).request
    }
    private suspend fun target(peer: Long=8)=TargetSnapshot.DirectMessage(
        TargetRequest(api.session.value,MessageTarget.DirectMessage(peer,10)),api.context(peer),api.history(peer,11,100))

    @Test fun sharedActorFlagsNeverOverrideViewerIdsAndDesiredChoicesNeverToggle()=runTest(dispatcher) {
        api.rows[8]=listOf(message(reactions=listOf(reaction(8,flag=true)))); open(); val request=begin()
        assertTrue(model.canSetReaction(request,ReactionEmoji.THUMBS_UP,true)); assertFalse(model.canSetReaction(request,ReactionEmoji.THUMBS_UP,false))
        model.setReaction(request,ReactionEmoji.THUMBS_UP,false); assertEquals(0,api.reacts)
        model.setReaction(request,ReactionEmoji.THUMBS_UP,true); runCurrent()
        assertEquals(listOf(8L,7L),row().reactions.single().users.map { it.id }); assertEquals(1,api.reacts)
        assertTrue(row().reactions.single().selectedBy(7)); assertFalse(row().reactions.single().reactedByMe)
    }

    @Test fun blockingAndRoomIgnoreDoNotBecomeDirectHistoryReactionGates()=runTest(dispatcher) {
        api.relationship=DirectRelationship(true,false)
        api.session.value=api.session.value.copy(chatPolicy=ChatPolicy(setOf(8),null,DisplayPreferences()))
        open(); assertFalse(model.canSend()); val request=begin()
        assertSame(row(),model.reactionCurrentRow(request)); model.setReaction(request,ReactionEmoji.HEART,true); runCurrent()
        assertEquals(1,api.reacts); assertEquals(0,api.blocks); assertEquals(0,api.sends)
    }

    @Test fun incomingRowsNeedNoAuthorHintsAndMalformedPartiesOrDirectionFailClosed()=runTest(dispatcher) {
        open(); assertFalse(row().canEdit); val valid=row()
        assertTrue(model.canBeginReactions(api.session.value,8,valid))
        assertFalse(model.canBeginReactions(api.session.value,9,valid))
        val foreign=message(peer=9); api.rows[8]=listOf(foreign); model.refresh(); runCurrent()
        assertFalse(model.canBeginReactions(api.session.value,8,foreign))
        val wrongDirection=DirectMessage(10,MessagePerson(8,"Peer"),MessagePerson(7,"Viewer"),"Body",null,stamp,true,
            false,null,false,false,null,emptyList(),emptyList(),null)
        api.rows[8]=listOf(wrongDirection); model.refresh(); runCurrent()
        assertFalse(model.canBeginReactions(api.session.value,8,wrongDirection)); assertEquals(0,api.reacts)
    }

    @Test fun unknownPrivacyPolicyAndGuestCannotAcquireReactionState()=runTest(dispatcher) {
        open(); val original=row(); val owner=api.session.value
        api.session.value=owner.copy(privacy=null); runCurrent(); assertFalse(model.beginReactions(api.session.value,8,original))
        api.session.value=owner.copy(chatPolicy=null); runCurrent(); assertFalse(model.beginReactions(api.session.value,8,original))
        api.session.value=owner.copy(phase=SessionPhase.GUEST,user=SessionUser(7,"Guest",listOf("guest"),1,true)); runCurrent()
        assertFalse(model.beginReactions(api.session.value,8,original)); assertTrue(model.state.value.messages.isEmpty())
    }

    @Test fun mutedDeletedHistoryAllowsOwnRemovalButNoNewAdd()=runTest(dispatcher) {
        api.rows[8]=listOf(message(deleted=true,reactions=listOf(reaction(7))))
        api.session.value=api.session.value.copy(chatPolicy=ChatPolicy(emptySet(),RoomMute(1,"global",null,null,"Fixture"),DisplayPreferences()))
        open(); val request=begin()
        assertFalse(model.canSetReaction(request,ReactionEmoji.HEART,true)); assertTrue(model.canSetReaction(request,ReactionEmoji.THUMBS_UP,false))
        model.setReaction(request,ReactionEmoji.THUMBS_UP,false); runCurrent()
        assertTrue(row().deleted); assertTrue(row().reactions.isEmpty()); assertEquals(1,api.reacts)
    }

    @Test fun aNewMuteBetweenRenderedSelectionAndCallbackPreventsAnAdd()=runTest(dispatcher) {
        open(); val request=begin()
        api.session.value=api.session.value.copy(chatPolicy=ChatPolicy(emptySet(),RoomMute(1,"global",null,null,"Fixture"),DisplayPreferences()))
        runCurrent(); model.setReaction(request,ReactionEmoji.HEART,true); runCurrent(); assertEquals(0,api.reacts)
    }

    @Test fun anOpenSelectionHidesPrivateRowsWhenDiscoveredPrivacyBecomesUnknown()=runTest(dispatcher) {
        open(); val request=begin(); assertSame(row(),model.reactionCurrentRow(request))
        api.session.value=api.session.value.copy(privacy=null); runCurrent()
        assertNull(model.reactionCurrentRow(request)); assertFalse(model.canSetReaction(request,ReactionEmoji.HEART,true))
        assertEquals(0,api.reacts)
    }

    @Test fun modalSuppressesReadSendBlockDraftAndAuthorChoicesUntilFreshViewport()=runTest(dispatcher) {
        api.rows[8]=listOf(message(outgoing=true,author=true)); open(); model.updateDraft("Draft")
        assertTrue(model.canBeginAuthor(api.session.value,8,row(),AuthorChangeKind.DELETE)); val request=begin()
        model.viewport(DirectAnchor(10,0),setOf(10),10,true); model.send(); model.setBlocked(true); runCurrent()
        assertFalse(model.updateDraft(api.session.value,8,"Old callback")); assertFalse(model.canSend())
        assertFalse(model.canBeginAuthor(api.session.value,8,row(),AuthorChangeKind.DELETE)); assertFalse(model.state.value.atLatest)
        assertEquals(0,api.reads); assertEquals(0,api.sends); assertEquals(0,api.blocks)
        model.dismissReactions(request); assertTrue(model.canSend()); assertFalse(model.state.value.atLatest); assertEquals("Draft",model.state.value.draft)
    }

    @Test fun activeReadAndAuthorWorkCannotBeInterruptedByReactionSelection()=runTest(dispatcher) {
        api.rows[8]=listOf(message(outgoing=true,author=true)); open()
        assertTrue(model.beginAuthor(api.session.value,8,row(),AuthorChangeKind.DELETE)); assertFalse(model.beginReactions(api.session.value,8,row()))
        model.dismissAuthor(checkNotNull(model.state.value.authorDialog).request)
        api.rows[8]=listOf(message()); model.refresh(); runCurrent(); api.readGate=CompletableDeferred()
        model.viewport(DirectAnchor(10,0),setOf(10),10,true); runCurrent(); assertEquals(1,api.reads)
        assertFalse(model.beginReactions(api.session.value,8,row())); api.readGate?.complete(Unit); runCurrent()
        assertTrue(model.beginReactions(api.session.value,8,row()))
    }

    @Test fun committedButUnconfirmedChoiceNeedsExplicitGetThenReviewAndNewChoice()=runTest(dispatcher) {
        open(); api.reactionFailure=ApiFailure(FailureKind.HTTP,500,"publication_failed"); val old=begin()
        model.setReaction(old,ReactionEmoji.HEART,true); runCurrent(); val pending=checkNotNull(model.state.value.reactionUnknown)
        api.reactionFailure=null; model.refresh(); runCurrent(); assertSame(pending,model.state.value.reactionUnknown)
        assertFalse(model.canReviewReactions(old)); model.setReaction(old,ReactionEmoji.SAD,true); assertEquals(1,api.reacts)
        model.checkReactionHistory(api.session.value,pending); runCurrent(); assertNull(model.state.value.reactionUnknown)
        assertTrue(model.state.value.reactionDialog?.checked==true); model.setReaction(old,ReactionEmoji.SAD,true); assertEquals(1,api.reacts)
        assertTrue(model.reviewReactions(old)); val fresh=checkNotNull(model.state.value.reactionDialog).request
        model.dismissReactions(old); assertSame(fresh,model.state.value.reactionDialog?.request)
        model.setReaction(old,ReactionEmoji.SAD,true); assertEquals(1,api.reacts)
        model.setReaction(fresh,ReactionEmoji.SAD,true); runCurrent(); assertEquals(2,api.reacts)
    }

    @Test fun failedHistoryCheckKeepsTheExactUnknownAndNeverReplays()=runTest(dispatcher) {
        open(); api.reactionFailure=ApiFailure(FailureKind.NETWORK); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        val pending=checkNotNull(model.state.value.reactionUnknown); api.historyFailure=ApiFailure(FailureKind.NETWORK)
        model.checkReactionHistory(api.session.value,pending); runCurrent()
        assertSame(pending,model.state.value.reactionUnknown); assertFalse(model.state.value.reactionDialog?.checked==true); assertEquals(1,api.reacts)
    }

    @Test fun confirmationBeforeFailedGetPreservesFullSummaryAndReadQuoteMetadata()=runTest(dispatcher) {
        val quote=DirectReply("direct",9,true,DirectReplyTarget(9,MessagePerson(8,"Peer"),"Quote",false))
        api.rows[8]=listOf(message(read="seen",quote=quote)); open(); api.failGetAfterReaction=true
        model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        assertNull(model.state.value.reactionUnknown); assertNull(model.state.value.reactionDialog)
        assertTrue(model.state.value.stale); assertNotNull(model.state.value.error); assertEquals("reaction_changed",model.state.value.reactionNotice)
        assertEquals("seen",row().readAt); assertSame(quote,row().replyTo); assertEquals("Body",row().body)
        assertTrue(row().reactions.single().selectedBy(7)); assertEquals(1,api.reacts)
    }

    @Test fun oldCanonicalOwnerAndPeerCallbacksCannotGainNewAuthority()=runTest(dispatcher) {
        open(); val owner=api.session.value; val old=begin(); api.rows[8]=listOf(message(body="Remote replacement"))
        model.refresh(); runCurrent(); model.setReaction(old,ReactionEmoji.HEART,true); assertEquals(0,api.reacts)
        assertTrue(model.reviewReactions(old)); val fresh=checkNotNull(model.state.value.reactionDialog).request
        model.open(9); model.foreground(); runCurrent(); model.setReaction(fresh,ReactionEmoji.HEART,true); assertEquals(0,api.reacts)
        api.session.value=owner.copy(generation=2); runCurrent(); model.open(8); model.foreground(); runCurrent()
        assertFalse(model.beginReactions(owner,8,row())); model.setReaction(old,ReactionEmoji.HEART,true); assertEquals(0,api.reacts)
    }

    @Test fun lateNoncooperativeResponseCannotRepopulateSuccessorPeer()=runTest(dispatcher) {
        open(); api.gate=CompletableDeferred(); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        model.open(9); model.foreground(); runCurrent(); api.gate?.complete(Unit); runCurrent()
        assertEquals(9L,model.state.value.peerId); assertTrue(row().reactions.isEmpty()); assertNull(model.state.value.reactionUnknown)
        open(8); assertEquals(10L,model.state.value.reactionUnknown?.messageId); assertFalse(model.beginReactions(api.session.value,8,row()))
        assertEquals(1,api.reacts)
    }

    @Test fun ownerRetirementClearsUnknownsAndRejectsLateResponse()=runTest(dispatcher) {
        open(); api.gate=CompletableDeferred(); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        api.session.value=SessionState(generation=2); runCurrent(); api.gate?.complete(Unit); runCurrent()
        assertNull(model.state.value.peerId); assertTrue(model.state.value.messages.isEmpty()); assertNull(model.state.value.reactionUnknown)
    }

    @Test fun invalidationsQueueBehindReactionWithoutCancellationOrReplay()=runTest(dispatcher) {
        open(); api.gate=CompletableDeferred(); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        val histories=api.histories; repeat(50) { model.invalidate() }; model.refresh(); model.latest(); runCurrent()
        assertEquals(histories,api.histories); assertEquals(1,api.reacts); assertTrue(model.state.value.reactionWriting)
        api.gate?.complete(Unit); runCurrent(); assertEquals(histories+1,api.histories); assertEquals(1,api.reacts); assertFalse(model.state.value.busy)
    }

    @Test fun targetsCannotCancelAnActiveReactionOrRetireItsSelection()=runTest(dispatcher) {
        open(); val same=target(); val other=target(9); val request=begin()
        assertFalse(model.adoptTarget(same)); assertFalse(model.prepareTarget(other))
        api.gate=CompletableDeferred(); model.setReaction(request,ReactionEmoji.HEART,true); runCurrent()
        assertFalse(model.prepareTarget(other)); model.dismissReactions(request); assertSame(request,model.state.value.reactionDialog?.request)
        api.gate?.complete(Unit); runCurrent(); assertEquals(8L,model.state.value.peerId); assertEquals(1,api.reacts)
    }

    @Test fun notificationPreparationRestoresDestinationPeerUnknown()=runTest(dispatcher) {
        open(); api.reactionFailure=ApiFailure(FailureKind.NETWORK); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        val pending=checkNotNull(model.state.value.reactionUnknown); api.reactionFailure=null; val snapshot=target(); open(9)
        assertTrue(model.prepareTarget(snapshot)); assertSame(pending,model.state.value.reactionUnknown)
        assertEquals(8L,model.state.value.peerId); assertEquals(10L,model.state.value.fixedUpper); assertFalse(model.state.value.latestWindow)
    }

    @Test fun explicitRecoveryRetainsHistoricalUpperBoundAfterAllRowsVanish()=runTest(dispatcher) {
        open(); assertTrue(model.adoptTarget(target())); api.reactionFailure=ApiFailure(FailureKind.NETWORK)
        val request=begin(); model.setReaction(request,ReactionEmoji.HEART,true); runCurrent()
        val pending=checkNotNull(model.state.value.reactionUnknown); api.rows[8]=emptyList(); api.reactionFailure=null
        model.checkReactionHistory(api.session.value,pending); runCurrent(); model.invalidate(); runCurrent()
        assertTrue(model.state.value.messages.isEmpty()); assertNotNull(model.state.value.context); assertFalse(model.reviewReactions(request))
        assertFalse(model.state.value.latestWindow); assertEquals(10L,model.state.value.fixedUpper); assertNull(model.state.value.reactionUnknown)
    }

    @Test fun dismissingUnknownDoesNotLoseRecoveryOrCloseItsSuccessor()=runTest(dispatcher) {
        open(); api.reactionFailure=ApiFailure(FailureKind.NETWORK); val old=begin(); model.setReaction(old,ReactionEmoji.HEART,true); runCurrent()
        val pending=checkNotNull(model.state.value.reactionUnknown); model.dismissReactions(old); assertSame(pending,model.state.value.reactionUnknown)
        api.reactionFailure=null; model.checkReactionHistory(api.session.value,pending); runCurrent(); val fresh=begin()
        model.dismissReactions(old); assertSame(fresh,model.state.value.reactionDialog?.request); assertEquals(1,api.reacts)
    }

    @Test fun reactionRecoveryDoesNotSupplySendHistoryOrDuplicateRiskConsent()=runTest(dispatcher) {
        open(); model.updateDraft("Uncertain draft"); api.sendFailure=ApiFailure(FailureKind.NETWORK); model.send(); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertTrue(model.adoptTarget(target())); assertFalse(model.state.value.checkedUnknown)
        api.reactionFailure=ApiFailure(FailureKind.NETWORK); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        api.reactionFailure=null; model.checkReactionHistory(api.session.value,checkNotNull(model.state.value.reactionUnknown)); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertFalse(model.state.value.checkedUnknown); assertEquals("Uncertain draft",model.state.value.draft)
        assertEquals(1,api.sends); assertEquals(1,api.reacts)
    }

    @Test fun reactionRecoveryDoesNotClearUncertainWholePeerRead()=runTest(dispatcher) {
        open(); api.readFailure=ApiFailure(FailureKind.NETWORK); model.viewport(DirectAnchor(10,0),setOf(10),10,true); runCurrent()
        assertTrue(model.state.value.uncertainRead); assertTrue(model.adoptTarget(target()))
        api.reactionFailure=ApiFailure(FailureKind.NETWORK); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        api.reactionFailure=null; model.checkReactionHistory(api.session.value,checkNotNull(model.state.value.reactionUnknown)); runCurrent()
        assertTrue(model.state.value.uncertainRead); assertEquals(1,api.reads); assertEquals(1,api.reacts)
    }

    @Test fun reactionConfirmationAndRecoveryPreserveIndependentBlockUncertainty()=runTest(dispatcher) {
        open(); api.blockFailure=ApiFailure(FailureKind.NETWORK); model.setBlocked(true); runCurrent()
        assertTrue(model.state.value.uncertainBlock); assertTrue(model.adoptTarget(target()))
        model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent(); assertTrue(model.state.value.uncertainBlock)
        api.reactionFailure=ApiFailure(FailureKind.NETWORK); model.setReaction(begin(),ReactionEmoji.SAD,true); runCurrent()
        api.reactionFailure=null; model.checkReactionHistory(api.session.value,checkNotNull(model.state.value.reactionUnknown)); runCurrent()
        assertTrue(model.state.value.uncertainBlock); assertFalse(model.canSend()); assertEquals(1,api.blocks); assertEquals(2,api.reacts)
    }

    @Test fun reactionHistoryDoesNotReconcileAnUnknownAuthorChange()=runTest(dispatcher) {
        api.rows[8]=listOf(message(outgoing=true,author=true)); open()
        assertTrue(model.beginAuthor(api.session.value,8,row(),AuthorChangeKind.DELETE)); val author=checkNotNull(model.state.value.authorDialog).request
        api.authorFailure=ApiFailure(FailureKind.NETWORK); model.submitAuthor(author); runCurrent()
        val pendingAuthor=checkNotNull(model.state.value.authorUnknown); model.dismissAuthor(author); model.refresh(); runCurrent()
        api.reactionFailure=ApiFailure(FailureKind.NETWORK); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent()
        api.reactionFailure=null; model.checkReactionHistory(api.session.value,checkNotNull(model.state.value.reactionUnknown)); runCurrent()
        assertSame(pendingAuthor,model.state.value.authorUnknown); assertNull(model.state.value.reactionUnknown); assertEquals(1,api.authors)
    }

    @Test fun oneHundredUnknownPeersNeverEvictAndExplicitGetReleasesCapacity()=runTest(dispatcher) {
        api.reactionFailure=ApiFailure(FailureKind.NETWORK)
        for (peer in 8L..107) { open(peer); model.setReaction(begin(),ReactionEmoji.HEART,true); runCurrent() }
        open(108); assertFalse(model.beginReactions(api.session.value,108,row())); assertTrue(model.state.value.reactionLimit)
        open(8); val pending=checkNotNull(model.state.value.reactionUnknown); api.reactionFailure=null
        model.checkReactionHistory(api.session.value,pending); runCurrent(); open(108); assertTrue(model.beginReactions(api.session.value,108,row()))
        assertEquals(100,api.reacts)
    }

    @Test fun pureSummaryApplicationDoesNotOverwriteReplacementOrOtherPeerMetadata() {
        val old=message(read="seen"); val replacement=message(body="Replacement")
        assertSame(replacement,applyDirectReactionResult(listOf(replacement),old,listOf(reaction(7))).single())
        val result=applyDirectReactionResult(listOf(old),old,listOf(reaction(7))).single()
        assertEquals(old.readAt,result.readAt); assertSame(old.sender,result.sender); assertSame(old.recipient,result.recipient)
        assertEquals(old.body,result.body); assertTrue(result.reactions.single().selectedBy(7))
    }

    private class Fixture: DirectOperations,MessageReactionOperations,MessageMutationOperations {
        val privacy=MessagePrivacy(DirectPrivacy(false,false,"admin","permanently",0),RevisionPrivacy(false,"super_admin",true,true,false))
        override val session=MutableStateFlow(SessionState(generation=1,phase=SessionPhase.AUTHENTICATED,
            user=SessionUser(7,"Viewer",listOf("member"),1,false),privacy=privacy,chatPolicy=ChatPolicy(emptySet(),null,DisplayPreferences())))
        val rows=mutableMapOf<Long,List<DirectMessage>>()
        var relationship=DirectRelationship(false,true)
        var reacts=0; var sends=0; var reads=0; var blocks=0; var authors=0; var histories=0
        var reactionFailure: ApiFailure?=null; var historyFailure: ApiFailure?=null; var sendFailure: ApiFailure?=null
        var readFailure: ApiFailure?=null; var blockFailure: ApiFailure?=null; var authorFailure: ApiFailure?=null
        var failGetAfterReaction=false
        var gate: CompletableDeferred<Unit>?=null; var readGate: CompletableDeferred<Unit>?=null
        override suspend fun context(peerId: Long)=DirectContext(MessagePerson(peerId,"Peer"),relationship,privacy,
            DisplayPreferences(),session.value.chatPolicy?.mutedEverywhere)
        override suspend fun history(peerId: Long,beforeId: Long?,limit: Int): DirectPage {
            histories++; historyFailure?.let { throw it }
            val page=rows.getOrPut(peerId) { listOf(message(peer=peerId)) }.filter { beforeId==null || it.id<beforeId }.takeLast(limit)
            return DirectPage(page,page.firstOrNull()?.id,page.size==limit)
        }
        override suspend fun inbox()=DirectInbox(emptyList(),privacy,DisplayPreferences())
        override suspend fun search(prefix: String,limit: Int)=emptyList<MessagePerson>()
        override suspend fun send(peerId: Long,body: String,replyId: Long?): Long { sends++; sendFailure?.let { throw it }; return 11 }
        override suspend fun read(peerId: Long): Int { reads++; readGate?.await(); readFailure?.let { throw it }; return 0 }
        override suspend fun setBlocked(peerId: Long,blocked: Boolean): DirectRelationship {
            blocks++; blockFailure?.let { throw it }; relationship=DirectRelationship(blocked,!blocked); return relationship
        }
        override suspend fun setDirect(expected: SessionState,original: DirectMessage,emoji: ReactionEmoji,selected: Boolean): List<MessageReaction> = withContext(NonCancellable) {
            reacts++; gate?.await(); val peer=if(original.outgoing) original.recipient.id else original.sender.id
            val current=rows[peer].orEmpty().first { it.id==original.id }
            val users=current.reactions.firstOrNull { it.emoji==emoji.value }?.users.orEmpty().filterNot { it.id==7L } +
                if(selected) listOf(MessagePerson(7,"Viewer")) else emptyList()
            val summary=current.reactions.filterNot { it.emoji==emoji.value } + if(users.isEmpty()) emptyList() else listOf(MessageReaction(emoji.value,users,false))
            rows[peer]=applyDirectReactionResult(rows[peer].orEmpty(),current,summary)
            if(failGetAfterReaction) historyFailure=ApiFailure(FailureKind.NETWORK)
            reactionFailure?.let { throw it }; summary
        }
        override suspend fun setRoom(expected: SessionState,original: RoomMessage,emoji: ReactionEmoji,selected: Boolean): List<MessageReaction> = error("No room actor")
        override suspend fun deleteDirect(expected: SessionState,original: DirectMessage): DirectMessage { authors++; authorFailure?.let { throw it }; error("No confirmed author write expected") }
        override suspend fun editDirect(expected: SessionState,original: DirectMessage,body: String): DirectMessage = error("No author edit expected")
        override suspend fun editRoom(expected: SessionState,original: RoomMessage,body: String): RoomMessage = error("No room actor")
        override suspend fun deleteRoom(expected: SessionState,original: RoomMessage): RoomMessage = error("No room actor")
    }
    companion object {
        private const val stamp="2026-11-05T03:00:00Z"
        private fun reaction(id: Long,flag: Boolean=false)=MessageReaction(ReactionEmoji.THUMBS_UP.value,listOf(MessagePerson(id,"Fixture")),flag)
        private fun message(peer: Long=8,body: String?="Body",outgoing: Boolean=false,deleted: Boolean=false,author: Boolean=false,
            read: String?=null,quote: DirectReply?=null,reactions: List<MessageReaction> = emptyList())=DirectMessage(10,
            MessagePerson(if(outgoing) 7 else peer,"Sender"),MessagePerson(if(outgoing) peer else 7,"Recipient"),body.takeUnless { deleted },
            read,stamp,outgoing,deleted,null,author,author,quote,emptyList(),reactions,null)
    }
}
