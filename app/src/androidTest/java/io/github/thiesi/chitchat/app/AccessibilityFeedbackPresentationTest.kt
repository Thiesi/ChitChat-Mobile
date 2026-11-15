package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.auth.AuthScreen
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * P-221: "Accessibility feedback" on Sign in (signed out) and in You's About and privacy, only when the server's
 * public session has a validated address, opening through an explicit choice that names the destination.
 * Real Configuration font scale (default and 2.0), light and dark; nothing is launched. No network.
 */
class AccessibilityFeedbackPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private fun public(url: String?) = PublicSession(true, emptyList(), Lockdown(false), url)
    private fun anonymous(url: String?) = SessionState(server = server, generation = 1, phase = SessionPhase.ANONYMOUS, public = public(url))
    private fun member(url: String?) = SessionState(server = server, generation = 1, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false), public = public(url))
    private fun text(id: Int, vararg args: Any) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    @Test fun absentWithoutAnAddressAndAMailAddressOffersTheMailApp() {
        FontScaleTestActivity.requestedFontScale = 1f
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            var state by mutableStateOf(anonymous(null))
            scenario.onActivity { activity -> val auth = AuthViewModel(TestSessions())
                activity.setContent { ChitChatTheme(dark = false) { Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (state.phase == SessionPhase.ANONYMOUS) AuthScreen(state, auth)
                    else YouScreen(state, auth, server.value, false, {}, {}, {}, {}, {})
                } } } }
            compose.onNodeWithTag("a11y_feedback").assertDoesNotExist() // Signed out, no field: no button, no explanation.
            state = member(null); compose.waitForIdle()
            compose.onNodeWithTag("a11y_feedback").assertDoesNotExist() // You: no row, no placeholder.
            state = member("mailto:access@example.org"); compose.waitForIdle()
            compose.onNodeWithTag("a11y_feedback").performScrollTo().performClick()
            compose.onNode(hasText(text(R.string.a11y_feedback_open_mail))).assertExists()
            compose.onNode(hasText(text(R.string.a11y_feedback_mail, "access@example.org"))).assertExists()
        }
    }

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                var state by mutableStateOf(anonymous("https://chitchat.example/accessibility"))
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    val auth = AuthViewModel(TestSessions())
                    activity.setContent { ChitChatTheme(dark = dark) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("a11y_host")) {
                            if (state.phase == SessionPhase.ANONYMOUS) AuthScreen(state, auth)
                            else YouScreen(state, auth, server.value, false, {}, {}, {}, {}, {})
                        }
                    } }
                }
                compose.onNodeWithTag("a11y_feedback").performScrollTo()
                capture("a11y-$label-$name-signin", compose.onNodeWithTag("a11y_host"))
                state = member("https://chitchat.example/accessibility"); compose.waitForIdle()
                compose.onNodeWithTag("a11y_feedback").performScrollTo()
                capture("a11y-$label-$name-you", compose.onNodeWithTag("you_about"))
                compose.onNodeWithTag("a11y_feedback").performClick()
                compose.onNode(hasText(text(R.string.a11y_feedback_web, "chitchat.example"))).assertExists()
                compose.onNodeWithTag("a11y_feedback_open").assertExists()
                capture("a11y-$label-$name-dialog", compose.onNode(isDialog()))
            }
        }
    }

    private fun capture(name: String, node: SemanticsNodeInteraction) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-a11y-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
