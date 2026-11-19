package io.github.thiesi.chitchat.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.ClosureState
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.AccountDeletionViewModel
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.account.TestDeletion
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.openTab
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Delete account (D2; play-readiness.md §2) through the real root: the row, the explanation screen, the decision dialog
 * (the only sender), step-up only on 403, the confirmation and the Check outcomes, Export my data first, and the
 * ended-session screens. Nothing is ever resent.
 */
class AccountDeletionJourneyTest {
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
    private val deletion = TestDeletion(sessions)
    private val model = AccountDeletionViewModel(deletion)

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun dialogNode(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(isDialog()))

    private fun signedIn() {
        compose.runOnUiThread { compose.activity.setContent { Box(Modifier.fillMaxSize()) {
            TestChitChatApp(settings, auth, accountDeletion = model)
        } } }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") } }
        compose.waitUntil(5_000) { exists("nav_tab_rooms") }
    }
    private fun openDeletion() {
        compose.openTab("you").performClick()
        compose.onNodeWithTag("you_account").performScrollTo().performClick()
        compose.onNodeWithTag("delete_account_row").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { exists("delete_account_screen") }
    }
    private fun confirm() {
        compose.onNodeWithTag("delete_account_continue").performScrollTo().performClick()
        dialogNode("delete_account_dialog_confirm").performClick()
    }

    @Test fun theRowOpensTheExplanationAndOnlyTheDialogSends() {
        signedIn(); openDeletion()
        compose.onNodeWithTag("delete_account_now").assertExists()
        compose.onNodeWithTag("delete_account_stays").assertExists()
        // Continue opens the decision dialog; its Cancel sends nothing.
        compose.onNodeWithTag("delete_account_continue").performScrollTo().performClick()
        dialogNode("delete_account_dialog_body").assertIsDisplayed()
        dialogNode("delete_account_dialog_cancel").performClick()
        compose.runOnIdle { assertEquals("Nothing before the dialog's Delete account", 0, deletion.posts) }
        // Cancel on the screen goes back to Privacy and security.
        compose.onNodeWithTag("delete_account_cancel").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("delete_account_row") }
        compose.runOnIdle { assertEquals(0, deletion.posts) }
    }

    @Test fun aConfirmedDeletionShowsTheServersDeadlineAndDoneGoesToStep3() {
        signedIn(); openDeletion(); confirm()
        compose.waitUntil(5_000) { exists("closure_outcome") }
        compose.onNodeWithText(text(R.string.delete_account_scheduled_title)).assertIsDisplayed()
        val deadline = io.github.thiesi.chitchat.feature.account.closureDeadline(deletion.deadline)!!
        compose.onNodeWithTag("closure_scheduled_body").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.delete_account_scheduled_body, deadline)).assertExists()
        compose.onNodeWithTag("closure_restore_line").assertExists()
        assertFalse("Signed out: no shell", exists("nav_tab_rooms"))
        compose.onNodeWithTag("closure_done").performClick()
        compose.waitUntil(5_000) { exists("onboarding_sign_in") }
        compose.runOnIdle {
            assertNull("Done clears the outcome", sessions.state.value.closure)
            assertEquals("Never resent", 1, deletion.posts)
        }
    }

    @Test fun theServersForcedLogoutBeforeThe200ShowsWaitingThenTheConfirmation() {
        // Review (#300): the takeover disposes the explanation screen while the POST is still on the wire; it must settle.
        deletion.reply = TestDeletion.Reply.FORCED_LOGOUT_THEN_CONFIRMED
        signedIn(); openDeletion(); confirm()
        compose.waitUntil(5_000) { exists("closure_waiting") }
        assertFalse(exists("delete_account_screen"))
        compose.runOnIdle { deletion.answer.complete(Unit) }
        compose.waitUntil(5_000) { exists("closure_scheduled_body") }
        compose.runOnIdle { assertFalse("Never cancelled", deletion.cancelled); assertEquals(1, deletion.posts) }
    }

    @Test fun stepUpAsksOnlyOn403WithItsOwnTitle() {
        deletion.stepUp = true
        signedIn(); openDeletion(); confirm()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text(R.string.step_up_title_delete_account)) and
            hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(text(R.string.cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("The 403 only, never resent", 1, deletion.posts); assertNull(sessions.state.value.closure) }
        compose.onNodeWithTag("delete_account_continue").assertExists()
    }

    @Test fun aLiveUnknownOffersOnlyCheckAndStillSignedInSaysNotDeleted() {
        deletion.reply = TestDeletion.Reply.UNKNOWN_LIVE
        signedIn(); openDeletion(); confirm()
        compose.waitUntil(5_000) { exists("delete_account_unknown") }
        compose.waitForIdle()
        compose.onNodeWithTag("delete_account_unknown").assertIsDisplayed() // Brought into view, not only announced (J1).
        assertFalse("Check replaces Continue and Cancel", exists("delete_account_continue") || exists("delete_account_cancel"))
        compose.onNode(hasTestTag("delete_account_check") and hasAnyAncestor(hasTestTag("pinned_action"))).assertIsDisplayed()
        // Check: still signed in, so nothing changed (one read, no POST).
        deletion.check = { it.copy(closure = ClosureState.NotClosed(it.server!!.value, it.user!!.id)) }
        compose.onNodeWithTag("delete_account_check").performClick()
        compose.waitUntil(5_000) { exists("delete_account_not_deleted") }
        compose.waitForIdle()
        compose.onNodeWithTag("delete_account_not_deleted").assertIsDisplayed()
        compose.onNodeWithTag("delete_account_continue").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, deletion.checks); assertEquals("Never resent", 1, deletion.posts) }
    }

    @Test fun anEndedUnknownKeepsCheckThenSignedOutIsHedged() {
        deletion.reply = TestDeletion.Reply.UNKNOWN_ENDED
        signedIn(); openDeletion(); confirm()
        compose.waitUntil(5_000) { exists("closure_outcome") && exists("delete_account_unknown") }
        compose.onNodeWithText(text(R.string.delete_account_title, "chat.​example.​test")).assertExists()
        // A Check that doesn't get through keeps the notice and adds the caption.
        compose.onNodeWithTag("delete_account_check").performClick()
        compose.waitUntil(5_000) { exists("delete_account_check_failed") }
        // In the pinned block above Check, so it stays in view (J3).
        compose.onNode(hasTestTag("delete_account_check_failed") and hasAnyAncestor(hasTestTag("pinned_action"))).assertIsDisplayed()
        compose.onNodeWithTag("delete_account_unknown").assertExists()
        // An anonymous session read: "most likely", never the definite title.
        deletion.check = { s -> s.copy(phase = SessionPhase.ANONYMOUS,
            closure = ClosureState.LikelyClosed(s.server!!.value, (s.closure as ClosureState.Unknown).userId)) }
        compose.onNodeWithTag("delete_account_check").performClick()
        compose.waitUntil(5_000) { exists("closure_signed_out_body") }
        compose.onNodeWithText(text(R.string.delete_account_signed_out_title)).assertIsDisplayed()
        compose.onAllNodesWithTag("closure_scheduled_body").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithTag("closure_done").performClick()
        compose.waitUntil(5_000) { exists("onboarding_sign_in") }
        compose.runOnIdle { assertEquals(2, deletion.checks); assertEquals(1, deletion.posts) }
    }

    @Test fun waitingShowsNoActionsUntilTheResultLands() {
        signedIn()
        compose.runOnIdle { val s = sessions.state.value
            sessions.state.value = s.copy(phase = SessionPhase.INVALIDATED, user = null, closure = ClosureState.Waiting(s.server!!.value, s.user!!.id)) }
        compose.waitUntil(5_000) { exists("closure_waiting") }
        assertFalse(exists("closure_done") || exists("delete_account_check"))
        // Settings could change or forget the server and drop the result still landing on D1's marker.
        compose.onAllNodes(hasText(text(R.string.settings))).fetchSemanticsNodes().let { assertTrue("No Settings while Waiting", it.isEmpty()) }
        compose.runOnIdle { val s = sessions.state.value
            sessions.state.value = s.copy(closure = ClosureState.Scheduled((s.closure as ClosureState.Waiting).origin, 7, deletion.deadline)) }
        compose.waitUntil(5_000) { exists("closure_scheduled_body") }
    }

    @Test fun theLastSuperAdministratorSeesTheRefusal() {
        deletion.reply = TestDeletion.Reply.LAST_SUPER_ADMIN
        signedIn(); openDeletion(); confirm()
        compose.waitUntil(5_000) { exists("delete_account_refusal") }
        compose.onNodeWithText(text(R.string.delete_account_last_super_admin)).assertExists()
        compose.runOnIdle { assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase); assertEquals(1, deletion.posts) }
    }

    @Test fun anEndedRefusalIsACaptionOnTheOrdinaryEndedPage() {
        signedIn()
        compose.runOnIdle { val s = sessions.state.value
            sessions.state.value = s.copy(phase = SessionPhase.INVALIDATED, user = null, closure = ClosureState.EndedWithRefusal(
                s.server!!.value, s.user!!.id, io.github.thiesi.chitchat.core.account.ClosureResult.LastSuperAdmin)) }
        compose.waitUntil(5_000) { exists("delete_account_ended_refusal") }
        compose.onNodeWithText(text(R.string.delete_account_ended_last_super_admin)).assertExists()
        compose.onNodeWithText(text(R.string.session_not_available)).assertExists()
    }

    @Test fun exportMyDataFirstReturnsWithTheExportInView() {
        signedIn(); openDeletion()
        compose.onNodeWithTag("delete_account_export_first").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("export_personal_data") && !exists("delete_account_screen") }
        compose.waitForIdle()
        compose.onNodeWithTag("export_personal_data").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Nothing runs on its own", 0, deletion.posts) }
    }

    @Test fun theRowIsQuietErrorTextWithoutALiveRegion() {
        signedIn()
        compose.openTab("you").performClick()
        compose.onNodeWithTag("you_account").performScrollTo().performClick()
        // The bar names the screen as the row, the notices and the public page do (J4).
        compose.onNodeWithText(text(R.string.you_privacy_security)).assertIsDisplayed()
        val row = compose.onNodeWithTag("delete_account_row").performScrollTo()
        row.assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("delete_account_row") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .fetchSemanticsNodes().isEmpty())
        assertTrue("A 48dp target", row.fetchSemanticsNode().size.height >= with(compose.density) { 48.dp.toPx() } - 1)
        Espresso.pressBack()
    }
}
