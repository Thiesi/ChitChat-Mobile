package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationIgnorePolicyTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = Conversations()
    private lateinit var model: ConversationViewModel
    @Before fun start() { Dispatchers.setMain(dispatcher); model = ConversationViewModel(api, Events()) }
    @After fun stop() { model.cancel(); Dispatchers.resetMain() }
    private fun ids(ids: Set<Long>) { api.session.value = api.session.value.copy(chatPolicy = ChatPolicy(ids, null, DisplayPreferences())) }

    @Test fun lateInitialHistoryCannotRestoreAnOlderIgnoreSnapshot() = runTest(dispatcher) {
        api.historyGate = CompletableDeferred()
        model.open(42); runCurrent()
        ids(setOf(8)); runCurrent(); api.historyGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.loaded)
        assertEquals(setOf(8L), model.state.value.context!!.policy.ignoredUserIds)
        assertTrue(model.state.value.pings.isEmpty())
    }
    @Test fun policyChangeClearsTemporaryRevealAndFiltersPrivatePingsImmediately() = runTest(dispatcher) {
        model.open(42); runCurrent(); model.reveal(2)
        assertEquals(listOf(9L), model.state.value.pings.map { it.id })
        ids(setOf(8)); runCurrent()
        assertEquals(setOf(8L), model.state.value.context!!.policy.ignoredUserIds)
        assertTrue(model.state.value.revealed.isEmpty()); assertTrue(model.state.value.pings.isEmpty())
        assertEquals(listOf(2L, 3L), model.state.value.messages.map { it.id })
    }
    @Test fun unignoreReconcilesPingsWithoutReplayingSendOrLosingDraftAndAnchor() = runTest(dispatcher) {
        model.open(42); model.foreground(); runCurrent()
        model.updateDraft("Unsent fixture"); model.viewport(TimelineAnchor(2, 7), null, false)
        ids(setOf(8)); runCurrent(); assertTrue(model.state.value.pings.isEmpty())
        ids(emptySet()); runCurrent()
        assertEquals(listOf(9L), model.state.value.pings.map { it.id })
        assertEquals("Unsent fixture", model.state.value.draft)
        assertEquals(2L, model.state.value.anchor?.id); assertEquals(7, model.state.value.anchor?.offset)
        assertTrue(api.pingCalls >= 3); assertEquals(0, api.sends)
    }

    private class Conversations : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false),
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())))
        var historyGate: CompletableDeferred<Unit>? = null
        var sends = 0; var pingCalls = 0
        override suspend fun context(roomId: Long) = ConversationContext(
            RoomInfo(42, "lounge", "Lounge", "", "public", 0, 0, 7, "member", false, "none", 1, 2, null),
            session.value.chatPolicy!!)
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            historyGate?.await()
            val rows = (2L..3).filter { beforeId == null || it < beforeId }.takeLast(limit)
                .map { RoomMessage(it, 42, 8, "Peer", "text", "Fixture", false, "2026-10-20T12:00:00Z") }
            return MessagePage(rows, rows.firstOrNull()?.id, false)
        }
        override suspend fun pings(roomId: Long): List<RoomPing> {
            pingCalls++
            return listOf(RoomPing(9, 42, MessagePerson(8, "Peer"), MessagePerson(7, "Viewer"), "Private fixture", "2026-10-20T12:00:00Z"))
        }
        override suspend fun send(roomId: Long, body: String): SendResult { sends++; error("No POST expected") }
        override suspend fun read(roomId: Long, messageId: Long) = messageId
    }
    private class Events : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(generation = 1, phase = RealtimePhase.LIVE,
            selectedRoomId = 42, presencePhase = PresencePhase.ACTIVE))
        override val events = MutableSharedFlow<RealtimeEvent>()
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) = Unit
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = Unit
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }
}
