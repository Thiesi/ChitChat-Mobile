package io.github.thiesi.chitchat.feature.targets

import androidx.lifecycle.ViewModelStore
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageTargetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = Operations()
    private val store = ViewModelStore()
    private lateinit var model: MessageTargetViewModel
    private val a = MessageTarget.RoomMessage(42, 18)
    private val b = MessageTarget.RoomMessage(43, 19)
    @Before fun start() { Dispatchers.setMain(dispatcher); model = MessageTargetViewModel(operations); store.put("target", model) }
    @After fun stop() {
        model.background(); operations.gates.values.forEach { it.complete(Unit) }; dispatcher.scheduler.runCurrent()
        store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain()
    }
    private fun load(target: MessageTarget = a) = checkNotNull(model.load(operations.session.value, target))

    @Test fun backgroundSelectionDoesNoWorkAndForegroundFetchesExactlyOnce() = runTest(dispatcher) {
        val request = load(); runCurrent(); assertTrue(operations.calls.isEmpty())
        model.foreground(); runCurrent()
        assertEquals(listOf(request), operations.calls); assertTrue(model.state.value.result!!.available)
        model.foreground(); runCurrent(); assertEquals(1, operations.calls.size)
    }
    @Test fun exactAvailableResultCanBeConsumedOnlyOnceByItsOriginalRequest() = runTest(dispatcher) {
        model.foreground(); val request = load(); runCurrent()
        val result = model.consume(request); assertNotNull(result); assertTrue(result!!.matches(operations.session.value))
        assertNull(model.consume(request)); assertNull(model.state.value.request); assertNull(model.state.value.result)
    }
    @Test fun unavailableTargetIsNormalEvidenceAndCannotBeConsumedAsAFoundMessage() = runTest(dispatcher) {
        operations.found = false; model.foreground(); val request = load(); runCurrent()
        assertNotNull(model.state.value.result); assertFalse(model.state.value.result!!.available)
        assertNull(model.state.value.error); assertFalse(model.state.value.stale); assertNull(model.consume(request))
    }
    @Test fun rejectingConsumerKeepsTheSelectionUntilItCanAdoptExactlyOnce() = runTest(dispatcher) {
        model.foreground(); val request = load(); runCurrent()
        var calls = 0
        assertFalse(model.adopt(request) { calls++; false })
        assertSame(request, model.state.value.request); assertNotNull(model.state.value.result)
        assertTrue(model.adopt(request) { calls++; true }); assertEquals(2, calls)
        assertFalse(model.adopt(request) { calls++; true }); assertEquals(2, calls)
    }
    @Test fun acceptedConsumerCannotClearASelectionItReplacedDuringHandoff() = runTest(dispatcher) {
        model.foreground(); val request = load(); runCurrent()
        var successor: TargetRequest? = null
        assertTrue(model.adopt(request) { successor = load(b); true })
        runCurrent(); assertSame(successor, model.state.value.request)
        assertSame(successor, model.state.value.result?.request)
    }
    @Test fun staleCloseAndRetryCannotRetireOrReloadTheSuccessor() = runTest(dispatcher) {
        model.foreground(); val old = load(); runCurrent(); val next = load(b); runCurrent()
        model.cancel(old); model.retry(old); runCurrent()
        assertSame(next, model.state.value.request); assertEquals(2, operations.calls.size)
        assertTrue(model.state.value.result!!.available)
    }
    @Test fun lateNonCooperativeOldLookupCannotReplaceTheNewResult() = runTest(dispatcher) {
        operations.gates[a] = CompletableDeferred(); operations.ignoreCancellation = true
        model.foreground(); val old = load(); runCurrent(); val next = load(b); runCurrent()
        operations.gates.getValue(a).complete(Unit); runCurrent()
        assertSame(next, model.state.value.result!!.request); assertNotSame(old, model.state.value.result!!.request)
    }
    @Test fun logoutIndependentlyFencesTheSnapshotBeforeCollectorClearsIt() = runTest(dispatcher) {
        model.foreground(); val old = load(); runCurrent(); val snapshot = checkNotNull(model.state.value.result)
        val original = operations.session.value
        operations.session.value = SessionState(original.server, original.generation + 1, SessionPhase.SIGNED_OUT)
        assertFalse(snapshot.matches(operations.session.value)); assertNull(model.consume(old))
        runCurrent(); assertNull(model.state.value.request); assertNull(model.state.value.result)
        assertNull(model.load(original, b)); assertEquals(1, operations.calls.size)
    }
    @Test fun replacementAccountClearsContentAndRejectsOriginalSessionCallbacks() = runTest(dispatcher) {
        model.foreground(); val original = operations.session.value; val old = load(); runCurrent()
        operations.session.value = original.copy(generation = 2, user = SessionUser(9, "Replacement", listOf("member"), 1, false))
        runCurrent(); assertNull(model.state.value.result)
        val next = load(b); runCurrent()
        assertNull(model.load(original, a)); model.cancel(old); model.retry(old); runCurrent()
        assertSame(next, model.state.value.request); assertEquals(2, operations.calls.size)
    }
    @Test fun genuineBackgroundDropsPrivateBodyAndResumeReconcilesWithGet() = runTest(dispatcher) {
        model.foreground(); val request = load(); runCurrent(); model.background(); runCurrent()
        assertSame(request, model.state.value.request); assertNull(model.state.value.result); assertTrue(model.state.value.stale)
        assertEquals(1, operations.calls.size)
        model.foreground(); runCurrent(); assertEquals(2, operations.calls.size); assertTrue(model.state.value.result!!.available)
    }
    @Test fun sessionBusyQueuesSelectionButCannotManufactureASecondLookupOnResume() = runTest(dispatcher) {
        operations.session.value = operations.session.value.copy(busy = true)
        model.foreground(); load(); runCurrent(); assertTrue(model.state.value.waitingForSession); assertTrue(operations.calls.isEmpty())
        model.foreground(); operations.session.value = operations.session.value.copy(busy = false); runCurrent()
        assertEquals(1, operations.calls.size); assertFalse(model.state.value.loading)
        operations.session.value = operations.session.value.copy(shareTyping = true); runCurrent(); assertEquals(1, operations.calls.size)
    }
    @Test fun configurationForegroundDuringInFlightGetDoesNotLeaveAnImplicitRetryQueued() = runTest(dispatcher) {
        operations.gates[a] = CompletableDeferred(); model.foreground(); load(); runCurrent(); model.foreground()
        operations.gates.getValue(a).complete(Unit); runCurrent()
        operations.session.value = operations.session.value.copy(shareTyping = true); runCurrent()
        assertEquals(1, operations.calls.size); assertTrue(model.state.value.result!!.available)
    }
    @Test fun failureIsVisibleAndNeedsExplicitRetryRatherThanAStateEmissionLoop() = runTest(dispatcher) {
        operations.failure = ApiFailure(FailureKind.NETWORK); model.foreground(); val request = load(); runCurrent()
        assertEquals(FailureKind.NETWORK, model.state.value.error?.kind); assertNull(model.state.value.result)
        operations.session.value = operations.session.value.copy(shareTyping = true); runCurrent(); assertEquals(1, operations.calls.size)
        operations.failure = null; model.retry(request); runCurrent(); assertTrue(model.state.value.result!!.available); assertEquals(2, operations.calls.size)
    }
    @Test fun invalidOrSelfTargetsAndNonMemberScopesDoNotReplaceAValidSelection() = runTest(dispatcher) {
        model.foreground(); val current = load(); runCurrent(); val original = operations.session.value
        for (bad in listOf(MessageTarget.RoomMessage(0, 1), MessageTarget.RoomPing(42, 0), MessageTarget.DirectMessage(7, 1)))
            assertNull(model.load(original, bad))
        assertNull(model.load(original.copy(phase = SessionPhase.GUEST), b))
        assertSame(current, model.state.value.request); assertEquals(1, operations.calls.size)
    }
    @Test fun aSnapshotBoundToAnotherRequestIsNotPublishableEvenInTheSameAccount() = runTest(dispatcher) {
        operations.wrongRequest = true; model.foreground(); load(); runCurrent()
        assertEquals(FailureKind.INCOMPATIBLE, model.state.value.error?.kind); assertNull(model.state.value.result)
    }
    @Test fun busyOrUnvalidatedSessionCannotConsumeAlreadyFetchedData() = runTest(dispatcher) {
        model.foreground(); val request = load(); runCurrent()
        operations.session.value = operations.session.value.copy(refreshRequired = true)
        assertNull(model.consume(request)); operations.session.value = operations.session.value.copy(refreshRequired = false, busy = true)
        assertNull(model.consume(request))
    }
    @Test fun consumingAReadyResultNeverRestoresOldIgnoreOrMutePolicy() = runTest(dispatcher) {
        model.foreground(); val request = load(); runCurrent()
        val mute = RoomMute(1, "everywhere", null, null, "Fixture reason")
        operations.session.value = operations.session.value.copy(chatPolicy = ChatPolicy(setOf(8), mute, DisplayPreferences()))
        val result = model.consume(request) as TargetSnapshot.RoomMessage
        assertEquals(setOf(8L), result.context.policy.ignoredUserIds); assertSame(mute, result.context.mute)
        assertTrue(result.pings.isEmpty())
    }
    @Test fun missingCurrentPolicyCannotBeSubstitutedWithTheOldSnapshotPolicy() = runTest(dispatcher) {
        model.foreground(); val request = load(); runCurrent()
        operations.session.value = operations.session.value.copy(chatPolicy = null)
        assertNull(model.consume(request)); assertNull(model.state.value.result)
        assertEquals(FailureKind.INCOMPATIBLE, model.state.value.error?.kind)
    }
    @Test fun noGeneratedStateOrSnapshotStringContainsTheMessageBody() = runTest(dispatcher) {
        model.foreground(); load(); runCurrent()
        assertFalse(model.state.value.toString().contains("Private fixture"))
        assertFalse(model.state.value.result.toString().contains("Private fixture"))
    }

    private class Operations : MessageTargetOperations {
        override val session = MutableStateFlow(SessionState(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
            generation = 1, phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false),
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())))
        val calls = mutableListOf<TargetRequest>(); val gates = mutableMapOf<MessageTarget, CompletableDeferred<Unit>>()
        var found = true; var failure: ApiFailure? = null; var ignoreCancellation = false; var wrongRequest = false
        override suspend fun lookup(request: TargetRequest): TargetSnapshot {
            calls.add(request)
            gates[request.target]?.let { gate -> if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await() }
            failure?.let { throw it }
            val target = request.target as MessageTarget.RoomMessage
            val bound = if (wrongRequest) TargetRequest(request.original, request.target) else request
            val room = RoomInfo(target.roomId, "room${target.roomId}", "Room fixture", "", "public", 0, 0, 9, "member", false, "none")
            val messages = if (found) listOf(RoomMessage(target.id, target.roomId, 8, "Peer", "text", "Private fixture", false, "2026-10-20T12:00:00Z")) else emptyList()
            return TargetSnapshot.RoomMessage(bound, ConversationContext(room, ChatPolicy(emptySet(), null, DisplayPreferences())),
                MessagePage(messages, messages.firstOrNull()?.id, false), listOf(RoomPing(5, target.roomId,
                    MessagePerson(8, "Peer"), MessagePerson(7, "Viewer"), "Private ping fixture", "2026-10-20T12:00:00Z")))
        }
    }
}
