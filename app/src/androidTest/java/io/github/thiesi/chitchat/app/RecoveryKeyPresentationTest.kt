package io.github.thiesi.chitchat.app

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.RecoveryKey
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The key display (O4a2a) for J, at a real Configuration font scale, light and dark: the display (the key card wrapping
 * at 200%), the "Saved your recovery key?" dialog, and the API ≤32 clipboard line. FLAG_SECURE would blank a pixel
 * capture, so these captures run without an activity (`LocalActivity` provides null); RecoveryKeyJourneyTest asserts the
 * flag on the real window.
 */
class RecoveryKeyPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val view = RecoveryKeyView(RecoveryKey("A7K2-B9QX-C4MZ-D8RT-E2WN-F6HP"), R.string.recovery_key_title_save,
        createdAt = "2026-11-16T10:00:00+00:00")

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    activity.setContent { ChitChatTheme(dark = dark) { CompositionLocalProvider(LocalActivity provides null) {
                        Box(Modifier.fillMaxSize().testTag("recovery_host")) {
                            RecoveryKeyDisplay(view, installation = "The Common Room", host = "chat.example.test", username = "Viewer",
                                onSaved = {}, sdkInt = if (!dark && fontScale == 1f) 32 else 35)
                        }
                    } } }
                }
                compose.onNodeWithTag("recovery_key_group_6").assertExists()
                capture(if (!dark && fontScale == 1f) "recovery-$label-$name-display-api32" else "recovery-$label-$name-display", "recovery_host")
                compose.onNodeWithTag("recovery_key_continue").performScrollTo().assertIsDisplayed() // Reachable by scrolling (A-04).
                capture("recovery-$label-$name-display-end", "recovery_host")
                if (fontScale == 1f) {
                    compose.onNodeWithTag("recovery_key_continue").performClick()
                    compose.onNodeWithTag("recovery_key_saved").assertExists()
                    capture("recovery-$label-$name-dialog", null)
                }
            }
        }
    }

    private fun capture(name: String, node: String?) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-recovery-presentation")
        check(folder.isDirectory || folder.mkdirs())
        // The dialog is its own window: capture its root.
        val target = if (node != null) compose.onNodeWithTag(node) else compose.onNode(isDialog())
        val bitmap = target.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
