package io.github.thiesi.chitchat.feature.push

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.height
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.push.PushDistributor
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P-220 sweep A: the six push dialogs, rendered from their real composables at a real Configuration font scale
 * (default and 2.0) on the AVD's actual window, light and dark. No network.
 */
class PushDialogPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val distributors = listOf("ntfy", "Sunup", "NextPush", "Conversations", "Fixture distributor", "Another fixture app")
        .mapIndexed { index, label -> PushDistributor("org.fixture.distributor$index", label) }
    // Each dialog and the tag of its scrolling text slot.
    private val dialogs = listOf("push-explain" to "push_explain_body", "push-choose" to "push_choose_body",
        "push-google" to "push_google_body", "push-none" to "push_none_body", "push-phone-off" to "push_phone_off_body",
        "push-remove" to "push_remove_body")

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                var dialog by mutableStateOf(dialogs.first().first)
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    assertEquals("The platform density is untouched",
                        InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.densityDpi,
                        activity.resources.configuration.densityDpi)
                    activity.setContent { ChitChatTheme(dark = dark) { Dialog(dialog) } }
                }
                for ((each, body) in dialogs) {
                    dialog = each; compose.waitForIdle()
                    val where = "$label/$name/$each"
                    if (each == "push-choose") for (d in distributors) {
                        val height = compose.onNodeWithTag("push_distributor:${d.packageName}").getUnclippedBoundsInRoot().height.value
                        assertTrue("$where: ${d.label} row is ${height}dp, expected at least 48dp", height >= 48f - 0.5f)
                    }
                    capture("dialog-$label-$name-$each") // The top, with the "more below" cue when it overflows.
                    assertBodyReachable(where, body)
                    if (each == "push-choose") try {
                        compose.onNodeWithTag("push_distributor:${distributors.last().packageName}").performScrollTo().assertIsDisplayed()
                    } catch (failure: AssertionError) { throw AssertionError("$where: the last choice is not reachable by scrolling", failure) }
                    if (each == "push-google") try { compose.onNodeWithTag("push_google_guide").performScrollTo().assertIsDisplayed() }
                        catch (failure: AssertionError) { throw AssertionError("$where: the guide link is not reachable by scrolling", failure) }
                }
            }
        }
    }

    /** With scroll range, the body's own ScrollBy reaches the end and comes back to the start (P-220). */
    private fun assertBodyReachable(where: String, tag: String) {
        val box = compose.onNodeWithTag(tag)
        val range = box.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return
        val max = range.maxValue()
        if (max <= 0f) return // It fits: nothing is hidden.
        box.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.waitForIdle()
        assertEquals("$where: the body scrolls to its end", max, range.value(), 0.5f)
        box.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -1_000_000f) }
        compose.waitForIdle()
        assertEquals("$where: the body scrolls back to its start", 0f, range.value(), 0.5f)
    }

    @Composable
    private fun Dialog(which: String) {
        when (which) {
            "push-explain" -> PushExplainDialog(busy = false, onContinue = {}, onCancel = {})
            "push-choose" -> PushChooseDialog(distributors, distributors[1], busy = false, onPick = {}, onConfirm = {}, onCancel = {})
            "push-google" -> PushUseGoogleDialog(busy = false, onGuide = {}, onUseGoogle = {}, onCancel = {})
            "push-none" -> PushNoDistributorDialog(onGuide = {}, onClose = {})
            "push-phone-off" -> PushPhoneOffDialog(onConfirm = {}, onCancel = {})
            "push-remove" -> PushRemoveDialog(currentSession = false, onConfirm = {}, onCancel = {})
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-dialog-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
