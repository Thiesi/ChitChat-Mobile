package io.github.thiesi.chitchat.feature.account

import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IgnorePersonViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fake = Ignores()
    private lateinit var model: IgnorePersonViewModel
    @Before fun start() { Dispatchers.setMain(dispatcher); model = IgnorePersonViewModel(fake) { 1000L } }
    @After fun stop() { model.cancel(); Dispatchers.resetMain() }
    private fun open(id: Long = 8): IgnorePersonTarget {
        model.open(fake.session.value, 42, id, "Peer")
        return checkNotNull(model.state.value.target)
    }

    @Test fun explicitIntentUsesOriginalIdentityAndOnlyAuthoritativeIds() = runTest(dispatcher) {
        runCurrent(); val target = open()
        fake.result = setOf(9)
        model.change(target, true); runCurrent()
        assertEquals(setOf(9L), fake.session.value.chatPolicy!!.ignoredUserIds)
        assertEquals(listOf(8L to true), fake.calls); assertEquals(7L, fake.expected?.user?.id)
        assertFalse(model.state.value.busy)
    }
    @Test fun staleButtonNeverReversesAnAlreadyAppliedChoice() = runTest(dispatcher) {
        val target = open(); fake.setIds(setOf(8))
        model.change(target, true); runCurrent(); assertTrue(fake.calls.isEmpty())
        model.change(target, false); runCurrent(); assertEquals(listOf(8L to false), fake.calls)
    }
    @Test fun unknownGuestSelfAndInvalidTargetsNeverPost() = runTest(dispatcher) {
        runCurrent(); model.open(fake.session.value, 42, 7, "Self"); assertNull(model.state.value.target)
        val target = open()
        fake.session.value = fake.session.value.copy(chatPolicy = null)
        model.change(target, true); runCurrent(); assertTrue(fake.calls.isEmpty())
        fake.session.value = fake.session.value.copy(phase = SessionPhase.GUEST)
        runCurrent(); assertNull(model.state.value.target)
        model.open(fake.session.value, 42, 8, "Peer"); assertNull(model.state.value.target)
    }
    @Test fun failureRequiresFreshGetNotRepeatedChoice() = runTest(dispatcher) {
        val target = open(); fake.failure = ApiFailure(FailureKind.NETWORK)
        model.change(target, true); runCurrent(); assertTrue(model.state.value.unconfirmed)
        model.change(target, true); runCurrent(); assertEquals(1, fake.calls.size)
        assertEquals(emptySet<Long>(), fake.session.value.chatPolicy!!.ignoredUserIds)
        fake.session.value = fake.session.value.copy(refreshRequired = false)
        runCurrent(); assertFalse(model.state.value.unconfirmed); assertNull(model.state.value.error)
    }
    @Test fun closeDuringPostCancelsWithoutReplayingAndLateTargetCannotCloseSuccessor() = runTest(dispatcher) {
        val old = open(); fake.hold = true
        model.change(old, true); runCurrent(); model.change(old, true); runCurrent()
        assertEquals(1, fake.calls.size)
        model.close(old); runCurrent(); assertNull(model.state.value.target)
        val current = open(9); model.close(old)
        assertSame(current, model.state.value.target)
        model.leaveRoom(fake.session.value, 43); assertSame(current, model.state.value.target)
        model.leaveRoom(fake.session.value, 42); assertNull(model.state.value.target)
        assertEquals(1, fake.calls.size)
    }
    @Test fun accountSwitchRetiresDialogAndOldCallbacks() = runTest(dispatcher) {
        val oldSession = fake.session.value; val target = open(); fake.hold = true
        model.change(target, true); runCurrent()
        fake.session.value = oldSession.copy(generation = 2, user = SessionUser(9, "Second", listOf("member"), 1, false))
        runCurrent(); assertNull(model.state.value.target)
        model.open(oldSession, 42, 8, "Stale"); model.change(target, false)
        runCurrent(); assertNull(model.state.value.target); assertEquals(1, fake.calls.size)
    }
    @Test fun cooldownBusyAndStoragePreventWrites() = runTest(dispatcher) {
        val target = open(); val original = fake.session.value
        for (blocked in listOf(original.copy(busy = true), original.copy(refreshRequired = true),
            original.copy(storageWarning = true), original.copy(retryAtMillis = 2000))) {
            fake.session.value = blocked; model.change(target, true); runCurrent()
        }
        assertTrue(fake.calls.isEmpty())
    }

    private class Ignores : IgnoreOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false),
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())))
        var result = emptySet<Long>()
        var expected: SessionState? = null
        var hold = false
        var failure: ApiFailure? = null
        val calls = mutableListOf<Pair<Long, Boolean>>()
        fun setIds(ids: Set<Long>) { session.value = session.value.copy(chatPolicy = ChatPolicy(ids, null, DisplayPreferences())) }
        override suspend fun change(expected: SessionState, userId: Long, ignored: Boolean): Set<Long> {
            this.expected = expected; calls += userId to ignored
            if (hold) awaitCancellation()
            failure?.let { session.value = session.value.copy(refreshRequired = true); throw it }
            setIds(result); return result
        }
    }
}
