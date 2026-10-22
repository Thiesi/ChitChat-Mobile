package io.github.thiesi.chitchat.feature.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.feature.account.TestChitChatApp as ChitChatApp
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel

class AuthJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.org") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    })

    private fun content() { compose.setContent { Box(Modifier.size(320.dp, 480.dp)) { ChitChatApp(settings, auth) } } }
    private fun enterCredentials() {
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed().performClick()
    }

    @Test fun authenticatedRoomsNavigationReturnsToTheExistingAccountHome() {
        content(); enterCredentials()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.auth_preview_limit))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rooms").performScrollTo().performClick()
        compose.onNodeWithText("No rooms are available to this account.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
    }

    @Test fun logoutWhileViewingRoomsRetiresTheProtectedDestination() {
        content(); enterCredentials()
        compose.onNodeWithText("Rooms").performScrollTo().performClick()
        compose.onNodeWithText("No rooms are available to this account.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { auth.signOut() }
        compose.onNodeWithText("Back to sign-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").assertDoesNotExist()
        assertEquals(io.github.thiesi.chitchat.core.session.SessionPhase.SIGNED_OUT, sessions.state.value.phase)
        compose.onNodeWithText("Back to sign-in").performClick()
        compose.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed()
    }

    @Test fun roomConversationNavigationAndLogoutClearMessagesAndDraft() {
        val room = RoomInfo(42, "fixture", "Fixture Lounge", "", "public", 0, 0, 7, "member", false, "none")
        val roomOperations = object : RoomOperations {
            override val session = sessions.state
            override suspend fun list() = listOf(room)
            override suspend fun detail(roomId: Long) = room
            override suspend fun join(roomId: Long): RoomInfo = error("Unconfigured fixture")
            override suspend fun leave(roomId: Long) { error("Unconfigured fixture") }
            override suspend fun create(input: CreateRoom): RoomInfo = error("Unconfigured fixture")
        }
        val conversationOperations = object : ConversationOperations {
            override val session = sessions.state
            override suspend fun context(roomId: Long) = ConversationContext(room, ChatPolicy(emptySet(), null, DisplayPreferences()))
            override suspend fun history(roomId: Long, beforeId: Long?, limit: Int) = MessagePage(listOf(
                RoomMessage(80, 42, 8, "Bob", "text", "Navigation private fixture", false, "2026-07-17T08:00:00Z")), 80, false)
            override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
            override suspend fun send(roomId: Long, body: String, replyId: Long?): SendResult = error("Unconfigured fixture")
            override suspend fun read(roomId: Long, messageId: Long) = messageId
        }
        val roomModel = RoomsViewModel(roomOperations)
        val conversationModel = ConversationViewModel(conversationOperations)
        compose.setContent { Box(Modifier.size(320.dp, 480.dp)) {
            ChitChatApp(settings, auth, roomModel = roomModel, conversationModel = conversationModel)
        } }
        enterCredentials()
        compose.waitUntil(5_000) {
            sessions.state.value.phase == io.github.thiesi.chitchat.core.session.SessionPhase.AUTHENTICATED && !sessions.state.value.busy
        }
        compose.onNodeWithText("Rooms").performScrollTo().performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Fixture Lounge").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("Fixture Lounge").performClick()
        compose.onNodeWithText("Open conversation").performScrollTo().performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Navigation private fixture").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("conversation_composer").performTextInput("Private unsent draft")
        compose.runOnIdle { auth.signOut() }
        compose.onNodeWithText("Back to sign-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Navigation private fixture").assertDoesNotExist()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("", conversationModel.state.value.draft)
            assertEquals(emptyList<RoomMessage>(), conversationModel.state.value.messages)
        }
    }

    @Test fun failedLoginClearsPasswordPreservesUsernameAndAllowsCorrection() {
        sessions.failNextLogin = true
        content()
        enterCredentials()
        compose.onNodeWithText("The username or password is incorrect.").assertIsDisplayed()
        compose.onNodeWithText("Username").assertTextContains("Alice")
        compose.onNodeWithText("Password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        // Real authentication suspends outside Compose's idleness tracking. Hold the corrected
        // response to prove receipt and absence of premature authenticated UI, then await its result.
        val completion = CompletableDeferred<Unit>()
        compose.runOnIdle { sessions.nextLoginCompletion = completion }
        compose.onNodeWithText("Password").performScrollTo().performTextInput("corrected-fixture-password")
        try {
            compose.onNodeWithText("Sign in").performScrollTo().performClick()
            compose.waitUntil(5_000) { sessions.logins == 2 }
            compose.onNodeWithText("Signed in as Alice").assertDoesNotExist()
            compose.onNodeWithText("Password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        } finally { completion.complete(Unit) }
        compose.waitUntil(5_000) { runCatching {
            compose.onNodeWithText("Signed in as Alice").assertExists()
        }.isSuccess }
        compose.onNodeWithText("Signed in as Alice").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, sessions.logins) }
        compose.onNodeWithText("Change server").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Forget server").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.onNodeWithText("Signed in as Alice").assertDoesNotExist()
        compose.onNodeWithText("Back to sign-in").performScrollTo().performClick()
        compose.onNodeWithText("Username").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun pendingMfaNeverShowsAuthenticatedContentBeforeRecovery() {
        sessions.requireMfa = true
        content()
        enterCredentials()
        compose.onNodeWithText("Finish sign-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Signed in as Alice").assertDoesNotExist()
        compose.onNodeWithText("Recovery code").performScrollTo().performTextInput("UNUSED-FIXTURE-CODE")
        compose.onNodeWithText("Use recovery code").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Signed in as Alice").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sessions.recoveries) }
    }

    @Test fun accountNavigationIsAuthenticatedAndRetiresBackStackOnLogout() {
        content()
        compose.onNodeWithText("Account").assertDoesNotExist()
        enterCredentials()
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Your personal data").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Signed in as Alice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Account").performClick()
        compose.runOnIdle { auth.signOut() }
        compose.onNodeWithText("Your personal data").assertDoesNotExist()
        compose.onNodeWithText("Back to sign-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Account").assertDoesNotExist()
    }

    @Test fun restoredFormDoesNotPutPasswordIntoSavedState() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Box(Modifier.size(320.dp, 480.dp)) { ChitChatApp(settings, auth) } }
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("unsaved-fixture-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Username").assertTextContains("Alice")
        compose.onNodeWithText("Password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(0, sessions.logins) }
    }

    private fun registrationForm() {
        sessions.registrationEnabled = true
        content()
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("Join this server").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
    }

    @Test fun registrationValidatesConfirmationBeforeSendingAndKeepsFormReachable() {
        registrationForm()
        compose.onNodeWithText("Confirm password").performScrollTo().performTextInput("different-password")
        compose.onNodeWithText("Create account").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("The passwords do not match.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, sessions.registrations) }
    }

    @Test fun registrationFailureClearsSecretsAndRequiresFreshCheck() {
        sessions.failNextRegistration = true
        registrationForm()
        compose.onNodeWithText("Confirm password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("That username is already registered. Choose another, or sign in.").assertIsDisplayed()
        compose.onNodeWithText("Username").assertTextContains("Alice")
        for (label in listOf("Password", "Confirm password")) compose.onNodeWithText(label)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Create account").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Refresh anti-bot check").performScrollTo().performClick()
        compose.onNodeWithText("Anti-bot check ready.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sessions.registrations) }
    }

    @Test fun birthDatePickerIsOptionalAndCancellable() {
        registrationForm()
        compose.onNodeWithText("Add birth date (optional)").performScrollTo().performClick()
        compose.onNodeWithText("Use this date").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Use this date").assertDoesNotExist()
        compose.onNodeWithText("Add birth date (optional)").performScrollTo().assertIsDisplayed()
    }

    @Test fun registrationSavedStateDoesNotRetainEitherPassword() {
        sessions.registrationEnabled = true
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Box(Modifier.size(320.dp, 480.dp)) { ChitChatApp(settings, auth) } }
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        for (label in listOf("Password", "Confirm password")) compose.onNodeWithText(label).performScrollTo().performTextInput("fixture-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Username").assertTextContains("Alice")
        for (label in listOf("Password", "Confirm password")) compose.onNodeWithText(label)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(0, sessions.registrations) }
    }

    @Test fun restorationExplainsPendingClosureAndRequiresMfaBeforeConfirmation() {
        sessions.requireMfa = true
        content()
        compose.onNodeWithText("Create account").assertDoesNotExist()
        compose.onNodeWithText("Restore account").performScrollTo().performClick()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithText("Restore and sign in").performScrollTo().performClick()
        compose.onNodeWithText("Finish account restoration").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The server confirmed that your account was restored.").assertDoesNotExist()
        compose.onNodeWithText("Recovery code").performScrollTo().performTextInput("UNUSED-FIXTURE-CODE")
        compose.onNodeWithText("Use recovery code").performScrollTo().performClick()
        compose.onNodeWithText("The server confirmed that your account was restored.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sessions.restores); assertEquals(1, sessions.recoveries) }
    }
}
