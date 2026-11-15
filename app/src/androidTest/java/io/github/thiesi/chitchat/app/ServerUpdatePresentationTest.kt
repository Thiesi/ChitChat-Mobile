package io.github.thiesi.chitchat.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** "This server needs an update" at setup and on revalidation, at a real Configuration font scale: captures, and every action reachable by scrolling. No network. */
class ServerUpdatePresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val setup = SessionState(server = server, generation = 1, phase = SessionPhase.ANONYMOUS, updateRequired = true,
        reportedVersion = "3.9.0", installationName = "The Common Room")
    private val signedIn = SessionState(server = server, generation = 1, phase = SessionPhase.AUTHENTICATED, updateRequired = true,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false))

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            for (scene in listOf("setup", "session")) {
                FontScaleTestActivity.requestedFontScale = fontScale
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                        activity.setContent { ChitChatTheme(dark = dark) {
                            if (scene == "setup") androidx.compose.material3.Surface(Modifier.fillMaxSize(),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("update_host")) {
                                    ServerUpdateNotice(setup, server.value) {}
                                }
                            } else Column(Modifier.fillMaxSize().testTag("update_host")) {
                                ServerUpdateScreen(signedIn, server.value, onCheckAgain = {}, onSignOut = {})
                            }
                        } }
                    }
                    capture("update-$label-$name-$scene")
                    // A-floor: every action is reachable by scrolling at both scales, not only on a tall window.
                    val actions = if (scene == "setup") listOf("server_update_copy", "server_update_other")
                        else listOf("server_update_copy", "server_update_check", "server_update_sign_out")
                    for (tag in actions) compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                    capture("update-$label-$name-$scene-actions") // Scrolled to the last action.
                }
            }
        }
    }

    /** J1 on PR259: in dark and Pure black the screen paints the Lantern ground, and its body uses the theme's text colour. */
    @Test fun theRevalidationScreenPaintsTheShellSurfaceInDarkAndPureBlack() {
        for ((palette, pureBlack) in listOf(io.github.thiesi.chitchat.core.theme.Lantern.dark to false,
            io.github.thiesi.chitchat.core.theme.Lantern.amoledBlack to true)) {
            FontScaleTestActivity.requestedFontScale = 1f
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { ChitChatTheme(dark = true, pureBlack = pureBlack) {
                    ServerUpdateScreen(signedIn, server.value, onCheckAgain = {}, onSignOut = {})
                } } }
                compose.waitForIdle()
                val pixels = compose.onNodeWithTag("server_update_screen").captureToImage().toPixelMap()
                assertEquals("pureBlack=$pureBlack: the ground", palette.ground.toArgb(), pixels[1, 1].toArgb())
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag("server_update_body").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertEquals("pureBlack=$pureBlack: the body's text colour", palette.text, layouts.single().layoutInput.style.color)
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-update-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("update_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
