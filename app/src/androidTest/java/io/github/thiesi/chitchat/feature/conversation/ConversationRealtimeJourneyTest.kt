package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.espresso.Espresso.closeSoftKeyboard
import io.github.thiesi.chitchat.app.RealtimeConsumers
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.realtime.*
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import io.github.thiesi.chitchat.testing.tapNativeInput

class ConversationRealtimeJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val api = Conversations()
    private val realtime = Connection()
    private val model = ConversationViewModel(api, realtime)
    private val directory = Directory(api.session)
    private val rooms = RoomsViewModel(directory, realtime)
    @Volatile private var imeBottom = 0
    @After fun stop() { closeSoftKeyboard(); model.cancel(); rooms.cancel() }
    private fun content() {
        compose.setContent { MaterialTheme {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeBottom = bottom }
            RealtimeConsumers(realtime, api.session.collectAsState().value, rooms, model)
            Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(model, 42, {}) }
        } }
        compose.runOnIdle { rooms.foreground() }
        settled()
    }
    private fun settled() = compose.waitUntil(5_000) {
        model.state.value.loaded && !model.state.value.busy && !model.state.value.syncing && realtime.events.subscriptionCount.value > 0
    }
    private fun event(roomId: Long = 42) {
        assertTrue(realtime.events.tryEmit(RealtimeEvent(77, "room_message", roomId, 8,
            buildJsonObject { put("message", Json.encodeToJsonElement(message(4, "Transport preview", roomId))) }, Instant.EPOCH)))
    }
    private fun enterRoom() {
        val before = realtime.entries
        val details = compose.onNodeWithContentDescription("Room details").assertIsDisplayed()
        val bounds = details.fetchSemanticsNode().boundsInWindow
        details.performClick()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        try {
            compose.onNode(hasText(context.getString(io.github.thiesi.chitchat.R.string.presence_enter)) and hasAnyAncestor(isDialog()))
                .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        } catch (failure: AssertionError) {
            val input = compose.onNodeWithTag("conversation_composer").fetchSemanticsNode()
            throw AssertionError("Reentry fixture: IME=$imeBottom, focused=${input.config[SemanticsProperties.Focused]}, detailsBefore=$bounds, detailsAfter=${details.fetchSemanticsNode().boundsInWindow}, dialogs=${compose.onAllNodes(isDialog()).fetchSemanticsNodes().size}, phase=${realtime.state.value.presencePhase}, entries=${realtime.entries}, loaded=${model.state.value.loaded}, busy=${model.state.value.busy}, stale=${model.state.value.stale}", failure)
        }
        compose.waitUntil(5_000) { realtime.entries == before + 1 }
    }

    private fun enterDraft(value: String) {
        compose.tapNativeInput("conversation_composer", { imeBottom }).performTextInput(value)
    }

    @Test fun incomingHintUsesCanonicalHistoryAndPreservesTheUnsentComposer() {
        content()
        enterDraft("Keep unsent draft")
        compose.runOnIdle { api.rows += message(4, "Canonical incoming"); event() }
        compose.waitUntil(5_000) { model.state.value.messages.lastOrNull()?.id == 4L && !model.state.value.syncing }
        compose.onNodeWithTag("conversation_composer").assertTextContains("Keep unsent draft")
        compose.onNodeWithText("Transport preview").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("Canonical incoming", model.state.value.messages.last().body)
            assertEquals(0, api.sends); assertEquals(0, api.reads)
        }
        val windows = api.windows; val lists = directory.lists
        compose.runOnIdle { event(43) }
        compose.waitUntil(5_000) { directory.lists > lists }
        compose.runOnIdle { assertEquals(windows, api.windows) }
    }

    @Test fun restoredRouteAndExpiredPresenceRequireExplicitAcknowledgedReentry() {
        content()
        enterDraft("Retained draft")
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, realtime.entries); assertEquals(0, api.reads) }
        enterRoom()
        compose.runOnIdle { assertEquals(1, realtime.entries); assertEquals(0, api.reads) }
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { realtime.state.value = realtime.state.value.copy(presencePhase = PresencePhase.ACTIVE) }
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsEnabled()
        closeSoftKeyboard()
        compose.onNodeWithTag("conversation_timeline").performScrollToIndex(2)
        compose.waitUntil(5_000) { api.reads == 1 }
        compose.runOnIdle { realtime.state.value = realtime.state.value.copy(presencePhase = PresencePhase.EXPIRED) }
        compose.onNodeWithText("Send").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("conversation_composer").assertTextContains("Retained draft")
        enterRoom()
        compose.runOnIdle { assertEquals(2, realtime.entries); assertEquals(0, api.sends) }
    }

    @Test fun tallIncomingCanonicalMessageFollowsToMeasuredBottomBeforeReadAcknowledgment() {
        content()
        enterRoom()
        compose.runOnIdle { realtime.state.value = realtime.state.value.copy(presencePhase = PresencePhase.ACTIVE) }
        compose.waitUntil(5_000) { api.lastRead == 3L }
        compose.runOnIdle {
            api.rows += message(4, (1..30).joinToString("\n") { "Tall incoming line $it" }); event()
        }
        compose.waitUntil(5_000) { api.lastRead == 4L }
        compose.runOnIdle { assertTrue(model.state.value.atLatest); assertEquals(0, api.sends) }
    }

    @Test fun warningStayIsExplicitAndStaleAggregateUsersCloseTheDialog() {
        realtime.state.value = realtime.state.value.copy(selectedRoomId = 42, presencePhase = PresencePhase.WARNING,
            presence = PresenceStatus(42, 110, 10, false, 60), presenceReceivedAtMillis = System.currentTimeMillis(),
            users = listOf(PresenceUser(7, "Viewer", false, 0, 2), PresenceUser(8, "Visitor", true, 3, 1)), presenceStale = false)
        compose.setContent { MaterialTheme {
            RoomPresencePanel(realtime.state.collectAsState().value, true, true) { realtime.reenter(42) }
        } }
        compose.onNodeWithText("seconds remaining.", substring = true).assertExists()
        compose.onNodeWithText("2 people online").performClick()
        compose.onNodeWithText("Visitor · guest").assertExists()
        compose.runOnIdle { realtime.state.value = realtime.state.value.copy(presenceStale = true) }
        compose.onNodeWithText("Visitor · guest").assertDoesNotExist()
        compose.onNodeWithText("The online list is updating or unavailable.").assertExists()
        compose.onNodeWithText("Stay in room").performClick()
        compose.runOnIdle { assertEquals(1, realtime.entries) }
    }

    @Test fun restartedConsumerScopeReconcilesEvenWhenItsEventWasMissed() {
        lateinit var owner: Owner
        compose.runOnIdle {
            owner = Owner(); owner.registry.currentState = Lifecycle.State.STARTED
            model.foreground(); model.open(42); rooms.foreground()
        }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            RealtimeConsumers(realtime, api.session.collectAsState().value, rooms, model)
        } }
        settled()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { realtime.events.subscriptionCount.value == 0 }
        compose.runOnIdle { api.rows += message(4, "Missed while stopped"); event() }
        compose.runOnIdle { assertEquals(3L, model.state.value.messages.last().id); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(5_000) { model.state.value.messages.lastOrNull()?.id == 4L }
        compose.runOnIdle { assertEquals(0, api.reads); assertEquals(0, realtime.entries) }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Conversations : ConversationOperations {
        override val session = MutableStateFlow(SessionState(generation = 1, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false)))
        var rows = listOf(message(2, "Earlier"), message(3, "Newest"))
        var sends = 0; var reads = 0; var windows = 0; var lastRead = 0L
        override suspend fun context(roomId: Long) = ConversationContext(room, ChatPolicy(emptySet(), null, DisplayPreferences()))
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            val page = rows.filter { beforeId == null || it.id < beforeId }.takeLast(limit)
            return MessagePage(page, page.firstOrNull()?.id, page.size == limit)
        }
        override suspend fun window(roomId: Long, lowerInclusive: Long, upperInclusive: Long?): WindowSnapshot {
            windows++; return super.window(roomId, lowerInclusive, upperInclusive)
        }
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun read(roomId: Long, messageId: Long): Long { reads++; lastRead = messageId; return messageId }
        override suspend fun send(roomId: Long, body: String): SendResult { sends++; error("Unexpected fixture send") }
    }
    private class Directory(override val session: MutableStateFlow<SessionState>) : RoomOperations {
        var lists = 0
        override suspend fun list(): List<RoomInfo> { lists++; return listOf(room) }
        override suspend fun detail(roomId: Long) = room
        override suspend fun join(roomId: Long): RoomInfo = error("Unexpected fixture join")
        override suspend fun leave(roomId: Long): Unit = error("Unexpected fixture leave")
        override suspend fun create(input: CreateRoom): RoomInfo = error("Unexpected fixture create")
    }
    private class Connection : RealtimeController {
        override val state = MutableStateFlow(RealtimeState(phase = RealtimePhase.LIVE, generation = 1))
        override val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
        var entries = 0
        override fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean) {
            if (interacted) entries++
            state.value = state.value.copy(selectedRoomId = roomId, presencePhase = when {
                !eligible -> PresencePhase.NONE
                interacted -> PresencePhase.CONNECTING
                else -> state.value.presencePhase
            })
        }
        override fun activity(roomId: Long) = Unit
        override fun reenter(roomId: Long) = selectRoom(roomId, true, true)
        override fun retry() = Unit
        override fun dismissBroadcast(id: Long) = Unit
    }
    companion object {
        private val room = RoomInfo(42, "lounge", "Lounge", "", "public", 0, 120, 7, "member", false, "none", 2, 1)
        private fun message(id: Long, body: String, roomId: Long = 42) = RoomMessage(id, roomId, 8, "Other", "text", body,
            false, "2026-10-20T12:00:00Z")
    }
}
