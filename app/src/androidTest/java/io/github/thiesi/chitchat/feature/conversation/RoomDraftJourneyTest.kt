package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.testing.tapNativeInput
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual root navigation and native IME; operations are fixtures, never live server writes. */
class RoomDraftJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val api = Rooms(sessions)
    private val directory = RoomsViewModel(api)
    private val typing = NativeTypingHarness(sessions.state)
    private val model = ConversationViewModel(api, typing = typing.controller)
    @Volatile private var ime = 0
    @Before fun configuration() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun stop() {
        Espresso.closeSoftKeyboard()
        compose.runOnIdle { model.cancel(); directory.cancel(); typing.close() }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun root() {
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server =
                (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        })
        compose.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { ime = bottom }
            Box(Modifier.size(320.dp, 640.dp)) {
                TestChitChatApp(settings, auth, roomModel = directory, conversationModel = model)
            }
        }
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Viewer")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        dismissIme()
        compose.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.AUTHENTICATED }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(shareTyping = true,
            chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences())) }
        compose.onNodeWithText("Rooms").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
    }
    private fun dismissIme() {
        Espresso.closeSoftKeyboard()
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true")
            compose.waitUntil(5_000) { ime == 0 }
    }
    private fun open(id: Long) {
        compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Room $id"))
        compose.onNodeWithText("Room $id").assertIsDisplayed().performClick()
        compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        settled(id)
    }
    private fun settled(id: Long) { compose.waitUntil(5_000) {
        model.state.value.roomId == id && model.state.value.loaded && !model.state.value.busy
    } }
    private fun back() {
        dismissIme()
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rooms_directory").assertIsDisplayed()
        compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
    }
    private fun drafts() {
        compose.onNodeWithTag("room_directory_drafts").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("room_drafts_dialog").assertIsDisplayed()
    }
    private fun reopen(id: Long) {
        compose.onNodeWithTag("room_draft_open:$id").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        settled(id)
    }
    private fun input() = compose.tapNativeInput("conversation_composer", { ime })
    private fun draft(value: String) = compose.onNodeWithTag("conversation_composer").assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    private fun details() = compose.onNodeWithContentDescription(text(R.string.conversation_details)).assertIsDisplayed().performClick()
    private fun dialogButton(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))

    @Test fun rootRoomSwitchAndManagerRestoreBothDraftsWithoutSending() {
        root(); open(42); input().performTextInput("Room A fixture")
        back(); open(43); input().performTextInput("Room B fixture")
        back(); drafts(); reopen(42)
        input(); draft("Room A fixture").assertIsDisplayed()
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled()
        typing.capture("room-draft-restored-keyboard.png")
        compose.runOnIdle { assertEquals("Room B fixture", model.drafts.value.getValue(43).body); assertEquals(0, api.sends.get()) }
    }
    @Test fun unknownDeliverySurvivesNavigationUntilExplicitCheckAndDuplicateChoice() {
        root(); open(42); input().performTextInput("Unconfirmed fixture")
        compose.runOnIdle { api.failure = ApiFailure(FailureKind.TIMEOUT) }
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { model.state.value.uncertainSend && !model.state.value.busy }
        back(); open(43); back(); drafts(); reopen(42)
        draft("Unconfirmed fixture"); compose.onNodeWithTag("conversation_send").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(model.state.value.reconciledAfterUncertain); assertEquals(1, api.sends.get()) }
        details(); dialogButton(R.string.conversation_allow_resend).assertDoesNotExist()
        dialogButton(R.string.conversation_refresh).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.reconciledAfterUncertain }
        details(); dialogButton(R.string.conversation_allow_resend).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, api.sends.get()); api.failure = null }
        input(); draft("Unconfirmed fixture")
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { api.sends.get() == 2 && !model.state.value.busy && model.state.value.draft.isEmpty() }
        compose.runOnIdle {
            assertTrue(model.drafts.value.isEmpty())
            val acknowledged = model.state.value.messages.single { it.id == 4L }
            assertEquals(7L, acknowledged.senderId); assertEquals("Unconfirmed fixture", acknowledged.body)
        }
    }
    @Test fun capacityHasReachableExplicitDiscardAndNeverEvictsAnotherRoom() {
        root()
        // Domain fixture seeding, not a claim of 100 manual navigation journeys.
        compose.runOnIdle { for (id in 42L..141) { model.open(id); model.updateDraft("Draft $id fixture") } }
        open(142); compose.onNodeWithTag("conversation_composer").assertIsNotEnabled()
        details(); compose.onNode(hasText(text(R.string.room_draft_limit)) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Saved room drafts (100)") and hasAnyAncestor(isDialog())).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("room_draft_discard:42").performScrollTo().assertIsDisplayed()
        typing.capture("room-draft-capacity-manager.png")
        compose.onNodeWithTag("room_draft_discard:42").assertIsEnabled().performClick()
        dialogButton(R.string.rooms_close).assertIsDisplayed().performClick()
        input().performTextInput("New room fixture")
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(100, model.drafts.value.size); assertFalse(42L in model.drafts.value)
            assertEquals("Draft 43 fixture", model.drafts.value.getValue(43).body); assertEquals(0, api.sends.get())
        }
    }
    @Test fun managerSuppressesReadAndTypingAndSignOutClearsItsScopedContent() {
        root(); open(42); compose.waitUntil(5_000) { api.read.get() == 3L }
        input().performTextInput("Private draft fixture")
        compose.waitUntil(5_000) { typing.requests.get() == 1 }
        compose.runOnIdle { typing.hint(42) }
        compose.waitUntil(5_000) { typing.controller.state.value.people.isNotEmpty() }
        dismissIme(); details()
        compose.onNode(hasText("Saved room drafts (1)") and hasAnyAncestor(isDialog())).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { typing.controller.state.value.people.isEmpty() }
        compose.runOnIdle { api.extra = true; model.refresh() }
        compose.waitUntil(5_000) { model.state.value.messages.lastOrNull()?.id == 4L && !model.state.value.busy }
        compose.runOnIdle {
            assertEquals(3L, api.read.get()); assertEquals(1, typing.requests.get()); assertEquals(0, api.sends.get())
            sessions.state.value = SessionState(sessions.state.value.server, sessions.state.value.generation + 1, SessionPhase.SIGNED_OUT)
        }
        compose.waitUntil(5_000) { model.drafts.value.isEmpty() }
        compose.onNodeWithTag("room_drafts_dialog").assertDoesNotExist()
        compose.onNodeWithText("Private draft fixture").assertDoesNotExist()
    }

    private class Rooms(sessions: TestSessions) : RoomOperations, ConversationOperations {
        override val session = sessions.state
        val sends = AtomicInteger(); val read = AtomicLong()
        var failure: ApiFailure? = null; var extra = false
        private val sent = mutableMapOf<Long, RoomMessage>()
        private fun info(id: Long) = RoomInfo(id, "room$id", "Room $id", "", "public", 0, 0, 9, "member", false, "none")
        override suspend fun list() = listOf(info(42), info(43), info(142))
        override suspend fun detail(roomId: Long) = info(roomId)
        override suspend fun join(roomId: Long): RoomInfo = error("No fixture membership write expected")
        override suspend fun leave(roomId: Long) = error("No fixture membership write expected")
        override suspend fun create(input: CreateRoom): RoomInfo = error("No fixture room creation expected")
        override suspend fun context(roomId: Long) = ConversationContext(info(roomId),
            session.value.chatPolicy ?: ChatPolicy(emptySet(), null, DisplayPreferences()))
        private fun row(id: Long, room: Long, body: String = "Fixture $id") = RoomMessage(id, room, 8, "Peer", "text", body, false, "2026-10-20T12:00:00Z")
        override suspend fun history(roomId: Long, beforeId: Long?, limit: Int): MessagePage {
            val available = (2L..if (extra) 4 else 3).map { row(it, roomId) } + listOfNotNull(sent[roomId])
            val rows = available.filter { beforeId == null || it.id < beforeId }.sortedBy { it.id }.takeLast(limit)
            return MessagePage(rows, rows.firstOrNull()?.id, false)
        }
        override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
        override suspend fun send(roomId: Long, body: String): SendResult {
            sends.incrementAndGet(); failure?.let { throw it }
            val acknowledged = RoomMessage(4, roomId, 7, "Viewer", "text", body, false, "2026-10-20T12:00:00Z")
            sent[roomId] = acknowledged
            return SendResult.Message(acknowledged)
        }
        override suspend fun read(roomId: Long, messageId: Long): Long { read.set(messageId); return messageId }
    }
}
