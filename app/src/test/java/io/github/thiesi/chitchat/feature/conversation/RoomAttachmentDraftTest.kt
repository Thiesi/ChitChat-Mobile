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
import io.github.thiesi.chitchat.testing.pngWithPrivateText
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
class RoomAttachmentDraftTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Rooms()
    private val budget = AttachmentMemoryBudget(12L * 1024 * 1024)
    private val store = ViewModelStore()
    private lateinit var model: ConversationViewModel
    private val held = mutableListOf<Held>()
    private var entry: String? = null

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        model = ConversationViewModel(api, attachmentBudget = budget)
        store.put("fixture", model)
    }
    @After fun stop() = runTest(dispatcher) {
        try {
            held.forEach { it.finish.countDown() }
            val lifetime = model.viewModelScope.coroutineContext[Job]!!
            store.clear()
            lifetime.join()
            assertEquals(0L, budget.reservedBytes)
        } finally { Dispatchers.resetMain() }
    }

    private fun open(room: Long = 42) {
        entry = "entry-$room"
        model.foreground(); model.open(room); dispatcher.scheduler.runCurrent()
    }
    private fun pick(bytes: String = "opaque", room: Long = 42, original: RoomAttachmentDraft? = null): Boolean {
        val source = PickedAttachmentRead("private-file", null) { ByteArrayInputStream(bytes.toByteArray()) }
        return model.selectAttachment(api.session.value, room, "entry-$room", source, AttachmentPickSource.FILE,
            original) { entry == "entry-$room" }
    }
    private fun hold(room: Long = 42): Held {
        val value = Held().also { held += it }
        assertTrue(model.selectAttachment(api.session.value, room, "entry-$room", value.source(),
            AttachmentPickSource.FILE) { entry == "entry-$room" })
        dispatcher.scheduler.runCurrent()
        return value
    }
    private suspend fun started(value: Held) = withContext(Dispatchers.IO) {
        assertTrue(value.started.await(5, TimeUnit.SECONDS))
    }
    private suspend fun prepared(room: Long = 42): RoomAttachmentDraft = model.drafts.first {
        it[room]?.attachment?.phase?.let { phase -> phase != RoomAttachmentPhase.PREPARING } == true
    }.getValue(room).attachment!!

    @Test fun fileOnlyDraftIsCountedAndPreparationMakesNoAdditionalNetworkOrSend() = runTest(dispatcher) {
        open(); val calls = api.calls.toList()
        assertTrue(pick()); val ready = prepared()
        assertEquals(RoomAttachmentPhase.READY, ready.phase)
        assertEquals(AttachmentPhotoPrivacy.NON_PHOTO, ready.privacy)
        assertEquals(1, model.drafts.value.size)
        assertEquals("", model.drafts.value.getValue(42).body)
        assertEquals(calls, api.calls)
        assertFalse(model.canSend()); model.send()
        assertEquals(0, api.sends)
    }

    @Test fun navigationCompletesOnlyTheOriginalPrivateSlotAndPreservesBothCaptionsAndReply() = runTest(dispatcher) {
        open(); model.updateDraft("first caption")
        assertTrue(model.replyTo(api.session.value, 42, model.state.value.messages.first()))
        val reply = model.state.value.composerReply
        val source = hold(); started(source)
        val original = model.drafts.value.getValue(42)
        open(43); model.updateDraft("other room")
        val active = model.state.value
        source.finish.countDown(); val ready = prepared()
        assertSame(original.slot, model.drafts.value.getValue(42).slot)
        assertSame(original.attachment!!.selection, ready.selection)
        assertEquals("first caption", model.drafts.value.getValue(42).body)
        assertSame(reply, model.drafts.value.getValue(42).composerReply)
        assertSame(active, model.state.value)
        assertNull(model.visibleAttachment(api.session.value, 42) { true })
        assertEquals(0, api.sends)
    }

    @Test fun genuineBackgroundRetainsLocalWorkButVisibleAdoptionNeedsAFreshEntry() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        entry = null; model.background()
        source.finish.countDown(); val ready = prepared()
        assertNull(model.visibleAttachment(api.session.value, 42) { true })
        model.foreground(); runCurrent()
        assertNull(model.visibleAttachment(api.session.value, 42) { entry != null })
        entry = "new-entry-after-return"
        assertSame(ready, model.visibleAttachment(api.session.value, 42) { entry == "new-entry-after-return" })
        assertEquals(1, source.opens.get())
        assertEquals(0, api.sends)
    }

    @Test fun replacementWaitsForTheRetiredProducerWithoutReopeningOrLosingItsNewSelection() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        val original = model.drafts.value.getValue(42).attachment!!
        var opens = 0
        val replacement = PickedAttachmentRead("replacement", null) { opens++; ByteArrayInputStream(byteArrayOf(9)) }
        assertTrue(model.selectAttachment(api.session.value, 42, "entry-42", replacement,
            AttachmentPickSource.FILE, original) { true })
        runCurrent()
        assertEquals(0, opens)
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        source.finish.countDown(); val ready = prepared()
        assertEquals(RoomAttachmentPhase.READY, ready.phase)
        assertNotSame(original.selection, ready.selection)
        assertEquals("replacement", ready.filename)
        assertEquals(1, opens)
        assertEquals(1, source.opens.get())
        assertFalse(model.removeAttachment(api.session.value, 42, original) { true })
        assertSame(ready, model.drafts.value.getValue(42).attachment)
    }

    @Test fun discardInvalidatesTheSlotBeforeTheHeldReadEnds() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        val old = model.drafts.value.getValue(42)
        model.discardDraft(old)
        assertTrue(model.drafts.value.isEmpty())
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        model.updateDraft("successor")
        val successor = model.drafts.value.getValue(42)
        assertNotSame(old.slot, successor.slot)
        source.finish.countDown()
        store.clear(); model.viewModelScope.coroutineContext[Job]!!.join()
        assertEquals("successor", model.drafts.value.getValue(42).body)
        assertNull(model.drafts.value.getValue(42).attachment)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun replacingAQueuedReplacementKeepsTheOriginalProducerBarrier() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        var skippedOpens = 0
        val skipped = PickedAttachmentRead("skipped", null) { skippedOpens++; ByteArrayInputStream(byteArrayOf(2)) }
        assertTrue(model.selectAttachment(api.session.value, 42, "entry-42", skipped,
            AttachmentPickSource.FILE, model.drafts.value.getValue(42).attachment) { true })
        runCurrent() // The middle selection is waiting for the held first producer.
        val middle = model.drafts.value.getValue(42).attachment!!
        assertTrue(pick("last", original = middle)); runCurrent()
        assertEquals(0, skippedOpens)
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        assertEquals(RoomAttachmentPhase.PREPARING, model.drafts.value.getValue(42).attachment!!.phase)
        source.finish.countDown()
        assertEquals(RoomAttachmentPhase.READY, prepared().phase)
        assertEquals(0, skippedOpens)
        assertEquals(1, source.opens.get())
    }

    @Test fun discardingAQueuedReplacementPreventsItsProviderFromOpening() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        var opens = 0
        val read = PickedAttachmentRead("never-opened", null) { opens++; ByteArrayInputStream(byteArrayOf(2)) }
        assertTrue(model.selectAttachment(api.session.value, 42, "entry-42", read,
            AttachmentPickSource.FILE, model.drafts.value.getValue(42).attachment) { true })
        runCurrent(); model.discardDraft(model.drafts.value.getValue(42))
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        source.finish.countDown()
        store.clear(); model.viewModelScope.coroutineContext[Job]!!.join()
        assertEquals(0, opens)
        assertTrue(model.drafts.value.isEmpty())
    }

    @Test fun sessionRetirementCancelsPrivateWorkWithoutChargingItsSuccessorEarly() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        val old = api.session.value
        api.session.value = old.copy(generation = 2, user = SessionUser(9, "Second", listOf("member"), 1, false))
        runCurrent()
        assertTrue(model.drafts.value.isEmpty())
        assertEquals(budget.capacityBytes, budget.reservedBytes)
        open(); model.updateDraft("new account")
        source.finish.countDown()
        store.clear(); model.viewModelScope.coroutineContext[Job]!!.join()
        assertEquals("new account", model.drafts.value.getValue(42).body)
        assertNull(model.drafts.value.getValue(42).attachment)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun staleOwnerAndFalseActualEntryCannotStartAProviderOrRemoveANewChoice() = runTest(dispatcher) {
        open(); val old = api.session.value
        var opens = 0
        val read = PickedAttachmentRead("private", null) { opens++; ByteArrayInputStream(byteArrayOf(1)) }
        assertFalse(model.selectAttachment(old, 42, "entry-42", read, AttachmentPickSource.FILE) { false })
        assertFalse(model.selectAttachment(old.copy(generation = 0), 42, "entry-42", read,
            AttachmentPickSource.FILE) { true })
        assertEquals(0, opens)
        assertTrue(pick()); val ready = prepared()
        assertNull(model.visibleAttachment(old, 42) { false })
        assertFalse(model.removeAttachment(old, 42, ready) { false })
        assertTrue(model.removeAttachment(old, 42, ready) { true })
        assertEquals(0L, budget.reservedBytes)
        assertTrue(model.drafts.value.isEmpty())
    }

    @Test fun serverVersionAndSignOutEachRetireRetainedBytesAndRejectOldActions() = runTest(dispatcher) {
        val initial = api.session.value
        val otherServer = (ServerAddress.parse("https://other.example/") as AddressResult.Valid).address
        for (successor in listOf(initial.copy(server = otherServer),
            initial.copy(user = SessionUser(7, "Viewer", listOf("member"), 2, false)),
            initial.copy(phase = SessionPhase.SIGNED_OUT, user = null))) {
            api.session.value = initial; runCurrent(); open()
            assertTrue(pick()); val original = prepared()
            api.session.value = successor; runCurrent()
            assertTrue(model.drafts.value.isEmpty())
            assertEquals(0L, budget.reservedBytes)
            assertNull(model.visibleAttachment(initial, 42) { true })
            assertFalse(model.removeAttachment(initial, 42, original) { true })
        }
    }

    @Test fun replyAndCaptionCanChangeWithAnAttachmentWithoutEnablingTextOnlySend() = runTest(dispatcher) {
        open(); assertTrue(pick()); prepared()
        assertTrue(model.replyTo(api.session.value, 42, model.state.value.messages.first()))
        model.updateDraft("caption after attaching")
        assertNotNull(model.drafts.value.getValue(42).composerReply)
        assertEquals("caption after attaching", model.drafts.value.getValue(42).body)
        assertEquals(RoomAttachmentPhase.READY, model.drafts.value.getValue(42).attachment!!.phase)
        assertFalse(model.canSend()); model.send(); assertEquals(0, api.sends)
    }

    @Test fun pendingAndFailedAttachmentsShareTheExistingDraftCapacityWithoutEviction() = runTest(dispatcher) {
        open()
        for (room in 42L..140L) { open(room); model.updateDraft("caption") }
        open(141); assertTrue(pick("", 141)); assertEquals(RoomAttachmentPhase.FAILED, prepared(141).phase)
        assertEquals(100, model.drafts.value.size)
        open(142); assertFalse(pick("opaque", 142))
        assertEquals(100, model.drafts.value.size)
        val retained = model.drafts.value.getValue(42)
        model.discardDraft(model.drafts.value.getValue(141))
        assertTrue(pick("opaque", 142)); prepared(142)
        assertSame(retained, model.drafts.value.getValue(42))
        assertEquals(100, model.drafts.value.size)
    }

    @Test fun unsupportedPhotosRemainAnExplicitPrivateChoiceAndNeverGrantSend() = runTest(dispatcher) {
        open(); model.updateDraft("caption")
        assertTrue(pick("GIF89a")); val choice = prepared()
        assertEquals(RoomAttachmentPhase.PRIVACY_CHOICE, choice.phase)
        assertEquals(PhotoPrivacyFailure.UNSUPPORTED_IMAGE, choice.privacyFailure)
        assertNull(choice.privacy)
        model.send(); assertEquals(0, api.sends)
        assertEquals("caption", model.state.value.draft)
        assertFalse(choice.toString().contains("private-file"))
    }

    @Test fun policyAndUncertainTextGuardsCannotBeBypassedByPickingAFile() = runTest(dispatcher) {
        open(); model.updateDraft("unknown text")
        api.failure = ApiFailure(FailureKind.TIMEOUT); model.send(); runCurrent()
        assertTrue(model.state.value.uncertainSend)
        assertFalse(pick())
        assertTrue(model.state.value.uncertainSend)
        api.failure = null; model.refresh(); runCurrent(); model.acknowledgeDuplicateRisk()
        for (policy in listOf(AttachmentPolicy.Invalid, AttachmentPolicy.Unavailable)) {
            api.session.value = api.session.value.copy(attachmentPolicy = policy); runCurrent()
            assertFalse(pick())
        }
        assertEquals(1, api.sends)
    }

    @Test fun captionEditsDoNotRetireTheOriginalPreparationSlot() = runTest(dispatcher) {
        open(); val source = hold(); started(source)
        val slot = model.drafts.value.getValue(42).slot
        model.updateDraft("updated while preparing")
        source.finish.countDown(); prepared()
        assertSame(slot, model.drafts.value.getValue(42).slot)
        assertEquals("updated while preparing", model.drafts.value.getValue(42).body)
    }

    @Test fun currentPolicyFactsDoNotMutateThePrivateDraftOrGrantSend() = runTest(dispatcher) {
        open(); val owner = api.session.value
        assertTrue(pick()); val ready = prepared()
        val memo = model.drafts.value.getValue(42); val charge = budget.reservedBytes; val calls = api.calls.toList()
        api.session.value = owner.copy(attachmentPolicy = AttachmentPolicy.Known(5,emptySet(),emptySet(),null)); runCurrent()
        val blocked = model.attachmentPolicyObservation(owner,42,ready) { true } as AttachmentPolicyObservation.TooLarge
        assertEquals(6L,blocked.sizeBytes); assertEquals(5L,blocked.maxBytes)
        api.session.value = owner.copy(attachmentPolicy = AttachmentPolicy.Known(6,emptySet(),emptySet(),null)); runCurrent()
        assertSame(AttachmentPolicyObservation.WithinAdvertisedFacts,model.attachmentPolicyObservation(owner,42,ready) { true })
        api.session.value = owner.copy(attachmentPolicy = AttachmentPolicy.Invalid); runCurrent()
        assertSame(AttachmentPolicyObservation.Invalid,model.attachmentPolicyObservation(owner,42,ready) { true })
        api.session.value = owner; runCurrent()
        assertSame(AttachmentPolicyObservation.Unadvertised,model.attachmentPolicyObservation(owner,42,ready) { true })
        assertNull(model.attachmentPolicyObservation(owner,42,ready) { false })
        assertNull(model.attachmentPolicyObservation(owner,43,ready) { true })
        assertSame(memo,model.drafts.value.getValue(42)); assertEquals(charge,budget.reservedBytes)
        assertEquals(calls,api.calls); assertFalse(model.canSend()); model.send(); assertEquals(0,api.sends)
        model.background(); assertNull(model.attachmentPolicyObservation(owner,42,ready) { true })
    }

    @Test fun policyUsesCleanedPhotoBytesAndNeverTheUnchosenOriginalSize() = runTest(dispatcher) {
        open(); val owner = api.session.value; val png = pngWithPrivateText()
        assertTrue(png.size>80)
        assertTrue(model.selectAttachment(owner,42,"entry-42",
            PickedAttachmentRead("photo","image/png") { ByteArrayInputStream(png) },AttachmentPickSource.FILE) { true })
        val clean = prepared(); assertEquals(AttachmentPhotoPrivacy.LOCATION_REMOVED,clean.privacy)
        assertTrue(clean.sizeBytes!!<=80)
        api.session.value = owner.copy(attachmentPolicy=AttachmentPolicy.Known(80,setOf("image/png","image/gif"),emptySet(),null)); runCurrent()
        assertSame(AttachmentPolicyObservation.WithinAdvertisedFacts,model.attachmentPolicyObservation(owner,42,clean) { true })
        val gif = "GIF89a" + "x".repeat(512)
        assertTrue(model.selectAttachment(api.session.value,42,"entry-42",
            PickedAttachmentRead("photo","image/gif") { ByteArrayInputStream(gif.toByteArray()) },
            AttachmentPickSource.FILE,clean) { true })
        val choice = prepared(); assertEquals(RoomAttachmentPhase.PRIVACY_CHOICE,choice.phase); assertTrue(choice.sizeBytes!!>80)
        assertSame(AttachmentPolicyObservation.AwaitingFinalBytes,model.attachmentPolicyObservation(owner,42,choice) { true })
        assertNull(model.attachmentPolicyObservation(owner,42,clean) { true })
        api.session.value = owner.copy(generation=2); runCurrent()
        assertNull(model.attachmentPolicyObservation(owner,42,choice) { true }); assertEquals(0,api.sends)
    }

    private class Held {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val opens = AtomicInteger()
        val closed = AtomicBoolean()
        fun source() = PickedAttachmentRead("private-held", null) {
            opens.incrementAndGet()
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    started.countDown(); check(finish.await(5, TimeUnit.SECONDS))
                    return super.read(bytes, offset, length)
                }
                override fun close() { closed.set(true); super.close() }
            }
        }
    }
    private class Rooms : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            server = (ServerAddress.parse("https://fixture.example/") as AddressResult.Valid).address,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        val calls = mutableListOf<String>()
        var sends = 0
        var failure: ApiFailure? = null
        override suspend fun context(roomId: Long): ConversationContext {
            calls += "context"
            return ConversationContext(RoomInfo(roomId, "room", "Room", "", "public", 0, 0, 7, "member", false,
                "none", 0, 0, null), ChatPolicy(emptySet(), null, DisplayPreferences()))
        }
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            calls += "history"
            return MessagePage(listOf(RoomMessage(2, roomId, 8, "Peer", "text", "fixture", false, "2026-11-09T12:00:00Z")), 2, false)
        }
        override suspend fun pings(roomId: Long): List<RoomPing> { calls += "pings"; return emptyList() }
        override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult {
            sends++; failure?.let { throw it }
            return SendResult.Message(RoomMessage(3, roomId, 7, "Viewer", "text", body, false, "2026-11-09T12:00:00Z"))
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { calls += "read"; return messageId }
    }
}
