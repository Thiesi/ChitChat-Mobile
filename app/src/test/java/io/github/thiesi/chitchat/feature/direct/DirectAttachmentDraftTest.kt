package io.github.thiesi.chitchat.feature.direct

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.conversation.RoomAttachmentPhase
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectAttachmentDraftTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Directs()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val store = ViewModelStore()
    private val lifetimes = mutableListOf<Job>()
    private val held = mutableListOf<Held>()
    private lateinit var model: DirectThreadViewModel
    private var entry: String? = null

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        model = DirectThreadViewModel(api, attachmentBudget = budget)
        store.put("direct", model)
        lifetimes += model.viewModelScope.coroutineContext[Job]!!
    }
    @After fun stop() = runTest(dispatcher) {
        try {
            held.forEach { it.finish.countDown() }; api.sendGate?.complete(Unit)
            store.clear(); lifetimes.forEach { it.join() }
            assertEquals(0L, budget.reservedBytes)
        } finally { Dispatchers.resetMain() }
    }
    private fun open(peer: Long = 8) {
        entry = "entry-$peer"; model.open(peer); model.foreground(); dispatcher.scheduler.runCurrent()
    }
    private fun pick(bytes: String = "opaque", peer: Long = 8, original: DirectAttachmentDraft? = null): Boolean {
        val read = PickedAttachmentRead("private-file", null) { ByteArrayInputStream(bytes.toByteArray()) }
        return model.selectAttachment(api.session.value, peer, "entry-$peer", read, AttachmentPickSource.FILE,
            original) { entry == "entry-$peer" }
    }
    private fun hold(peer: Long = 8): Held {
        val source = Held().also { held += it }
        assertTrue(model.selectAttachment(api.session.value, peer, "entry-$peer", source.source(),
            AttachmentPickSource.FILE) { entry == "entry-$peer" })
        dispatcher.scheduler.runCurrent(); return source
    }
    private suspend fun started(source: Held) = withContext(Dispatchers.IO) {
        assertTrue(source.started.await(5, TimeUnit.SECONDS))
    }
    private suspend fun prepared(peer: Long = 8): DirectAttachmentDraft = model.drafts.first {
        it[peer]?.attachment?.phase?.let { phase -> phase != DirectAttachmentPhase.PREPARING } == true
    }.getValue(peer).attachment!!

    @Test fun fileOnlyDraftCountsAndCannotSendItsCaptionAsATextMessage() = runTest(dispatcher) {
        open(); val calls = api.calls.toList()
        assertTrue(pick()); val ready = prepared()
        assertEquals(DirectAttachmentPhase.READY, ready.phase)
        assertEquals(AttachmentPhotoPrivacy.NON_PHOTO, ready.privacy)
        assertEquals(1, model.drafts.value.size)
        assertEquals("", model.drafts.value.getValue(8).body)
        assertEquals(calls, api.calls)
        model.updateDraft("caption"); assertFalse(model.canSend()); model.send()
        assertEquals(0, api.sends)
    }

    @Test fun navigationCompletesOnlyTheOriginalPrivateSlotWithItsCaptionAndReply() = runTest(dispatcher) {
        open(); model.updateDraft("first caption")
        assertTrue(model.replyTo(api.session.value, 8, model.state.value.messages.first()))
        val reply = model.state.value.composerReply
        val source = hold(); started(source)
        val original = model.drafts.value.getValue(8)
        open(9); model.updateDraft("other peer")
        val active = model.state.value; val calls = api.calls.toList()
        source.finish.countDown(); val ready = prepared()
        assertSame(original.slot, model.drafts.value.getValue(8).slot)
        assertSame(original.attachment!!.selection, ready.selection)
        assertEquals("first caption", model.drafts.value.getValue(8).body)
        assertSame(reply, model.drafts.value.getValue(8).composerReply)
        assertSame(active, model.state.value); assertEquals(calls, api.calls)
        assertNull(model.visibleAttachment(api.session.value, 8) { true })
        assertEquals(0, api.sends); assertEquals(0, api.reads); assertEquals(0, api.blocks)
    }

    @Test fun genuineBackgroundRetainsLocalWorkButVisibleAdoptionRequiresAFreshEntry() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        entry = null; model.background(); val calls = api.calls.toList()
        source.finish.countDown(); val ready = prepared()
        assertEquals(calls, api.calls)
        assertNull(model.visibleAttachment(api.session.value, 8) { true })
        model.foreground(); runCurrent()
        assertNull(model.visibleAttachment(api.session.value, 8) { entry != null })
        entry = "returned-entry"
        assertSame(ready, model.visibleAttachment(api.session.value, 8) { entry == "returned-entry" })
        assertEquals(1, source.opens.get()); assertEquals(0, api.sends)
    }

    @Test fun queuedReplacementRetainsTheRealPredecessorEvenWhenReplacedAgain() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        val original = model.drafts.value.getValue(8).attachment!!
        var skippedOpens = 0
        val skipped = PickedAttachmentRead("skipped", null) { skippedOpens++; ByteArrayInputStream(byteArrayOf(2)) }
        assertTrue(model.selectAttachment(api.session.value, 8, "entry-8", skipped,
            AttachmentPickSource.FILE, original) { true }); runCurrent()
        val middle = model.drafts.value.getValue(8).attachment!!
        assertTrue(pick("last", original = middle)); runCurrent()
        assertEquals(0, skippedOpens)
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        assertEquals(DirectAttachmentPhase.PREPARING, model.drafts.value.getValue(8).attachment!!.phase)
        source.finish.countDown(); val ready = prepared()
        assertEquals(DirectAttachmentPhase.READY, ready.phase)
        assertNotSame(original.selection, ready.selection)
        assertEquals(0, skippedOpens); assertEquals(1, source.opens.get())
        assertFalse(model.removeAttachment(api.session.value, 8, original) { true })
    }

    @Test fun discardRejectsTheHeldResultBeforeTheSlotIsReused() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        val original = model.drafts.value.getValue(8)
        assertTrue(model.discardSavedDraft(original) { true })
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        model.updateDraft("successor")
        assertNotSame(original.slot, model.drafts.value.getValue(8).slot)
        source.finish.countDown(); store.clear(); lifetimes.forEach { it.join() }
        assertEquals("successor", model.drafts.value.getValue(8).body)
        assertNull(model.drafts.value.getValue(8).attachment)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun discardingAQueuedReplacementNeverOpensItsSource() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        var opens = 0
        val read = PickedAttachmentRead("queued", null) { opens++; ByteArrayInputStream(byteArrayOf(2)) }
        assertTrue(model.selectAttachment(api.session.value, 8, "entry-8", read, AttachmentPickSource.FILE,
            model.drafts.value.getValue(8).attachment) { true }); runCurrent()
        assertTrue(model.discardSavedDraft(model.drafts.value.getValue(8)) { true })
        source.finish.countDown(); store.clear(); lifetimes.forEach { it.join() }
        assertEquals(0, opens); assertTrue(model.drafts.value.isEmpty())
    }

    @Test fun accountRetirementKeepsTheChargeUntilTheActualProducerEnds() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        api.session.value = api.session.value.copy(generation = 2,
            user = SessionUser(10, "Second", listOf("member"), 1, false)); runCurrent()
        assertTrue(model.drafts.value.isEmpty())
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        open(); model.updateDraft("new account")
        source.finish.countDown(); store.clear(); lifetimes.forEach { it.join() }
        assertEquals("new account", model.drafts.value.getValue(8).body)
        assertNull(model.drafts.value.getValue(8).attachment); assertEquals(0L, budget.reservedBytes)
    }

    @Test fun serverVersionAndSignOutRetireRetainedBytesAndOldActions() = runTest(dispatcher) {
        val initial = api.session.value
        val other = (ServerAddress.parse("https://other.example/") as AddressResult.Valid).address
        for (successor in listOf(initial.copy(server = other),
            initial.copy(user = SessionUser(7, "Viewer", listOf("member"), 2, false)),
            initial.copy(phase = SessionPhase.SIGNED_OUT, user = null))) {
            api.session.value = initial; runCurrent(); open()
            assertTrue(pick()); val original = model.drafts.value.getValue(8); prepared()
            api.session.value = successor; runCurrent()
            assertTrue(model.drafts.value.isEmpty()); assertEquals(0L, budget.reservedBytes)
            assertFalse(model.discardSavedDraft(original) { true })
            assertNull(model.visibleAttachment(initial, 8) { true })
        }
    }

    @Test fun falseEntryAndStaleSessionCannotOpenOrRemoveAFile() = runTest(dispatcher) {
        open(); val owner = api.session.value
        var opens = 0
        val read = PickedAttachmentRead("never-opened", null) { opens++; ByteArrayInputStream(byteArrayOf(2)) }
        assertFalse(model.selectAttachment(owner, 8, "entry-8", read, AttachmentPickSource.FILE) { false })
        assertFalse(model.selectAttachment(owner.copy(generation = 0), 8, "entry-8", read, AttachmentPickSource.FILE) { true })
        assertEquals(0, opens); assertTrue(pick()); val ready = prepared()
        assertNull(model.visibleAttachment(owner, 8) { false })
        assertFalse(model.removeAttachment(owner, 8, ready) { false })
        assertFalse(model.discardSavedDraft(model.drafts.value.getValue(8)) { false })
        assertTrue(model.removeAttachment(owner, 8, ready) { true })
        assertTrue(model.drafts.value.isEmpty()); assertEquals(0L, budget.reservedBytes)
    }

    @Test fun phaseCompletionDoesNotInvalidateDiscardButEditedContentAndReplacementDo() = runTest(dispatcher) {
        open(); model.updateDraft("caption"); val source = hold(); started(source)
        val preparing = model.drafts.value.getValue(8)
        source.finish.countDown(); prepared()
        assertNotSame(preparing, model.drafts.value.getValue(8))
        assertTrue(model.discardSavedDraft(preparing) { true })
        assertEquals(0L, budget.reservedBytes)
        model.updateDraft("old text"); val oldText = model.drafts.value.getValue(8)
        model.updateDraft("new text")
        assertFalse(model.discardSavedDraft(oldText) { true })
        assertTrue(pick()); prepared(); val oldFile = model.drafts.value.getValue(8)
        assertTrue(pick("new file", original = oldFile.attachment)); prepared()
        assertFalse(model.discardSavedDraft(oldFile) { true })
        assertEquals("new text", model.drafts.value.getValue(8).body)
    }

    @Test fun fileOnlyAndFailedDraftsShareTheExistingHundredSlotsWithoutEviction() = runTest(dispatcher) {
        for (peer in 8L..106L) { open(peer); model.updateDraft("caption") }
        open(107); assertTrue(pick("",107)); assertEquals(DirectAttachmentPhase.FAILED, prepared(107).phase)
        assertEquals(100, model.drafts.value.size)
        open(108); assertFalse(pick("opaque",108))
        val retained = model.drafts.value.getValue(8)
        assertTrue(model.discardSavedDraft(model.drafts.value.getValue(107)) { true })
        assertTrue(pick("opaque",108)); prepared(108)
        assertSame(retained, model.drafts.value.getValue(8)); assertEquals(100, model.drafts.value.size)
    }

    @Test fun unsupportedPhotoChoiceAndReplyCaptionEditsNeverGrantTextSend() = runTest(dispatcher) {
        open(); assertTrue(pick("GIF89a")); val choice = prepared()
        assertEquals(DirectAttachmentPhase.PRIVACY_CHOICE, choice.phase)
        assertEquals(PhotoPrivacyFailure.UNSUPPORTED_IMAGE, choice.privacyFailure)
        assertNull(choice.privacy)
        assertTrue(model.replyTo(api.session.value, 8, model.state.value.messages.first()))
        model.updateDraft("caption"); model.send()
        assertEquals(0, api.sends); assertNotNull(model.drafts.value.getValue(8).composerReply)
        assertFalse(choice.toString().contains("private-file"))
    }

    @Test fun pickingCannotCancelOrBypassTheSerializedSendOrItsUnknownOutcome() = runTest(dispatcher) {
        open(); model.updateDraft("text"); api.sendGate = CompletableDeferred()
        model.send(); runCurrent(); assertEquals(1, api.sends)
        assertFalse(pick()); assertTrue(model.state.value.sending)
        api.sendFailure = ApiFailure(FailureKind.TIMEOUT); api.sendGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.uncertainSend); assertFalse(pick())
        model.foreground(); runCurrent(); assertTrue(model.state.value.uncertainSend)
        assertEquals(1, api.sends)
    }

    @Test fun knownPolicyBlockAndMuteDenyBeforeProviderOpen() = runTest(dispatcher) {
        open()
        for (policy in listOf(AttachmentPolicy.Invalid, AttachmentPolicy.Unavailable,
            AttachmentPolicy.Known(0,emptySet(),emptySet(),null))) {
            api.session.value = api.session.value.copy(attachmentPolicy = policy); runCurrent(); assertFalse(pick())
        }
        api.session.value = api.session.value.copy(attachmentPolicy = AttachmentPolicy.Unknown); runCurrent()
        api.relationship = DirectRelationship(true,false); model.refresh(); runCurrent(); assertFalse(pick())
        api.relationship = DirectRelationship(false,true)
        api.mute = RoomMute(1,"everywhere",null,null,"fixture"); model.refresh(); runCurrent(); assertFalse(pick())
        assertEquals(0, api.sends)
    }

    @Test fun roomAndDirectPreparationCannotExceedTheSharedProcessLedgerOrAutoRetry() = runTest(dispatcher) {
        open()
        val room = ConversationViewModel(Rooms(api.session), attachmentBudget = budget)
        store.put("room",room); lifetimes += room.viewModelScope.coroutineContext[Job]!!
        room.foreground(); room.open(42); runCurrent()
        val source = Held().also { held += it }
        assertTrue(room.selectAttachment(api.session.value,42,"room-entry",source.source(),AttachmentPickSource.FILE) { true })
        runCurrent(); started(source)
        var opens = 0
        val denied = PickedAttachmentRead("denied",null) { opens++; ByteArrayInputStream(byteArrayOf(3)) }
        assertTrue(model.selectAttachment(api.session.value,8,"entry-8",denied,AttachmentPickSource.FILE) { true })
        val failure = prepared()
        assertEquals(DirectAttachmentPhase.FAILED,failure.phase)
        assertEquals(PreparationFailureKind.CLIENT_BUDGET,failure.failure); assertEquals(0,opens)
        source.finish.countDown()
        room.drafts.first { it[42]?.attachment?.phase==RoomAttachmentPhase.READY }
        assertEquals(0,opens); assertSame(failure,model.drafts.value.getValue(8).attachment)
        assertTrue(pick("explicit new choice",original=failure)); assertEquals(DirectAttachmentPhase.READY,prepared().phase)
        assertTrue(budget.reservedBytes < budget.capacityBytes)
    }

    private class Held {
        val started = CountDownLatch(1); val finish = CountDownLatch(1); val opens = AtomicInteger()
        fun source() = PickedAttachmentRead("private-held",null) {
            opens.incrementAndGet()
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                    started.countDown(); check(finish.await(5,TimeUnit.SECONDS))
                    return super.read(bytes,offset,length)
                }
            }
        }
    }
    private class Directs : DirectOperations {
        private val privacy = MessagePrivacy(DirectPrivacy(false,false,"admin","permanently",0),
            RevisionPrivacy(false,"super_admin",true,true,false))
        override val session = MutableStateFlow(SessionState(generation=1,phase=SessionPhase.AUTHENTICATED,
            server=(ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user=SessionUser(7,"Viewer",listOf("member"),1,false), privacy=privacy))
        val calls = mutableListOf<String>()
        var sends=0; var reads=0; var blocks=0
        var sendGate: CompletableDeferred<Unit>?=null; var sendFailure: ApiFailure?=null
        var relationship=DirectRelationship(false,true); var mute: RoomMute?=null
        override suspend fun context(peerId: Long): DirectContext {
            calls += "context"; return DirectContext(MessagePerson(peerId,"Peer"),relationship,privacy,DisplayPreferences(),mute)
        }
        override suspend fun inbox() = DirectInbox(emptyList(),privacy,DisplayPreferences())
        override suspend fun search(prefix: String,limit: Int) = emptyList<MessagePerson>()
        override suspend fun history(peerId: Long,beforeId: Long?,limit: Int): DirectPage {
            calls += "history"
            return DirectPage(listOf(DirectMessage(2,MessagePerson(peerId,"Peer"),MessagePerson(7,"Viewer"),
                "fixture",null,"2026-11-09T12:00:00Z",false,false,null,false,false,null,emptyList(),emptyList(),null)),2,false)
        }
        override suspend fun send(peerId: Long,body: String,replyId: Long?): Long {
            sends++; sendGate?.await(); sendFailure?.let { throw it }; return 3
        }
        override suspend fun read(peerId: Long): Int { reads++; return 1 }
        override suspend fun setBlocked(peerId: Long,blocked: Boolean): DirectRelationship { blocks++; return relationship }
    }
    private class Rooms(override val session: MutableStateFlow<SessionState>) : ConversationOperations {
        override suspend fun context(roomId: Long) = ConversationContext(
            RoomInfo(roomId,"room","Room","","public",0,0,7,"member",false,"none",0,0,null),
            ChatPolicy(emptySet(),null,DisplayPreferences()))
        override suspend fun history(roomId: Long,beforeId: Long?,limit: Int) = MessagePage(emptyList(),null,false)
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long,body: String,replyId: Long?): SendResult = error("No POST expected")
        override suspend fun read(roomId: Long,messageId: Long) = messageId
    }
}
