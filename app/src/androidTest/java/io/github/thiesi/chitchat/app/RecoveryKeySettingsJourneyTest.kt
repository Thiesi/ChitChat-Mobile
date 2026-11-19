package io.github.thiesi.chitchat.app

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso
import io.github.thiesi.chitchat.core.account.AccountAccess
import io.github.thiesi.chitchat.core.account.AccountOperations
import io.github.thiesi.chitchat.core.account.CreatedRecoveryKey
import io.github.thiesi.chitchat.core.account.RecoveryKey
import io.github.thiesi.chitchat.core.account.RecoveryKeyStatus
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.RecoveryKeyViewModel
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.openTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Rule
import org.junit.Test

/**
 * Privacy and security → Recovery key (O4a2b; recovery-key.md P-188) through the real root: the row per status, Replace
 * behind its dialog, Create without one, the key display over the composed shell (secure window, shell out of TalkBack's
 * reach, Back asks first, back to the screen with the new date), and the "didn't reach this phone" notice on Rooms.
 */
class RecoveryKeySettingsJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val sessions = TestSessions()
    private val auth = AuthViewModel(sessions)
    private val store = object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server = server))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun savePureBlack(enabled: Boolean) = Unit
        override suspend fun saveTheme(theme: ThemePreference) = Unit
    }
    private val settings = SettingsViewModel(store)
    private val newDate = "2026-11-17T10:00:00+00:00"
    private val accounts = object : AccountOperations {
        override val session get() = sessions.state
        var posts = 0
        /** The server asks for step-up first (403 step_up_required) while this is set. */
        var stepUp = false
        override suspend fun open(): AccountAccess = object : AccountAccess {
            override fun ensureCurrent() = Unit
            override suspend fun export() = error("No export here")
            override suspend fun verificationMethods() = VerificationMethods(false, true, 8)
            override suspend fun verifyPassword(password: String) = Unit
            override suspend fun verifyRecovery(code: String) = error("No recovery code here")
            override suspend fun createRecoveryKey(): CreatedRecoveryKey {
                posts++
                if (stepUp) throw io.github.thiesi.chitchat.core.network.ApiFailure(
                    io.github.thiesi.chitchat.core.network.FailureKind.HTTP, 403, "step_up_required")
                sessions.state.value = sessions.state.value.copy(recoveryKey = RecoveryKeyStatus.Known(true, newDate), recoveryKeyUndelivered = null)
                return CreatedRecoveryKey(RecoveryKey("A7K2-B9QX-C4MZ-D8RT-E2WN-F6HP"), newDate)
            }
        }
        override suspend fun dismissRecoveryKeyNotice(expected: SessionState) {
            sessions.state.value = sessions.state.value.copy(recoveryKeyUndelivered = null)
        }
    }
    private val keys = RecoveryKeyViewModel(accounts)

    private fun signedIn(status: RecoveryKeyStatus, undelivered: Boolean = false) {
        compose.runOnUiThread { compose.activity.setContent { Box(Modifier.fillMaxSize()) {
            TestChitChatApp(settings, auth, recoveryKeys = keys)
        } } }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(recoveryKey = status, recoveryKeyUndelivered = io.github.thiesi.chitchat.core.account.KeyOrigin.REGISTRATION.takeIf { undelivered })
        }
        compose.waitUntil(5_000) { exists("nav_tab_rooms") }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun openPrivacy() {
        compose.openTab("you").performClick()
        compose.onNodeWithTag("you_account").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun replaceAsksFirstThenShowsTheKeyOverTheShellAndReturnsWithTheNewDate() {
        signedIn(RecoveryKeyStatus.Known(true, "2026-11-01T10:00:00+00:00"))
        openPrivacy()
        compose.onNodeWithTag("recovery_key_row").assertExists().performClick()
        compose.waitUntil(5_000) { exists("recovery_key_screen") }
        compose.onNodeWithTag("recovery_key_created").assertExists()
        compose.onNodeWithTag("recovery_key_replace").performScrollTo().performClick()
        // The decision dialog: Cancel sends nothing.
        compose.onNodeWithTag("recovery_key_replace_cancel").performClick()
        compose.runOnIdle { assertEquals(0, accounts.posts) }
        compose.onNodeWithTag("recovery_key_replace").performScrollTo().performClick()
        compose.onNodeWithTag("recovery_key_replace_confirm").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_display") }
        compose.runOnIdle { assertEquals(1, accounts.posts); assertTrue("FLAG_SECURE over the shell", secure()) }
        compose.onNodeWithText(compose.activity.getString(io.github.thiesi.chitchat.R.string.recovery_key_title_new)).assertExists()
        compose.onNodeWithText(compose.activity.getString(io.github.thiesi.chitchat.R.string.recovery_key_replaced_note)).assertExists()
        assertFalse("The shell is out of TalkBack's reach while the key shows", exists("recovery_key_screen"))
        // Back asks first; I've saved it returns to the Recovery key screen with the new date.
        Espresso.pressBack()
        compose.onNodeWithTag("recovery_key_saved").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_screen") }
        compose.runOnIdle { assertFalse("FLAG_SECURE is cleared", secure()); assertNull(keys.state.value.shown) }
        compose.onNodeWithTag("recovery_key_created").assertTextContains("2026", substring = true)
        compose.runOnIdle { assertEquals("Never resent", 1, accounts.posts) }
    }

    @Test fun notSetUpCreatesWithoutADialog() {
        signedIn(RecoveryKeyStatus.Known(false, null))
        openPrivacy()
        compose.onNodeWithTag("recovery_key_row_not_set_up").assertExists()
        compose.onNodeWithTag("recovery_key_row").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_screen") }
        compose.onNodeWithTag("recovery_key_not_set_up").assertExists()
        compose.onNodeWithTag("recovery_key_create").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("recovery_key_display") }
        compose.onNodeWithText(compose.activity.getString(io.github.thiesi.chitchat.R.string.recovery_key_title_save)).assertExists()
        assertFalse(exists("recovery_key_replace_confirm"))
    }

    /** The step-up dialog names this request's own action; Cancel ends it with nothing committed (the 403 refused it). */
    private fun assertStepUpTitle(title: Int, other: Int) {
        val text = compose.activity.getString(title)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(compose.activity.getString(other)).assertCountEquals(0)
        compose.onAllNodesWithText("Confirm personal-data export").assertCountEquals(0)
        compose.onNode(hasText(compose.activity.getString(io.github.thiesi.chitchat.R.string.cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("The 403 only, never resent", 1, accounts.posts); assertNull(keys.state.value.shown) }
    }

    @Test fun replaceStepUpIsTitledForReplacing() {
        accounts.stepUp = true
        signedIn(RecoveryKeyStatus.Known(true, "2026-11-01T10:00:00+00:00"))
        openPrivacy()
        compose.onNodeWithTag("recovery_key_row").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_screen") }
        compose.onNodeWithTag("recovery_key_replace").performScrollTo().performClick()
        compose.onNodeWithTag("recovery_key_replace_confirm").performClick()
        assertStepUpTitle(io.github.thiesi.chitchat.R.string.step_up_title_replace_recovery_key,
            io.github.thiesi.chitchat.R.string.step_up_title_create_recovery_key)
    }

    @Test fun createStepUpIsTitledForCreating() {
        accounts.stepUp = true
        signedIn(RecoveryKeyStatus.Known(false, null))
        openPrivacy()
        compose.onNodeWithTag("recovery_key_row").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_screen") }
        compose.onNodeWithTag("recovery_key_create").performScrollTo().performClick()
        assertStepUpTitle(io.github.thiesi.chitchat.R.string.step_up_title_create_recovery_key,
            io.github.thiesi.chitchat.R.string.step_up_title_replace_recovery_key)
    }

    /** The colour Text actually resolved, from its layout result (the merged style, including LocalContentColor). */
    private fun resolvedColour(tag: String): androidx.compose.ui.graphics.Color {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult]
            .action?.invoke(results)
        return results.single().layoutInput.style.color
    }

    @Test fun theRealScreenResolvesItsPlainTextToOnBackgroundInDarkTheme() {
        // Not just the capture host: O1's revalidation screen once drew black on dark (TL). Both key states, real root.
        store.settings.value = store.settings.value.copy(theme = ThemePreference.DARK)
        val onBackground = io.github.thiesi.chitchat.core.theme.Lantern.dark.text
        signedIn(RecoveryKeyStatus.Known(true, "2026-11-01T10:00:00+00:00"))
        openPrivacy()
        compose.onNodeWithTag("recovery_key_row").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_screen") }
        assertEquals(onBackground, resolvedColour("recovery_key_explanation"))
        assertEquals(onBackground, resolvedColour("recovery_key_replace_explanation"))
        compose.runOnIdle { sessions.state.value = sessions.state.value.copy(recoveryKey = RecoveryKeyStatus.Known(false, null)) }
        compose.waitUntil(5_000) { exists("recovery_key_create") }
        assertEquals(onBackground, resolvedColour("recovery_key_explanation"))
    }

    @Test fun aProviderOnlyAccountHasNoRow() {
        signedIn(RecoveryKeyStatus.NotApplicable)
        openPrivacy()
        compose.waitForIdle()
        assertFalse(exists("recovery_key_row"))
    }

    @Test fun theUndeliveredNoticeShowsOnRoomsAndGoThereOpensTheScreen() {
        signedIn(RecoveryKeyStatus.Known(true, "2026-11-01T10:00:00+00:00"), undelivered = true)
        compose.waitUntil(5_000) { exists("recovery_key_notice") }
        compose.onNodeWithTag("recovery_key_notice_go_there").performClick()
        compose.waitUntil(5_000) { exists("recovery_key_screen") }
        compose.runOnIdle { assertNull("It ends on Go there", sessions.state.value.recoveryKeyUndelivered) }
    }

    @Test fun notNowEndsTheNotice() {
        signedIn(RecoveryKeyStatus.Known(true, "2026-11-01T10:00:00+00:00"), undelivered = true)
        compose.waitUntil(5_000) { exists("recovery_key_notice") }
        compose.onNodeWithTag("recovery_key_notice_not_now").performClick()
        compose.waitUntil(5_000) { !exists("recovery_key_notice") }
    }
}
