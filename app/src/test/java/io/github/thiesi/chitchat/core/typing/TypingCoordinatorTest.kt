package io.github.thiesi.chitchat.core.typing

import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.network.apiJson
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TypingCoordinatorTest {
    private val room = TypingTarget.Room(42)
    private class Fixture(scope: TestScope, replay: Int = 0) {
        val api = Signals()
        val realtime = Events(replay)
        val model = TypingCoordinator(api, realtime, scope.backgroundScope,
            elapsed = { scope.testScheduler.currentTime }, wall = { 0 })
        val expected get() = api.session.value
        init { model.setForeground(true); model.select(expected, TypingTarget.Room(42), true, true) }
        fun hint(id: Long, now: Long, actor: Long = 8, room: Long? = 42, type: String = "typing") {
            val body = if (type == "typing") """{"room_id":$room,"user":{"id":$actor,"username":"Peer $actor"}}""" else "{}"
            assertTrue(realtime.events.tryEmit(RealtimeEvent(id, type, room, actor,
                apiJson.parseToJsonElement(body) as JsonObject, Instant.EPOCH, receivedAtMillis = now)))
        }
    }
    @Test fun attemptsRequireActualInputThrottleAndNeverAutomaticallyReplay() = runTest {
        val f = Fixture(this); runCurrent()
        f.model.input(f.expected, room, false); runCurrent(); assertEquals(0, f.api.calls.size)
        f.model.input(f.expected, room, true); runCurrent(); assertEquals(listOf(room), f.api.calls)
        f.model.input(f.expected, room, true); runCurrent(); assertEquals(1, f.api.calls.size)
        advanceTimeBy(10_000); runCurrent(); assertEquals(1, f.api.calls.size)
        f.model.input(f.expected, room, true); runCurrent(); assertEquals(2, f.api.calls.size)
        f.model.sent(f.expected, room); f.model.input(f.expected, room, true); runCurrent()
        assertEquals(3, f.api.calls.size)
    }
    @Test fun ownPublishingRestrictionDoesNotHideOtherPeopleAndMessageRemovesTheirHint() = runTest {
        val f = Fixture(this); f.model.select(f.expected, room, true, false); runCurrent()
        f.model.input(f.expected, room, true); runCurrent(); assertTrue(f.api.calls.isEmpty())
        f.hint(1, testScheduler.currentTime); runCurrent(); assertEquals(8L, f.model.state.value.people.single().id)
        f.hint(2, testScheduler.currentTime, type = "room_message"); runCurrent()
        assertTrue(f.model.state.value.people.isEmpty())
    }
    @Test fun expiryUsesOriginalLocalReceiptTimeAndReplayDoesNotExtendIt() = runTest {
        val f = Fixture(this); runCurrent(); f.hint(1, 0); runCurrent()
        advanceTimeBy(6000); runCurrent(); f.hint(1, 6000); runCurrent() // Duplicate ID is not a fresh signal.
        advanceTimeBy(500); runCurrent(); assertTrue(f.model.state.value.people.isEmpty())
        f.hint(2, 0); runCurrent(); assertTrue(f.model.state.value.people.isEmpty()) // Queued expired hint.
    }
    @Test fun directScopeRequiresTheSelectedPeerAndIgnoresRoomSignals() = runTest {
        val f = Fixture(this); f.model.select(f.expected, TypingTarget.Direct(8), true, true); runCurrent()
        f.hint(1, 0, room = null, actor = 9); f.hint(2, 0); runCurrent()
        assertTrue(f.model.state.value.people.isEmpty())
        f.hint(3, 0, room = null); runCurrent(); assertEquals(8L, f.model.state.value.people.single().id)
        f.hint(4, 0, room = null, type = "direct_message"); runCurrent()
        assertTrue(f.model.state.value.people.isEmpty())
    }
    @Test fun privacyUnknownOptOutIgnoresBackgroundAndOfflineClearWithoutPublication() = runTest {
        val f = Fixture(this); runCurrent(); f.hint(1, 0); runCurrent()
        f.api.session.value = f.expected.copy(shareTyping = null); runCurrent()
        assertTrue(f.model.state.value.people.isEmpty())
        f.model.input(f.expected, room, true); runCurrent(); assertTrue(f.api.calls.isEmpty())
        f.api.session.value = f.expected.copy(shareTyping = true,
            chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences())); runCurrent()
        f.hint(2, 0); runCurrent(); assertTrue(f.model.state.value.people.isEmpty())
        f.api.session.value = f.expected.copy(chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())); runCurrent()
        f.hint(3, 0); runCurrent(); assertEquals(1, f.model.state.value.people.size)
        f.model.setForeground(false); runCurrent(); assertTrue(f.model.state.value.people.isEmpty())
        f.model.input(f.expected, room, true); runCurrent(); assertTrue(f.api.calls.isEmpty())
        f.model.setForeground(true); f.realtime.state.value = f.realtime.state.value.copy(phase = RealtimePhase.OFFLINE)
        runCurrent(); f.model.input(f.expected, room, true); runCurrent(); assertTrue(f.api.calls.isEmpty())
    }
    @Test fun staleUiCallbacksCannotUpgradeToAnotherAccountWithTheSameNumericRoom() = runTest {
        val f = Fixture(this); runCurrent(); val original = f.expected
        f.api.session.value = original.copy(generation = 2, user = SessionUser(9, "Second", listOf("member"), 1, false))
        f.realtime.state.value = f.realtime.state.value.copy(generation = 2); runCurrent()
        f.model.select(original, room, true, true); f.model.input(original, room, true); runCurrent()
        assertTrue(f.api.calls.isEmpty())
        f.model.select(f.expected, room, true, true); f.model.input(original, room, true); runCurrent()
        assertTrue(f.api.calls.isEmpty())
        f.model.input(f.expected, room, true); runCurrent(); assertEquals(listOf(room), f.api.calls)
    }
    @Test fun lossOfVisibilityCancelsAnInFlightEphemeralPost() = runTest {
        val f = Fixture(this); f.api.hold = true; runCurrent()
        f.model.input(f.expected, room, true); runCurrent(); assertEquals(1, f.api.calls.size)
        f.model.select(f.expected, room, false, false); runCurrent(); assertEquals(1, f.api.cancelled)
        advanceTimeBy(10_000); runCurrent(); assertEquals(1, f.api.calls.size)
    }
    @Test fun disposalOfOldTargetCannotRetireTheNewTarget() = runTest {
        val f = Fixture(this); runCurrent()
        val direct = TypingTarget.Direct(8)
        f.model.select(f.expected, direct, true, true); f.model.release(f.expected, room); runCurrent()
        assertEquals(direct, f.model.state.value.target)
        f.model.input(f.expected, direct, true); runCurrent(); assertEquals(listOf(direct), f.api.calls)
        f.model.release(f.expected, direct); runCurrent(); assertNull(f.model.state.value.target)
    }
    @Test fun confirmedGlobalMuteBlocksPublicationButNotViewing() = runTest {
        val f = Fixture(this)
        val mute = apiJson.decodeFromString<io.github.thiesi.chitchat.core.conversation.RoomMute>(
            """{"id":1,"scope":"everywhere","room_id":null,"expires_at":null,"reason":"Fixture"}""")
        f.api.session.value = f.expected.copy(chatPolicy = ChatPolicy(emptySet(), mute, DisplayPreferences()))
        runCurrent(); f.model.input(f.expected, room, true); runCurrent(); assertTrue(f.api.calls.isEmpty())
        f.hint(1, 0); runCurrent(); assertEquals(8L, f.model.state.value.people.single().id)
    }
    @Test fun transientNamesAreBoundedWithoutEvictingAnActiveHint() = runTest {
        val f = Fixture(this); runCurrent()
        for (index in 1L..101L) { f.hint(index, 0, actor = index + 10); runCurrent() }
        assertEquals(100, f.model.state.value.people.size)
        assertTrue(f.model.state.value.people.any { it.id == 11L })
        advanceTimeBy(6_500); runCurrent(); assertTrue(f.model.state.value.people.isEmpty())
    }
    @Test fun roomIgnoreIsNotADirectTypingBlock() = runTest {
        val f = Fixture(this)
        val direct = TypingTarget.Direct(8)
        f.api.session.value = f.expected.copy(chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences()))
        f.model.select(f.expected, direct, true, false); runCurrent()
        f.hint(1, 0, room = null); runCurrent()
        assertEquals(listOf(8L), f.model.state.value.people.map { it.id })
        f.model.release(f.expected, direct); f.api.session.value = f.expected.copy(chatPolicy = null)
        f.model.select(f.expected, direct, true, false); runCurrent()
        f.hint(2, 0, room = null); runCurrent()
        assertEquals(listOf(8L), f.model.state.value.people.map { it.id })
        f.api.session.value = f.expected.copy(chatPolicy = ChatPolicy(setOf(8), null, DisplayPreferences()))
        f.model.select(f.expected, this@TypingCoordinatorTest.room, true, false); runCurrent()
        f.hint(3, 0); runCurrent(); assertTrue(f.model.state.value.people.isEmpty())
    }

    @Test fun retainedSourceReplayCannotBeReassignedToAReplacementAccount() = runTest {
        val f = Fixture(this, replay = 1); runCurrent(); f.hint(1, 0); runCurrent()
        assertEquals(8L, f.model.state.value.people.single().id)
        f.api.session.value = f.expected.copy(generation = 2, user = SessionUser(9, "Second", listOf("member"), 1, false))
        f.realtime.state.value = f.realtime.state.value.copy(generation = 2)
        f.model.select(f.expected, room, true, true); runCurrent()
        assertTrue(f.model.state.value.people.isEmpty())
        f.hint(2, 0, actor = 10); runCurrent()
        assertEquals(10L, f.model.state.value.people.single().id)
        assertEquals(9L, f.model.state.value.account?.user)
    }
    private class Signals : TypingOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false), shareTyping = true,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())))
        val calls = mutableListOf<TypingTarget>()
        var hold = false
        var cancelled = 0
        override suspend fun signal(expected: SessionState, target: TypingTarget): Boolean {
            calls += target
            if (hold) try { awaitCancellation() } finally { cancelled++ }
            return false // Suppressed is a normal server result.
        }
    }
    private class Events(replay: Int = 0) : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(generation = 1, phase = RealtimePhase.LIVE))
        override val events = MutableSharedFlow<RealtimeEvent>(replay = replay, extraBufferCapacity = 32)
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }
}
