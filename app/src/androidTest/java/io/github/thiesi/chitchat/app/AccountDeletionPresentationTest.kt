package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.ClosureResult
import io.github.thiesi.chitchat.core.account.ClosureState
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.AccountDeletionViewModel
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.account.TestDeletion
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.openTab
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * D2 for J, at a real Configuration font scale, light and dark, through the real root: the Delete account row, the
 * explanation screen, the decision dialog and the step-up dialog (their actions asserted at both scales), the live
 * Unknown and "wasn't deleted", and the ended-session screens (Waiting, Unknown, a failed Check, "You've been signed
 * out", the confirmation with the server's deadline, and the refusal caption). One 200% confirmation uses a long-date
 * locale. Collected into `deletion-presentation`. No network.
 */
class AccountDeletionPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private lateinit var sessions: TestSessions
    private lateinit var deletion: TestDeletion

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun dialogNode(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(isDialog()))
    private fun set(change: (SessionState) -> SessionState) = compose.runOnIdle { sessions.state.value = change(sessions.state.value) }
    private val origin get() = sessions.state.value.server!!.value

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) scene(dark, fontScale) {
            val at = "deletion-$label-$name"
            compose.openTab("you").performClick()
            compose.onNodeWithTag("you_account").performScrollTo().performClick()
            compose.onNodeWithTag("delete_account_row").performScrollTo().assertIsDisplayed()
            capture("$at-row")
            compose.onNodeWithTag("delete_account_row").performClick()
            compose.waitUntil(5_000) { exists("delete_account_screen") }
            capture("$at-explanation")
            compose.onNodeWithTag("delete_account_cancel").performScrollTo()
            capture("$at-explanation-actions")
            // The decision dialog: both actions shown at either scale (P-220).
            compose.onNodeWithTag("delete_account_continue").performScrollTo().performClick()
            dialogNode("delete_account_dialog_cancel").assertIsDisplayed(); dialogNode("delete_account_dialog_confirm").assertIsDisplayed()
            captureDialog("$at-dialog")
            // Step-up only on 403, with its own title; Cancel and Verify shown (a form dialog keeps its geometry).
            deletion.stepUp = true
            dialogNode("delete_account_dialog_confirm").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(text(R.string.step_up_title_delete_account)) and
                hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(text(R.string.cancel)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            captureDialog("$at-stepup")
            compose.onNode(hasText(text(R.string.cancel)) and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            // The live Unknown (Check pinned as the single primary), then "wasn't deleted".
            set { it.copy(closure = ClosureState.Unknown(origin, it.user!!.id)) }
            compose.waitUntil(5_000) { exists("delete_account_unknown") }
            capture("$at-unknown")
            set { it.copy(closure = ClosureState.NotClosed(origin, it.user!!.id)) }
            compose.waitUntil(5_000) { exists("delete_account_not_deleted") }
            capture("$at-notdeleted")
            // The ended-session screens.
            set { it.copy(phase = SessionPhase.INVALIDATED, user = null, closure = ClosureState.Waiting(origin, 7)) }
            compose.waitUntil(5_000) { exists("closure_waiting") }
            capture("$at-waiting")
            set { it.copy(closure = ClosureState.Unknown(origin, 7)) }
            compose.waitUntil(5_000) { exists("closure_outcome") && exists("delete_account_unknown") }
            capture("$at-endedunknown")
            compose.onNodeWithTag("delete_account_check").performClick()
            compose.waitUntil(5_000) { exists("delete_account_check_failed") }
            capture("$at-checkfailed")
            set { it.copy(closure = ClosureState.LikelyClosed(origin, 7)) }
            compose.waitUntil(5_000) { exists("closure_signed_out_body") }
            capture("$at-signedout")
            set { it.copy(closure = ClosureState.Scheduled(origin, 7, deletion.deadline)) }
            compose.waitUntil(5_000) { exists("closure_scheduled_body") }
            capture("$at-scheduled")
            if (!dark && fontScale == 2f) {
                // A long date string (PL): the deadline's date and time stay together where they can.
                val previous = Locale.getDefault()
                try {
                    Locale.setDefault(Locale.forLanguageTag("pt-BR"))
                    set { it.copy(closure = null) }; compose.waitForIdle()
                    set { it.copy(closure = ClosureState.Scheduled(origin, 7, deletion.deadline)) }
                    compose.waitUntil(5_000) { exists("closure_scheduled_body") }
                    capture("$at-scheduled-longdate")
                } finally { Locale.setDefault(previous) }
            }
            set { it.copy(closure = ClosureState.EndedWithRefusal(origin, 7, ClosureResult.LastSuperAdmin)) }
            compose.waitUntil(5_000) { exists("delete_account_ended_refusal") }
            capture("$at-endedrefusal")
        }
    }

    private fun scene(dark: Boolean, fontScale: Float, drive: () -> Unit) {
        FontScaleTestActivity.requestedFontScale = fontScale
        sessions = TestSessions()
        deletion = TestDeletion(sessions)
        val auth = AuthViewModel(sessions)
        val model = AccountDeletionViewModel(deletion)
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
                theme = if (dark) ThemePreference.DARK else ThemePreference.LIGHT))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun savePureBlack(enabled: Boolean) = Unit
            override suspend fun saveTheme(theme: ThemePreference) = Unit
        })
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                activity.setContent { Box(Modifier.fillMaxSize().testTag("deletion_host")) {
                    // #349 (TL): a fixed wall clock and zone, so the signed-out "about" date (and its capture) is the
                    // same on every run date and image zone: 2026-11-18 in UTC, 14 days on.
                    androidx.compose.runtime.CompositionLocalProvider(io.github.thiesi.chitchat.feature.account.LocalWallClock provides
                        io.github.thiesi.chitchat.feature.account.WallClock({ java.time.Instant.parse("2026-11-18T10:00:00Z") },
                            { java.time.ZoneId.of("UTC") })) {
                        TestChitChatApp(settings, auth, accountDeletion = model)
                    }
                } }
            }
            compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
            compose.runOnIdle { runBlocking { sessions.signIn("Viewer", "fixture-password") } }
            compose.waitUntil(5_000) { exists("nav_tab_rooms") }
            drive()
        }
    }

    private fun capture(name: String) = save(name, compose.onNodeWithTag("deletion_host"))
    private fun captureDialog(name: String) = save(name, compose.onNode(isDialog()))

    private fun save(name: String, node: SemanticsNodeInteraction) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-deletion-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
