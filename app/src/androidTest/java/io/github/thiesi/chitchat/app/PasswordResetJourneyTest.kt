package io.github.thiesi.chitchat.app

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.AuthFlow
import io.github.thiesi.chitchat.core.session.PendingReset
import io.github.thiesi.chitchat.core.session.ResetOutcome
import io.github.thiesi.chitchat.core.session.ResetSupport
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptance
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptanceStore
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel
import io.github.thiesi.chitchat.testing.openTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Forgot your password? (O4b2; recovery-key.md) through the real root: step 4a's discovery row, the reset form with its
 * pinned action, every outcome, the pending MFA step and its stop dialog, the cancel outcomes back on step 4a, and the
 * new-key display after a reset ahead of the guidelines and the shell. Nothing is ever resent.
 */
class PasswordResetJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val sessions = TestSessions().apply { resetSupport = ResetSupport.SUPPORTED }
    private val auth = AuthViewModel(sessions)
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = server))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    })
    private val store = object : GuidelinesAcceptanceStore {
        override val accepted = MutableStateFlow<Set<GuidelinesAcceptance>>(emptySet())
        override suspend fun record(acceptance: GuidelinesAcceptance) { accepted.value = accepted.value + acceptance }
        override suspend fun forgetServer(origin: String) = Unit
    }
    private val rules = object : RulesOperations {
        override val state get() = sessions.state
        override suspend fun acceptRules(version: Int, expected: SessionState) = error("No rules in this fixture")
        override suspend fun refresh() = sessions.refresh()
    }
    private val guidelines = GuidelinesViewModel(rules, store)
    private val key = "a7k2b9qx-c4mz-d8rt-e2wn-f6hp"

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun signInForm() {
        compose.runOnUiThread { compose.activity.setContent { Box(Modifier.fillMaxSize()) {
            TestChitChatApp(settings, auth, guidelines = guidelines)
        } } }
        compose.waitUntil(5_000) { exists("onboarding_sign_in") }
        compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("sign_in_password") }
    }
    private fun resetForm(username: String = "Alice") {
        signInForm()
        compose.onNodeWithTag("sign_in_username").performTextReplacement(username)
        compose.waitUntil(5_000) { exists("forgot_password") }
        compose.onNodeWithTag("forgot_password").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("reset_form") }
    }
    private fun fillAndSubmit() {
        compose.onNodeWithTag("reset_key").performScrollTo().performTextReplacement(key)
        compose.onNodeWithTag("reset_password").performScrollTo().performTextReplacement("a new fixture password")
        compose.onNodeWithTag("reset_confirmation").performScrollTo().performTextReplacement("a new fixture password")
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { exists("reset_submit") }
        compose.onNodeWithTag("reset_submit").assertIsEnabled().performClick()
    }
    private fun pending(methods: List<String>): (SessionState) -> SessionState = {
        it.copy(reset = it.reset.copy(pending = PendingReset(methods, "2026-11-18T10:05:00+00:00")))
    }
    private fun text(tag: String, value: String) =
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))

    @Test fun theForgotRowStaysEmptyWhileCheckingThenShowsTheButtonOnce() {
        sessions.discoveryHold = CompletableDeferred()
        signInForm()
        compose.waitUntil(5_000) { exists("forgot_password_pending") }
        assertFalse("Nothing to read while checking", exists("forgot_password") || exists("forgot_password_static"))
        compose.runOnIdle { sessions.discoveryHold!!.complete(Unit) }
        compose.waitUntil(5_000) { exists("forgot_password") }
        compose.waitForIdle()
        assertEquals("One discovery GET per visit, never on recomposition", 1, sessions.discoveries)
    }

    @Test fun anUnsupportedServerShowsTheStaticLineInstead() {
        sessions.resetSupport = ResetSupport.UNSUPPORTED
        signInForm()
        compose.waitUntil(5_000) { exists("forgot_password_static") }
        assertFalse(exists("forgot_password"))
    }

    @Test fun aCompletedResetShowsTheNewKeyBeforeTheGuidelinesAndTheShell() {
        resetForm("Viewer")
        text("reset_username", "Viewer") // Prefilled from step 4a.
        assertTrue("Pinned while the keyboard is hidden", exists("reset_action_block"))
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("recovery_key_display") }
        assertEquals(Triple("Viewer", key, "a new fixture password"), sessions.lastReset)
        assertEquals("One POST", 1, sessions.resets)
        compose.onNodeWithText(compose.activity.getString(io.github.thiesi.chitchat.R.string.recovery_key_title_new)).assertExists()
        compose.onNodeWithText(compose.activity.getString(io.github.thiesi.chitchat.R.string.recovery_key_reset_note)).assertExists()
        assertTrue("FLAG_SECURE while the key shows", secure())
        assertFalse(exists("guidelines_screen")); assertFalse(exists("nav_tab_rooms"))
        compose.onNodeWithTag("recovery_key_continue").performScrollTo().performClick()
        compose.onNodeWithTag("recovery_key_saved").performClick()
        compose.waitUntil(5_000) { exists("guidelines_screen") }
        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
        compose.openTab("rooms").assertIsDisplayed()
    }

    @Test fun aRefusalKeepsTheFieldsShowsTheLineBesideTheActionAndPreparesAgain() {
        resetForm()
        sessions.nextReset = { it.copy(error = ApiFailure(FailureKind.HTTP, 400, "reset_failed")) }
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_failed") }
        assertTrue("The line sits in the action block", compose.onAllNodes(hasTestTag("reset_failed")
            and hasAnyAncestor(hasTestTag("reset_action_block"))).fetchSemanticsNodes().isNotEmpty())
        text("reset_key", key) // The key and passwords keep their text.
        compose.waitUntil(5_000) { sessions.state.value.reset.preparation == io.github.thiesi.chitchat.core.session.RegistrationPreparation.READY }
        compose.onNodeWithTag("reset_submit").assertIsEnabled()
        assertEquals("Never resent", 1, sessions.resets)
    }

    @Test fun aStaleChallengeSaysNothingWasSentBesideTheAction() {
        // Review (#294): the tap found an expired challenge, so nothing was sent and a fresh check runs (PL wording).
        resetForm()
        sessions.nextReset = { it.copy(error = ApiFailure(FailureKind.HTTP, 400, "reset_challenge_expired")) }
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_challenge_renewed") }
        assertTrue(compose.onAllNodes(hasTestTag("reset_challenge_renewed") and hasAnyAncestor(hasTestTag("reset_action_block")))
            .fetchSemanticsNodes().isNotEmpty())
        text("reset_key", key)
    }

    @Test fun anUnknownResetSaysWhatToDoAndSignInReturnsToStep4a() {
        resetForm()
        sessions.nextReset = { it.copy(reset = it.reset.copy(outcome = ResetOutcome.UNCERTAIN)) }
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_unknown") }
        compose.onNodeWithTag("reset_unknown").assertIsDisplayed() // Brought into view, not only announced.
        // The pinned primary is Sign in now, never another reset (PL J1).
        assertFalse("No Reset password while the password may have changed", exists("reset_submit"))
        assertTrue(compose.onAllNodes(hasTestTag("reset_unknown_sign_in") and hasAnyAncestor(hasTestTag("reset_action_block")))
            .fetchSemanticsNodes().isNotEmpty())
        val discoveries = sessions.discoveries
        compose.onNodeWithTag("reset_unknown_sign_in").performClick()
        compose.waitUntil(5_000) { exists("sign_in_password") }
        // Only navigation (TL): no reset POST and no new discovery GET; a deliberate retry stays via Forgot password?.
        compose.waitForIdle()
        assertEquals("Never resent", 1, sessions.resets)
        assertEquals("No network call on the way back", discoveries, sessions.discoveries)
        compose.onNodeWithTag("forgot_password").assertExists()
    }

    @Test fun thePendingStepTakesTheCodeAndCompletes() {
        resetForm()
        sessions.nextReset = pending(listOf("passkey", "recovery_code"))
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_mfa") }
        compose.onNodeWithTag("reset_mfa_deadline").assertExists()
        compose.onNodeWithTag("reset_mfa_code").performTextReplacement("code-1234")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("reset_mfa_use_code").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_display") }
    }

    @Test fun anErrorOnThePendingStepIsShownThere() {
        // Review (#294): the form's own lines don't exist on the MFA step, so its refusals use the screen's error line.
        resetForm()
        sessions.nextReset = pending(listOf("recovery_code"))
        sessions.nextResetCode = { it.copy(error = ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 30),
            retryAtMillis = System.currentTimeMillis() + 30_000) }
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_mfa") }
        compose.onNodeWithTag("reset_mfa_code").performTextReplacement("code-1234")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("reset_mfa_use_code").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(compose.activity.getString(io.github.thiesi.chitchat.R.string.rate_limited))
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reset_mfa").assertExists()
    }

    @Test fun aPasskeyOnlyPendingResetCanOnlyBeCancelled() {
        resetForm()
        sessions.nextReset = pending(listOf("passkey"))
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_mfa_passkey_only") }
        assertFalse(exists("reset_mfa_code")); assertFalse(exists("reset_mfa_use_code"))
        compose.onNodeWithTag("reset_cancel").assertExists()
    }

    @Test fun stopAsksFirstAndAnAcknowledgedCancelSaysNothingChanged() {
        resetForm()
        sessions.nextReset = pending(listOf("recovery_code"))
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_mfa") }
        // Back asks too; Keep going stays.
        Espresso.pressBack()
        compose.onNodeWithTag("reset_keep_going").performClick()
        compose.onNodeWithTag("reset_mfa").assertExists()
        compose.onNodeWithTag("reset_cancel").performScrollTo().performClick()
        compose.onNodeWithTag("reset_stop").performClick()
        compose.waitUntil(5_000) { exists("reset_cancelled") }
        compose.onNodeWithTag("sign_in_password").assertExists()
        assertEquals(1, sessions.cancels)
    }

    @Test fun anUncertainCancelShowsTheLevelTwoBlockOnStep4a() {
        resetForm()
        sessions.nextReset = pending(listOf("recovery_code"))
        sessions.nextCancel = { it.copy(authFlow = AuthFlow.SIGN_IN,
            reset = it.reset.copy(pending = null, outcome = ResetOutcome.CANCEL_UNCERTAIN)) }
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_mfa") }
        compose.onNodeWithTag("reset_cancel").performScrollTo().performClick()
        compose.onNodeWithTag("reset_stop").performClick()
        compose.waitUntil(5_000) { exists("reset_cancel_unknown") }
        assertFalse("Never \"nothing changed\"", exists("reset_cancelled"))
    }

    @Test fun anExpiredPendingResetReturnsToTheFormWithTheLine() {
        resetForm()
        sessions.nextReset = pending(listOf("recovery_code"))
        sessions.nextResetCode = { it.copy(reset = it.reset.copy(pending = null, outcome = ResetOutcome.EXPIRED)) }
        fillAndSubmit()
        compose.waitUntil(5_000) { exists("reset_mfa") }
        compose.onNodeWithTag("reset_mfa_code").performTextReplacement("code-1234")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("reset_mfa_use_code").performClick()
        compose.waitUntil(5_000) { exists("reset_expired") }
        compose.onNodeWithTag("reset_form").assertExists()
    }
}
