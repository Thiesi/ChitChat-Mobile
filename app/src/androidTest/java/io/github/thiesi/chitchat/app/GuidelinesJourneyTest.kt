package io.github.thiesi.chitchat.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.policies.PolicyDiscovery
import io.github.thiesi.chitchat.core.policies.PolicyDocument
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.policies.PolicyLink
import io.github.thiesi.chitchat.core.policies.PolicyReads
import io.github.thiesi.chitchat.core.policies.RulesAcceptResult
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.policies.RulesStatus
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptance
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptanceStore
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.core.settings.covers
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel
import io.github.thiesi.chitchat.feature.policies.PolicyDocumentViewModel
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.testing.openTab
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The guidelines screen between sign-in and the shell (O3b2; play-readiness.md §1, P-170/171) through the real app
 * root: nothing of the shell before I agree, the server's accept POST only while required, Sign out, and a document
 * opened from the screen closing back to it. No network.
 */
class GuidelinesJourneyTest {
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
    private val posts = CopyOnWriteArrayList<Int>()
    private val rules = object : RulesOperations {
        override val state get() = sessions.state
        override suspend fun acceptRules(version: Int, expected: SessionState): RulesAcceptResult {
            posts += version
            val status = RulesStatus(version, null, false)
            sessions.state.value = sessions.state.value.copy(rules = status)
            return RulesAcceptResult.Accepted(status)
        }
        override suspend fun refresh() = sessions.refresh()
    }
    private val reads = object : PolicyReads {
        override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState) = PolicyDocument.NotPublished
    }
    private val guidelines = GuidelinesViewModel(rules, store)
    private val documents = PolicyDocumentViewModel(reads)
    private val rulesLink = PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00")

    private val api = io.github.thiesi.chitchat.testing.NativeChatFixtures(sessions)
    private val notices = io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel(api.notices)
    private val taps = io.github.thiesi.chitchat.core.push.PendingPushTap()
    private fun install(activity: ComponentActivity) = activity.setContent { Box(Modifier.fillMaxSize()) {
        TestChitChatApp(settings, auth, notifications = notices, pushTaps = taps, policies = documents, guidelines = guidelines)
    } }
    @org.junit.After fun close() { compose.runOnIdle { notices.background() } }

    private fun signIn(shape: (SessionState) -> SessionState = { it }) {
        compose.runOnUiThread { install(compose.activity) }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = shape(sessions.state.value) }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun nothingOfTheShellShowsBeforeIAgreeThenTheShellOpens() {
        signIn()
        compose.onNodeWithTag("guidelines_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_tab_rooms").assertDoesNotExist()
        compose.onNodeWithTag("guidelines_consent").assertDoesNotExist()
        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.accepted.value.covers(server.value, 7) }
        compose.onNodeWithTag("nav_tab_rooms").assertIsDisplayed()
        compose.onNodeWithTag("guidelines_screen").assertDoesNotExist()
        assertEquals("No server rules: no accept POST", emptyList<Int>(), posts)
    }

    @Test fun anAlreadyAcceptedAccountGoesStraightToTheShell() {
        store.accepted.value = setOf(GuidelinesAcceptance(server.value, 7, io.github.thiesi.chitchat.core.settings.APP_GUIDELINES_VERSION))
        signIn()
        compose.onNodeWithTag("nav_tab_rooms").assertIsDisplayed()
        compose.onNodeWithTag("guidelines_screen").assertDoesNotExist()
    }

    @Test fun requiredServerRulesSendOneAcceptOfTheShownVersionWithTheConsentLine() {
        signIn { it.copy(public = PublicSession(false, emptyList(), Lockdown(false), policies = PolicyDiscovery(rulesLink, null, null)),
            rules = RulesStatus(1, null, true)) }
        compose.onNodeWithTag("guidelines_consent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("guidelines_rules_row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
        compose.waitUntil(5_000) { store.accepted.value.covers(server.value, 7) }
        assertEquals(listOf(4), posts)
        compose.onNodeWithTag("nav_tab_rooms").assertIsDisplayed()
    }

    @Test fun theRulesRowOpensTheDocumentAndBackReturnsToTheScreen() {
        signIn { it.copy(public = PublicSession(false, emptyList(), Lockdown(false), policies = PolicyDiscovery(rulesLink, null, null)),
            rules = RulesStatus(4, null, false)) }
        compose.onNodeWithTag("guidelines_consent").assertDoesNotExist() // Accepted elsewhere: reading links only.
        compose.onNodeWithTag("guidelines_rules_row").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists("policy_not_published") }
        compose.onNodeWithTag("nav_tab_rooms").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("guidelines_agree").performScrollTo().assertIsDisplayed()
        assertFalse(store.accepted.value.covers(server.value, 7))
    }

    @Test fun signOutLeavesWithoutRecordingAnything() {
        signIn()
        compose.onNodeWithTag("guidelines_sign_out").performScrollTo().performClick()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.SIGNED_OUT }
        compose.onNodeWithTag("guidelines_screen").assertDoesNotExist()
        assertTrue(store.accepted.value.isEmpty())
    }

    /**
     * A push tap for this account while the screen is pending doesn't reach the shell. The existing guarded routing
     * defers it: only the composed Activity destination consumes a tap, and nothing of the shell is composed until I
     * agree. So it waits (still pending, never dropped or replayed elsewhere) and routes once, to Activity, after I agree.
     */
    @Test fun aPushTapWhileTheScreenIsPendingWaitsAndRoutesOnlyAfterIAgree() {
        signIn()
        compose.onNodeWithTag("guidelines_screen").assertIsDisplayed()
        compose.runOnIdle { taps.offer(io.github.thiesi.chitchat.core.push.PushTap(server.value, 7, null)) }
        compose.waitForIdle()
        compose.onNodeWithTag("guidelines_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_tab_activity").assertDoesNotExist()
        compose.onNodeWithTag("notification_list").assertDoesNotExist()
        compose.runOnIdle { assertTrue("The tap waits behind the screen", taps.state.value != null) }
        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
        compose.waitUntil(5_000) { taps.state.value == null && compose.onAllNodesWithTagExists("notification_list") }
        compose.openTab("activity").assertIsSelected()
    }

    /**
     * A restored shell stack (activity recreation) with acceptance pending shows the screen, not the restored
     * destination; after I agree, the restored stack is where the person was.
     */
    @Test fun aRestoredStackWithAcceptancePendingShowsTheScreenFirst() {
        store.accepted.value = setOf(GuidelinesAcceptance(server.value, 7, io.github.thiesi.chitchat.core.settings.APP_GUIDELINES_VERSION))
        signIn()
        compose.openTab("activity").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists("notification_list") }
        compose.runOnIdle { store.accepted.value = emptySet() } // E.g. a new guidelines version: acceptance is pending again.
        compose.onNodeWithTag("guidelines_screen").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        compose.onNodeWithTag("guidelines_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_tab_activity").assertDoesNotExist()
        compose.onNodeWithTag("notification_list").assertDoesNotExist()
        compose.onNodeWithTag("guidelines_agree").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTagExists("notification_list") }
        compose.openTab("activity").assertIsSelected()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
