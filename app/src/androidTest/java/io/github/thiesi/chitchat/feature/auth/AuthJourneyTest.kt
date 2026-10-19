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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.app.ChitChatApp
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

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

    @Test fun failedLoginClearsPasswordPreservesUsernameAndAllowsCorrection() {
        sessions.failNextLogin = true
        content()
        enterCredentials()
        compose.onNodeWithText("The username or password is incorrect.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Username").assertTextContains("Alice")
        compose.onNodeWithText("Password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Password").performScrollTo().performTextInput("corrected-fixture-password")
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
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
}
