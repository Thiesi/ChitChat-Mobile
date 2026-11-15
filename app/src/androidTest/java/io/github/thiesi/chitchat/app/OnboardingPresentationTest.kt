package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Onboarding (O2a) at a real Configuration font scale, light and dark: Welcome, Where do you chat? and Server found,
 * each scrolled to its primary action (A-04), with captures. No network.
 */
class OnboardingPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    @Test fun presentationLight() = presentation(ThemePreference.LIGHT)
    @Test fun presentationDark() = presentation(ThemePreference.DARK)

    private fun presentation(theme: ThemePreference) {
        val label = if (theme == ThemePreference.DARK) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            val sessions = TestSessions().apply {
                registrationEnabled = true
                nextConnect = { it.copy(public = it.public!!.copy(accessibilityFeedback = "https://chitchat.example/a11y")) }
            }
            val settings = SettingsViewModel(object : SettingsStore {
                override val settings = MutableStateFlow(AppSettings(theme = theme))
                override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
                override suspend fun savePureBlack(enabled: Boolean) = Unit
                override suspend fun saveTheme(theme: ThemePreference) = Unit
            })
            val auth = AuthViewModel(sessions)
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    activity.setContent { Box(Modifier.fillMaxSize().testTag("onboarding_host")) { TestChitChatApp(settings, auth) } }
                }
                compose.waitUntil(5_000) { !settings.state.value.loading }
                compose.onNodeWithTag("onboarding_welcome_start").performScrollTo().assertIsDisplayed()
                capture("onboarding-$label-$name-welcome")
                compose.onNodeWithTag("onboarding_welcome_start").performClick()
                compose.onNodeWithTag("onboarding_server_input").performTextReplacement("chat.example.test")
                Espresso.closeSoftKeyboard()
                compose.onNodeWithTag("onboarding_continue").performScrollTo().assertIsDisplayed()
                capture("onboarding-$label-$name-address")
                compose.onNodeWithTag("onboarding_continue").performClick()
                compose.onNodeWithTag("onboarding_server_card").assertIsDisplayed()
                capture("onboarding-$label-$name-foundtop") // Unscrolled: the server card (J3). Host-only fixture.
                for (tag in listOf("onboarding_create", "onboarding_sign_in", "onboarding_restore", "onboarding_other_server", "a11y_feedback"))
                    compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("onboarding_create").performScrollTo()
                capture("onboarding-$label-$name-found")
                if (fontScale == 2f) { // A step 2 failure notice at 200% (J3).
                    sessions.nextConnect = { it.copy(phase = SessionPhase.INVALIDATED, error = ApiFailure(FailureKind.TLS)) }
                    compose.onNodeWithTag("onboarding_other_server").performScrollTo().performClick()
                    // A different host: saving the same address again starts no new connect.
                    compose.onNodeWithTag("onboarding_server_input").performTextReplacement("other.example.test")
                    Espresso.closeSoftKeyboard()
                    compose.onNodeWithTag("onboarding_continue").performScrollTo().performClick()
                    compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext
                        .getString(io.github.thiesi.chitchat.R.string.onboarding_tls, "other.example.test")).assertExists()
                    compose.onNodeWithTag("onboarding_continue").performScrollTo().assertIsDisplayed()
                    capture("onboarding-$label-$name-failure")
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-onboarding-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("onboarding_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
