package io.github.thiesi.chitchat.feature.auth

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.policies.PolicyDiscovery
import io.github.thiesi.chitchat.core.policies.PolicyLink
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #275 product fix: a form's primary action stays visible above the keyboard (PL and TL design).
 * - Sign in is pinned outside the scroller and displayed above the IME with no scrolling.
 * - Create is part of one block with the consent line: absent while the IME is up, and back with the line fully
 *   visible once the last field's Done hides the keyboard.
 * - On a very short window the block sits in flow; a tap on Create while the line is off screen brings it into view,
 *   announces why and doesn't submit.
 */
class AuthPinnedSubmitJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions().apply { registrationEnabled = true }
    private val auth = AuthViewModel(sessions)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    @Volatile private var imeBottom = 0
    private val rules = PolicyDiscovery(PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00"), null, null)

    private fun install(width: Int = 320, height: Int = 640) {
        sessions.nextConnect = { it.copy(installationName = "The Common Room", public = it.public!!.copy(policies = rules)) }
        compose.runOnUiThread { compose.activity.setContent {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeBottom = bottom }
            Box(Modifier.size(width.dp, height.dp)) { TestChitChatApp(settings, auth) }
        } }
        compose.waitUntil(5_000) { !settings.state.value.loading }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun root() = compose.onRoot().fetchSemanticsNode().boundsInWindow

    @Test fun signInStaysDisplayedAboveTheKeyboardWithoutScrolling() {
        install()
        compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
        compose.onNodeWithText("Username").performScrollTo().performTextInput("Alice")
        compose.tapNativeInput("sign_in_password", imeBottom = { imeBottom })
        compose.onNodeWithTag("sign_in_password").performTextInput("fixture-password")
        // No performScrollTo: the button is pinned outside the scroller.
        val button = compose.onNodeWithTag("sign_in_submit").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val visibleBottom = root().bottom - imeBottom
        assertTrue("Sign in above the keyboard: button=$button, root=${root()}, ime=$imeBottom", button.bottom <= visibleBottom + 0.5f)
        compose.onNodeWithTag("sign_in_submit").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sessions.logins == 1 }
    }

    @Test fun createIsAbsentWhileTypingAndDoneBringsItBackWithTheConsentLineVisible() {
        install()
        compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
        compose.onNodeWithTag("registration_submit").assertIsDisplayed() // Keyboard hidden: the pinned block shows.
        compose.onNodeWithTag("registration_rules_consent").assertIsDisplayed()
        compose.onNodeWithTag("registration_username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithTag("registration_password").performScrollTo().performTextInput("fixture-password")
        compose.tapNativeInput("registration_confirmation", imeBottom = { imeBottom })
        compose.onNodeWithTag("registration_confirmation").performTextInput("fixture-password")
        if (imeBottom > 0) {
            assertTrue("No Create while the keyboard is up", !exists("registration_submit"))
            assertTrue("No consent block while the keyboard is up", !exists("registration_rules_consent"))
        }
        compose.onNodeWithTag("registration_confirmation").performImeAction() // Done hides the keyboard.
        compose.waitUntil(5_000) { exists("registration_submit") }
        val line = compose.onNodeWithTag("registration_rules_consent").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val visibleBottom = root().bottom - imeBottom
        assertTrue("The consent line is fully visible with Create: line=$line, root=${root()}", line.top >= root().top && line.bottom <= visibleBottom + 0.5f)
        compose.onNodeWithTag("registration_submit").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { sessions.registrations == 1 }
    }

    @Test fun onAVeryShortWindowATapWithTheLineOffScreenBringsItIntoViewAnnouncesAndDoesNotSubmit() {
        install(height = 260)
        compose.onNodeWithTag("onboarding_create").performScrollTo().performClick()
        compose.onNodeWithTag("registration_username").performScrollTo().performTextInput("Alice")
        compose.onNodeWithTag("registration_password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithTag("registration_confirmation").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithTag("registration_confirmation").performImeAction()
        compose.waitUntil(5_000) { exists("registration_submit") }
        // In flow here: the block is part of the scroller, not the pinned area.
        assertTrue("The block falls back to the scroller", !exists("pinned_action"))
        compose.onNodeWithTag("registration_username").performScrollTo() // The block is now below the viewport.
        compose.onNodeWithTag("registration_submit").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(5_000) { exists("registration_review_announcement") }
        val announcement = compose.onNodeWithTag("registration_review_announcement").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, announcement.config[SemanticsProperties.LiveRegion])
        assertEquals(listOf(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.registration_review_agreement)),
            announcement.config[SemanticsProperties.ContentDescription])
        compose.runOnIdle { assertEquals("Not submitted", 0, sessions.registrations) }
        compose.onNodeWithTag("registration_rules_consent").assertIsDisplayed()
        compose.onNodeWithTag("registration_submit").performClick() // Now the line is on screen: it submits.
        compose.waitUntil(5_000) { sessions.registrations == 1 }
    }
}
