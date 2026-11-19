package io.github.thiesi.chitchat.feature.auth

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.height
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Synthetic step-up dialogs for the presentation lead's J review: each caller's own title (export, Replace key, Create
 * key, room transfer) with the password explanation, and the recovery-code warning, at default and 200%, light and dark.
 * No network; the real dialog. Which caller passes which title is pinned by StepUpTitleContractTest and the journeys.
 */
class StepUpPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val titles = mapOf("export" to R.string.step_up_title_export,
        "replace" to R.string.step_up_title_replace_recovery_key, "create" to R.string.step_up_title_create_recovery_key,
        "transfer" to R.string.rooms_transfer_step_up_title, "recovery" to R.string.step_up_title_export)
    private val password = VerificationMethods(false, true, 0)
    private val codes = VerificationMethods(true, false, 6)

    @Test fun presentationLight() = presentation(dark = false)
    @Test fun presentationDark() = presentation(dark = true)

    private fun presentation(dark: Boolean) {
        val label = if (dark) "dark" else "light"
        val heights = mutableMapOf<String, Float>()
        for ((name, fontScale) in listOf("default" to 1f, "font200" to 2f)) {
            FontScaleTestActivity.requestedFontScale = fontScale
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                var scene by mutableStateOf(titles.keys.first())
                scenario.onActivity { activity ->
                    assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                    assertEquals("The platform density is untouched",
                        InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.densityDpi,
                        activity.resources.configuration.densityDpi)
                    activity.setContent { ChitChatTheme(dark = dark) { Scene(scene) } }
                }
                for (each in titles.keys) {
                    scene = each; compose.waitForIdle()
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    if (each == "recovery") compose.onNode(hasText(context.getString(R.string.choose_emergency_recovery)) and
                        hasAnyAncestor(isDialog())).performScrollTo().performClick() // At 200% it sits below the fold.
                    val title = compose.onNode(hasText(context.getString(titles.getValue(each))) and hasAnyAncestor(isDialog()))
                    val height = title.getUnclippedBoundsInRoot().height.value
                    if (fontScale == 1f) heights[each] = height
                    else assertTrue("$label/$name/$each: the 2.0 font scale reached the dialog " +
                        "(default ${heights.getValue(each)}dp, now ${height}dp)", height > heights.getValue(each))
                    // The recovery scene is the chosen state, captured with its field in view (TL, #297).
                    if (each == "recovery") compose.onNode(hasText(context.getString(R.string.recovery_code)) and
                        hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
                    capture("stepup-$label-$name-$each") // The top; for recovery, scrolled to its field.
                    // After the capture: the body's last line (the field) scrolls into view, and both buttons stay shown.
                    val last = context.getString(if (each == "recovery") R.string.recovery_code else R.string.current_password)
                    try {
                        compose.onNode(hasText(last) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
                        for (button in listOf(R.string.verify_and_continue, R.string.cancel))
                            compose.onNode(hasText(context.getString(button)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
                    } catch (failure: AssertionError) { throw AssertionError("$label/$name/$each: field or buttons not reachable", failure) }
                }
            }
        }
    }

    @Composable
    private fun Scene(scene: String) {
        // A fresh dialog per scene, so the recovery choice never carries over.
        androidx.compose.runtime.key(scene) {
            StepUpDialog(stringResource(titles.getValue(scene)), if (scene == "recovery") codes else password, busy = false,
                errorMessage = null, onVerify = { _, _ -> }, onCancel = {})
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-step-up-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
