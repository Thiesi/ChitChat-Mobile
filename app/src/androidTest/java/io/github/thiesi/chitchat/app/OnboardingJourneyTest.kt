package io.github.thiesi.chitchat.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** onboarding-restyle.md O2a (P-50–P-52, P-55): Welcome, step 2 with its failure path, Server found and Back. */
class OnboardingJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private fun install() = compose.runOnUiThread { compose.activity.setContent { TestChitChatApp(settings, auth) } }
    private fun toAddress() {
        install()
        compose.waitUntil(5_000) { !settings.state.value.loading }
        compose.onNodeWithTag("onboarding_welcome_start").performScrollTo().performClick()
    }
    private fun submit(address: String) {
        compose.onNodeWithTag("onboarding_server_input").performTextReplacement(address)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("onboarding_continue").performScrollTo().performClick()
    }
    private fun addressIs(value: String) = compose.onNodeWithTag("onboarding_server_input")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    @Test fun welcomeLeadsToTheAddressBackReturnsAndRecreationKeepsTheStep() {
        toAddress(); compose.onNodeWithTag("onboarding_server_input").assertExists()
        Espresso.pressBack(); compose.onNodeWithTag("onboarding_welcome_start").assertExists()
        compose.onNodeWithTag("onboarding_welcome_start").performClick()
        compose.activityRule.scenario.recreate(); compose.activityRule.scenario.onActivity { it.setContent { TestChitChatApp(settings, auth) } }
        compose.onNodeWithTag("onboarding_server_input").assertExists() // P-50: the same step after recreation.
    }

    @Test fun theFeatureDisclosureAndPrivacyLineAreReachableBeforeSignIn() {
        toAddress(); Espresso.pressBack()
        compose.onNodeWithTag("onboarding_preview").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.preview_limit)).performScrollTo().assertExists()
        Espresso.pressBack()
        compose.onNodeWithTag("onboarding_welcome_start").performClick(); submit("chat.example.test")
        compose.onNodeWithText(text(R.string.onboarding_privacy_line)).performScrollTo().assertExists() // P-55
    }

    /** P-51: a failed bootstrap returns to step 2 with the address kept and one notice; an incompatible host never reaches step 3. */
    @Test fun aFailedBootstrapReturnsToStepTwoWithTheAddressAndOneNotice() {
        toAddress()
        sessions.nextConnect = { it.copy(phase = SessionPhase.INVALIDATED, error = ApiFailure(FailureKind.INCOMPATIBLE)) }
        submit("chat.example.test")
        compose.onNodeWithText(text(R.string.onboarding_incompatible, "chat.example.test")).assertExists()
        addressIs("https://chat.example.test/")
        compose.onNodeWithTag("onboarding_server_card").assertDoesNotExist(); compose.onNodeWithTag("onboarding_sign_in").assertDoesNotExist()
        sessions.nextConnect = { it.copy(phase = SessionPhase.INVALIDATED, error = ApiFailure(FailureKind.TIMEOUT)) }
        compose.onNodeWithTag("onboarding_continue").performScrollTo().performClick() // Continue on the same address: a fresh bootstrap.
        compose.onNodeWithText(text(R.string.onboarding_not_answering, "chat.example.test")).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_incompatible, "chat.example.test")).assertDoesNotExist() // One notice.
        compose.onNodeWithTag("onboarding_continue").performScrollTo().performClick() // This time it answers.
        compose.onNodeWithTag("onboarding_server_card").assertExists(); compose.onNodeWithTag("onboarding_sign_in").assertExists()
    }

    /** The technical lead's guard: an outdated server is a successful bootstrap, so its notice is on Server found. */
    @Test fun anOutdatedServerReachesServerFoundWithTheUpdateNoticeNotStepTwo() {
        toAddress()
        sessions.nextConnect = { it.copy(updateRequired = true, reportedVersion = "3.9.0") }
        submit("chat.example.test")
        compose.onNodeWithTag("onboarding_server_card").assertExists()
        compose.onNodeWithTag("server_update_title").assertExists()
        compose.onNodeWithTag("onboarding_server_input").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_sign_in").assertDoesNotExist(); compose.onNodeWithTag("onboarding_create").assertDoesNotExist()
    }

    @Test fun createAppearsOnlyWithRegistrationOnAndNoLockdown() {
        sessions.registrationEnabled = true
        toAddress(); submit("chat.example.test")
        compose.onNodeWithTag("onboarding_create").assertExists()
        sessions.nextConnect = { it.copy(public = it.public!!.copy(lockdown = Lockdown(true, "Back at noon"))) }
        compose.runOnIdle { auth.reconnect() }
        compose.onNodeWithTag("onboarding_create").assertDoesNotExist(); compose.onNodeWithTag("onboarding_signup_closed").assertExists()
        sessions.registrationEnabled = false
        compose.runOnIdle { auth.reconnect() }
        compose.onNodeWithTag("onboarding_create").assertDoesNotExist(); compose.onNodeWithTag("onboarding_signup_closed").assertExists()
    }

    @Test fun backFromAFormReturnsToTheChooserAndTheFooterSitsOnServerFound() {
        sessions.nextConnect = { it.copy(public = it.public!!.copy(accessibilityFeedback = "https://chitchat.example/a11y")) }
        toAddress(); submit("chat.example.test")
        compose.onNodeWithTag("a11y_feedback").performScrollTo().assertExists() // P-221 on Server found.
        compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
        compose.onNodeWithText("Username").assertExists()
        Espresso.closeSoftKeyboard(); Espresso.pressBack()
        compose.onNodeWithTag("onboarding_sign_in").assertExists(); compose.onNodeWithText("Username").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_restore").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_back").performScrollTo().performClick() // The visible Back does the same.
        compose.onNodeWithTag("onboarding_sign_in").assertExists()
    }
}
