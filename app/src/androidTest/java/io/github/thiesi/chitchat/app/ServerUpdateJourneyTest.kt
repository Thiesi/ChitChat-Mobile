package io.github.thiesi.chitchat.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.openTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** server-update.md P-180–P-182 in the app: the setup notice and the revalidation state. Typed fixtures. */
class ServerUpdateJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = NativeChatFixtures(sessions)
    private val auth = AuthViewModel(sessions)
    private val directory = RoomsViewModel(object : RoomOperations {
        override val session = sessions.state
        private val rows = listOf(RoomInfo(43, "studio", "Room 43", "A shared room", "private", 0, 0, 8, "member", false, "none", 12, 88))
        override suspend fun list() = rows
        override suspend fun detail(roomId: Long) = rows.single { it.id == roomId }
        override suspend fun join(roomId: Long): RoomInfo = error("No membership mutation")
        override suspend fun leave(roomId: Long) { error("No membership mutation") }
        override suspend fun create(input: CreateRoom): RoomInfo = error("No room creation")
    })
    private val room = ConversationViewModel(api.rooms)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @After fun close() { compose.runOnIdle { room.cancel(); directory.cancel() } }
    private fun install() = compose.runOnUiThread { compose.activity.setContent {
        TestChitChatApp(settings, auth, roomModel = directory, conversationModel = room)
    } }

    @Test fun atSetupAnOutdatedServerOffersOnlyTheNoteAndAnotherServer() {
        install()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(updateRequired = true, reportedVersion = "3.9.0") }
        compose.onNodeWithTag("server_update_title").assertTextEquals(text(R.string.server_update_title))
        compose.onNodeWithText(text(R.string.server_update_details, "3.9.0", "3.10.0")).assertExists()
        // P-181: no sign-in, account or change-server actions while the version is too old.
        compose.onNodeWithText("Username").assertDoesNotExist(); compose.onNodeWithText("Sign in").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.change_server)).assertDoesNotExist()
        compose.onNodeWithTag("server_update_copy").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.server_update_note_copied)).assertExists()
        compose.onNodeWithTag("server_update_other").performScrollTo().performClick()
        compose.onNodeWithText("Server address").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun onRevalidationTheShellIsReplacedAndPendingWorkSurvivesShowingAndClearingIt() {
        install()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy) }
        compose.openTab("rooms").assertIsDisplayed()
        compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
        compose.onNodeWithTag("rooms_directory").performScrollToNode(hasText("Room 43"))
        compose.onNodeWithText("Room 43").performClick()
        compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.waitUntil(5_000) { room.state.value.roomId == 43L && room.state.value.loaded && !room.state.value.busy }
        // An unknown delivery with its draft kept.
        compose.runOnIdle { api.rooms.sendFailure = ApiFailure(FailureKind.TIMEOUT); room.updateDraft("Kept through the update"); room.send() }
        compose.waitUntil(5_000) { room.state.value.uncertainSend && !room.state.value.busy }
        val sends = api.rooms.sends

        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(updateRequired = true, reportedVersion = null,
            error = ApiFailure(FailureKind.NETWORK)) }
        compose.onNodeWithTag("server_update_screen").assertExists()
        // The displayed title may break at the host's dots; TalkBack and tests get the raw host (no U+200B).
        compose.onNodeWithTag("server_update_title").assertTextEquals(text(R.string.server_update_session_title, "chat.example.test"))
        compose.onNodeWithText(text(R.string.server_update_details_unknown, "3.10.0")).assertExists()
        compose.onNodeWithTag("nav_tab_rooms").assertDoesNotExist(); compose.onNodeWithTag("conversation_composer").assertDoesNotExist()
        compose.onNodeWithTag("server_update_check").performScrollTo().performClick() // The explicit refresh.
        compose.waitUntil(5_000) { sessions.state.value.error == null }

        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(updateRequired = false) }
        compose.onNodeWithTag("conversation_composer").assertExists()
        compose.runOnIdle {
            assertTrue("The unknown-send guard survives", room.state.value.uncertainSend)
            assertEquals("The draft survives", "Kept through the update", room.drafts.value[43]?.body)
            assertEquals("Nothing was resent", sends, api.rooms.sends)
        }
    }

    @Test fun signOutFromTheUpdateStateIsTheOrdinarySignOut() {
        install()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(updateRequired = true) }
        compose.onNodeWithTag("server_update_sign_out").performScrollTo().performClick()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.SIGNED_OUT }
        compose.onNodeWithTag("server_update_screen").assertDoesNotExist()
    }
}
