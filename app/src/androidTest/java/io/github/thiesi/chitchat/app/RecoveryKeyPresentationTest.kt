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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
 * at 200%), the "Saved your recovery key?" dialog, and the actions side by side at default or stacked at 200%. Light runs
 * as API 32 (the clipboard line), dark as API 35. FLAG_SECURE would blank a pixel
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
                                onSaved = {}, sdkInt = if (!dark) 32 else 35)
                        }
                    } } }
                }
                compose.onNodeWithTag("recovery_key_group_6").assertExists()
                // The top of the display first: measuring the touch targets below scrolls them on screen.
                capture(if (!dark) "recovery-$label-$name-display-api32" else "recovery-$label-$name-display", "recovery_host")
                // J1: side by side at default (both fit), stacked at 200% (they don't). Deterministic for this window.
                val copy = compose.onNodeWithTag("recovery_key_copy").getUnclippedBoundsInRoot()
                val save = compose.onNodeWithTag("recovery_key_save_file").getUnclippedBoundsInRoot()
                val file = compose.onNodeWithTag("recovery_key_file_line").getUnclippedBoundsInRoot()
                val clip = if (!dark) compose.onNodeWithTag("recovery_key_clipboard_line").getUnclippedBoundsInRoot() else null
                // The 48dp floor is the touch target (Material's minimumInteractiveComponentSize), not the drawn height.
                val copyTouch = touchHeight("recovery_key_copy"); val saveTouch = touchHeight("recovery_key_save_file")
                org.junit.Assert.assertTrue("48dp touch targets: copy layout=${copy.height} touch=$copyTouch, " +
                    "save layout=${save.height} touch=$saveTouch", copyTouch >= 48.dp && saveTouch >= 48.dp)
                if (fontScale == 1f) {
                    assertEquals("Copy and Save share a row at default", copy.top, save.top)
                    // Both lines under the row, in button order.
                    val rowBottom = maxOf(copy.bottom, save.bottom)
                    clip?.let { org.junit.Assert.assertTrue("Clipboard line under the row", it.top >= rowBottom) }
                    org.junit.Assert.assertTrue("File line under the row, after the clipboard line", file.top >= (clip?.bottom ?: rowBottom))
                } else {
                    org.junit.Assert.assertTrue("Save stacks below Copy at 200%", save.top >= copy.bottom)
                    // Each line directly under its own button: the clipboard line between Copy and Save.
                    clip?.let { org.junit.Assert.assertTrue("Clipboard line under Copy, above Save", it.top >= copy.bottom && it.bottom <= save.top) }
                    org.junit.Assert.assertTrue("The file line sits under its own button", file.top >= save.bottom)
                }
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

    /** Touch bounds are clipped to the visible area, so the target is scrolled on screen first, as a person reaches it. */
    private fun touchHeight(tag: String): androidx.compose.ui.unit.Dp {
        val node = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
        return with(node.layoutInfo.density) { node.touchBoundsInRoot.height.toDp() }
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
