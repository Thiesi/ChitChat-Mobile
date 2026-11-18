package io.github.thiesi.chitchat.feature.auth

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.AuthFlow
import io.github.thiesi.chitchat.core.session.PendingReset
import io.github.thiesi.chitchat.core.session.ResetOutcome
import io.github.thiesi.chitchat.core.session.ResetSupport
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.AppSettings
import io.github.thiesi.chitchat.core.settings.SettingsStore
import io.github.thiesi.chitchat.core.settings.ThemePreference
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * O4b2 for J, at a real Configuration font scale, light and dark, through the real root (keyboard down): step 4a with
 * Forgot password?, the reset form with its pinned action, the reset_failed line in that block, the Unknown notice,
 * the MFA step (code and passkey-only), the stop dialog (its actions asserted at both scales, P-220), and the cancel
 * outcomes back on step 4a. Collected into `reset-presentation`. No network.
 */
class ResetPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val key = "a7k2b9qx-c4mz-d8rt-e2wn-f6hp"

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            scene(dark, fontScale) { capture("reset-$label-$name-signin-forgot") }
            scene(dark, fontScale) { toForm()
                // The form opens at its title even though step 4a's username was just typed (keyboard up).
                compose.onNodeWithTag("reset_title").assertIsDisplayed()
                capture("reset-$label-$name-form") }
            scene(dark, fontScale, mfa = listOf("recovery_code")) { toForm(); submit(); compose.waitUntil(5_000) { exists("reset_mfa") }
                compose.onNodeWithTag("reset_mfa_title").assertIsDisplayed()
                capture("reset-$label-$name-mfa")
                compose.onNodeWithTag("reset_cancel").performScrollTo().performClick()
                // P-220: no body text, so nothing to scroll; both actions stay displayed at either scale.
                compose.onNodeWithTag("reset_keep_going").assertIsDisplayed(); compose.onNodeWithTag("reset_stop").assertIsDisplayed()
                if (fontScale == 1f) captureDialog("reset-$label-$name-stop")
                compose.onNodeWithTag("reset_stop").performClick()
                compose.waitUntil(5_000) { exists("reset_cancelled") }
                if (fontScale == 1f) capture("reset-$label-$name-cancelled")
            }
            if (fontScale == 2f) {
                scene(dark, fontScale, mfa = listOf("passkey")) { toForm(); submit(); compose.waitUntil(5_000) { exists("reset_mfa_passkey_only") }
                    capture("reset-$label-$name-passkey") }
                scene(dark, fontScale, reply = { it.copy(error = ApiFailure(FailureKind.HTTP, 400, "reset_failed")) }) {
                    toForm(); submit(); compose.waitUntil(5_000) { exists("reset_failed") }; capture("reset-$label-$name-failed") }
                scene(dark, fontScale, reply = { it.copy(reset = it.reset.copy(outcome = ResetOutcome.UNCERTAIN)) }) {
                    toForm(); submit(); compose.waitUntil(5_000) { exists("reset_unknown") }; capture("reset-$label-$name-unknown") }
                scene(dark, fontScale, mfa = listOf("recovery_code"), cancel = { it.copy(authFlow = AuthFlow.SIGN_IN,
                    reset = it.reset.copy(pending = null, outcome = ResetOutcome.CANCEL_UNCERTAIN)) }) {
                    toForm(); submit(); compose.waitUntil(5_000) { exists("reset_mfa") }
                    compose.onNodeWithTag("reset_cancel").performScrollTo().performClick(); compose.onNodeWithTag("reset_stop").performClick()
                    compose.waitUntil(5_000) { exists("reset_cancel_unknown") }; capture("reset-$label-$name-cancel-unknown") }
            }
        }
    }

    private fun toForm() {
        compose.onNodeWithTag("sign_in_username").performTextReplacement("Viewer")
        compose.onNodeWithTag("forgot_password").performScrollTo().performClick()
        compose.waitUntil(5_000) { exists("reset_form") }
    }

    private fun submit() {
        compose.onNodeWithTag("reset_key").performScrollTo().performTextReplacement(key)
        compose.onNodeWithTag("reset_password").performScrollTo().performTextReplacement("a new fixture password")
        compose.onNodeWithTag("reset_confirmation").performScrollTo().performTextReplacement("a new fixture password")
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { exists("reset_submit") }
        compose.onNodeWithTag("reset_submit").performClick()
    }

    private fun scene(dark: Boolean, fontScale: Float, mfa: List<String>? = null,
        reply: ((SessionState) -> SessionState)? = null, cancel: ((SessionState) -> SessionState)? = null, drive: () -> Unit) {
        FontScaleTestActivity.requestedFontScale = fontScale
        val sessions = TestSessions().apply {
            resetSupport = ResetSupport.SUPPORTED
            nextConnect = { it.copy(installationName = "The Common Room") }
            nextReset = reply ?: mfa?.let { methods -> { s: SessionState ->
                s.copy(reset = s.reset.copy(pending = PendingReset(methods, "2026-11-18T10:05:00+00:00"))) } }
            nextCancel = cancel
        }
        val auth = AuthViewModel(sessions)
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
                activity.setContent { Box(Modifier.fillMaxSize().testTag("reset_host")) { TestChitChatApp(settings, auth) } }
            }
            compose.waitUntil(5_000) { !settings.state.value.loading && exists("onboarding_sign_in") }
            compose.onNodeWithTag("onboarding_sign_in").performScrollTo().performClick()
            compose.waitUntil(5_000) { exists("forgot_password") }
            drive()
        }
    }

    private fun capture(name: String) = save(name, compose.onNodeWithTag("reset_host"))
    private fun captureDialog(name: String) = save(name, compose.onNode(isDialog()))

    private fun save(name: String, node: SemanticsNodeInteraction) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-reset-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
