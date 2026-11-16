package io.github.thiesi.chitchat.app

import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso
import io.github.thiesi.chitchat.core.account.RecoveryKey
import io.github.thiesi.chitchat.core.account.RecoveryKeyStatus
import io.github.thiesi.chitchat.core.policies.RulesAcceptResult
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.push.PendingPushTap
import io.github.thiesi.chitchat.core.push.PushTap
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptance
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptanceStore
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.openTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The registration key display (O4a2a; recovery-key.md P-184/P-185) through the real root, with the guidelines gate on.
 * It comes after the update screen and before the guidelines and the shell; FLAG_SECURE is set only while it shows;
 * Continue and Back ask first; a push tap or a restored stack can't get past it.
 */
class RecoveryKeyJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val sessions = TestSessions()
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
    private val api = NativeChatFixtures(sessions)
    private val notices = NotificationCenterViewModel(api.notices)
    private val taps = PendingPushTap()
    private val key = "A7K2-B9QX-C4MZ-D8RT-E2WN-F6HP"

    @After fun close() { compose.runOnIdle { notices.background() } }

    private fun install(activity: ComponentActivity) = activity.setContent { Box(Modifier.fillMaxSize()) {
        TestChitChatApp(settings, auth, notifications = notices, pushTaps = taps, guidelines = guidelines)
    } }
    private fun registered() {
        compose.runOnUiThread { install(compose.activity) }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(registrationRecoveryKey = RecoveryKey(key),
                recoveryKey = RecoveryKeyStatus.Known(true, "2026-11-16T10:00:00+00:00"))
        }
        compose.waitUntil(5_000) { exists("recovery_key_display") }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun theKeyShowsOnceBeforeTheGuidelinesAndTheShellInASecureWindow() {
        registered()
        assertTrue("FLAG_SECURE while the key shows (screenshots, recordings, recents thumbnail)", secure())
        assertFalse(exists("guidelines_screen")); assertFalse(exists("nav_tab_rooms"))
        compose.onNodeWithTag("recovery_key_group_1").assertTextEquals("A7K2")
        compose.onNodeWithTag("recovery_key_group_6").assertTextEquals("F6HP")
        // Continue and Back both ask first; Go back keeps the key on screen.
        compose.onNodeWithTag("recovery_key_continue").performScrollTo().performClick()
        compose.onNodeWithTag("recovery_key_go_back").performClick()
        compose.onNodeWithTag("recovery_key_display").assertExists()
        Espresso.pressBack()
        compose.onNodeWithTag("recovery_key_saved").assertExists()
        compose.onNodeWithTag("recovery_key_saved").performClick()
        compose.waitUntil(5_000) { exists("guidelines_screen") }
        assertNull("The key leaves memory on I've saved it", sessions.state.value.registrationRecoveryKey)
        assertFalse("FLAG_SECURE is cleared once the key is gone", secure())
        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
        compose.openTab("rooms").assertIsDisplayed()
    }

    @Test fun copyMarksTheClipSensitive() {
        registered()
        compose.onNodeWithTag("recovery_key_copy").performScrollTo().performClick()
        compose.onNodeWithTag("recovery_key_copied").assertExists()
        compose.runOnIdle {
            val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertEquals(key, clip.getItemAt(0).text.toString())
            if (android.os.Build.VERSION.SDK_INT >= 33)
                assertTrue(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
        }
    }

    @Test fun aPushTapWaitsBehindTheKeyDisplayAndTheGuidelines() {
        registered()
        compose.runOnIdle { taps.offer(PushTap(server.value, 7, null)) }
        compose.waitForIdle()
        compose.onNodeWithTag("recovery_key_display").assertExists()
        assertFalse(exists("notification_list"))
        compose.runOnIdle { assertNotNull("The tap waits", taps.state.value) }
        compose.onNodeWithTag("recovery_key_continue").performScrollTo().performClick()
        compose.onNodeWithTag("recovery_key_saved").performClick()
        compose.waitUntil(5_000) { exists("guidelines_screen") }
        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
        compose.waitUntil(5_000) { taps.state.value == null && exists("notification_list") }
    }

    @Test fun aRestoredStackStillShowsTheKeyDisplayFirst() {
        registered()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        compose.waitUntil(5_000) { exists("recovery_key_display") }
        assertFalse(exists("nav_tab_rooms")); assertFalse(exists("guidelines_screen"))
        assertTrue(secure())
    }

    @Test fun theClipboardHistoryLineShowsOnlyBelowApi33() {
        for ((sdk, shown) in listOf(32 to true, 33 to false)) {
            compose.runOnUiThread { compose.activity.setContent { io.github.thiesi.chitchat.core.theme.ChitChatTheme(dark = false) {
                RecoveryKeyDisplay(RecoveryKeyView(RecoveryKey(key), io.github.thiesi.chitchat.R.string.recovery_key_title_save, createdAt = null),
                    installation = "The Common Room", host = "chat.example.test", username = "Viewer", onSaved = {}, sdkInt = sdk)
            } } }
            compose.waitForIdle()
            assertEquals("API $sdk", shown, exists("recovery_key_clipboard_line"))
        }
    }
}
