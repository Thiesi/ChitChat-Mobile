package io.github.thiesi.chitchat.core.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.feature.auth.StepUpDialog
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.tapNativeInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * The form-dialog scaffold (P-220 amendment; PL and TL of 2026-11-21): with the keyboard up, the buttons and the focused
 * field sit above it, read against the dialog window's own IME inset, never only "displayed" in the app window. Pinned
 * while the body keeps 120dp; one column below that. Each case records its measurements for the J record.
 */
class FormDialogGeometryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    private fun host(fontScale: Float, landscape: Boolean, content: @Composable () -> Unit, block: () -> Unit) {
        FontScaleTestActivity.requestedFontScale = fontScale
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            if (landscape) {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(5_000) {
                    var orientation = 0
                    scenario.onActivity { orientation = it.resources.configuration.orientation }
                    orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            }
            scenario.onActivity { it.setContent { ChitChatTheme(dark = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            } } }
            compose.waitForIdle()
            block()
        }
    }

    /** Scrolls a node into view when it sits in a scroller (an AlertDialog's buttons don't); otherwise leaves it. */
    private fun SemanticsNodeInteraction.scrollIfScrollable() {
        var parent = fetchSemanticsNode().parent
        while (parent != null && SemanticsActions.ScrollBy !in parent.config) parent = parent.parent
        if (parent != null) performScrollTo()
    }

    private fun mode() = if (present("form_dialog_pinned")) "pinned" else "column"

    /**
     * One native tap on the field, then typing; the field must be focused and above the keyboard. Then each button is
     * scrolled to (one column may hold it below the fold; focus stays in the field) and must end at or above the IME top,
     * read from the dialog window's own IME inset. Every case writes its numbers for the J record.
     */
    private fun aboveTheKeyboard(case: String, field: SemanticsNodeInteraction, fieldTag: String, send: SemanticsNodeInteraction,
        cancel: SemanticsNodeInteraction, typed: String, fieldTopOnly: Boolean = false) {
        val before = mode()
        field.scrollIfScrollable()
        compose.tapNativeInput(fieldTag, imeBottom = { focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } })
        field.performTextInput(typed)
        compose.waitForIdle()
        val ime = focusedWindowIme()
        val windowHeight = ime.height; val imeBottom = ime.imeBottom; val imeTop = ime.imeTop
        val fieldBox = field.fetchSemanticsNode().boundsInWindow
        val after = mode()
        send.scrollIfScrollable(); val sendBox = send.fetchSemanticsNode().boundsInWindow
        cancel.scrollIfScrollable(); val cancelBox = cancel.fetchSemanticsNode().boundsInWindow
        val density = context.resources.displayMetrics.density
        fun dp(px: Float) = "%.1f".format(Locale.ROOT, px / density)
        recordFormDialogMetrics("case=$case modeImeHidden=$before modeImeUp=$after window=${windowHeight}px imeVisible=${ime.visible} imeBottom=${imeBottom}px " +
            "imeTop=${imeTop}px/${dp(imeTop.toFloat())}dp sendBottom=${sendBox.bottom}px/${dp(sendBox.bottom)}dp " +
            "cancelBottom=${cancelBox.bottom}px/${dp(cancelBox.bottom)}dp fieldTop=${fieldBox.top}px/${dp(fieldBox.top)}dp " +
            "fieldBottom=${fieldBox.bottom}px/${dp(fieldBox.bottom)}dp")
        val detail = "$case: imeTop=$imeTop send=$sendBox cancel=$cancelBox field=$fieldBox"
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true")
            assertTrue("The keyboard showed ($detail)", ime.visible)
        val target = 40 * density // A collapsed (clipped-away) node would pass the edge checks vacuously.
        assertTrue("Send is whole ($detail)", sendBox.height >= target)
        assertTrue("Cancel is whole ($detail)", cancelBox.height >= target)
        assertTrue("Send above the keyboard ($detail)", sendBox.bottom <= imeTop + 0.5f)
        assertTrue("Cancel above the keyboard ($detail)", cancelBox.bottom <= imeTop + 0.5f)
        assertTrue("The focused field above the keyboard ($detail)",
            if (fieldTopOnly) fieldBox.top < imeTop && fieldBox.height > 0f else fieldBox.bottom <= imeTop + 0.5f)
        field.assertIsFocused()
    }

    private fun stepUp(fontScale: Float, landscape: Boolean, case: String) = host(fontScale, landscape, {
        StepUpDialog("Confirm it's you", VerificationMethods(false, true, 0), busy = false, errorMessage = null,
            onVerify = { _, _ -> }, onCancel = {})
    }) {
        aboveTheKeyboard(case, compose.onNodeWithTag("step_up_secret"), "step_up_secret",
            compose.onNode(hasText(context.getString(R.string.verify_and_continue)) and hasAnyAncestor(isDialog())),
            compose.onNode(hasText(context.getString(R.string.cancel)) and hasAnyAncestor(isDialog())), "fixture-password")
    }

    private fun fixture(case: String, typed: String = "why") = aboveTheKeyboard(case, compose.onNodeWithTag("fixture_details"),
        "fixture_details", compose.onNodeWithTag("fixture_send"), compose.onNodeWithTag("fixture_cancel"), typed, fieldTopOnly = true)

    /**
     * PL (2026-11-22): a native tap outside StepUp's surface does nothing, with a typed password or recovery code: the
     * dialog stays, its field keeps the text and nothing is verified. Back (password) and Cancel (recovery) still cancel.
     */
    @Test fun anOutsideTapLeavesAFilledStepUpAlone() {
        for (recovery in listOf(false, true)) {
            var cancelled = 0
            var verified = 0
            host(1f, landscape = true, {
                StepUpDialog("Confirm it's you", if (recovery) VerificationMethods(true, true, 8) else VerificationMethods(false, true, 0),
                    busy = false, errorMessage = null, onVerify = { _, _ -> verified++ }, onCancel = { cancelled++ })
            }) {
                if (recovery) compose.onNode(hasText(context.getString(R.string.choose_emergency_recovery)) and hasAnyAncestor(isDialog()))
                    .performScrollTo().performClick()
                val typed = if (recovery) "UNUSED-FIXTURE-CODE" else "fixture-password"
                // Exactly the typed text, so a stray keystroke fails (run 45042310286: an outside tap computed from bounds read
                // before the keyboard moved the dialog landed on the keyboard and typed a "w").
                fun intact() = compose.onNodeWithTag("step_up_secret").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    SemanticsProperties.InputText, androidx.compose.ui.text.AnnotatedString(typed)))
                compose.onNodeWithTag("step_up_secret").performScrollTo().performTextInput(typed)
                // The keyboard goes first: in landscape it covers most of the window, so a tap "outside" with it up can land
                // on it. Closing it isn't a cancel (Back with the keyboard up closes only the keyboard, the platform's Back).
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
                assertEquals("recovery=$recovery: closing the keyboard doesn't cancel", 0, cancelled)
                intact()
                // With no keyboard, any point in the window's left margin is outside the centred surface, on the dialog's
                // own area. The mode column fills the surface (its padding scrolls inside it).
                val surface = compose.onNode(hasTestTag("form_dialog_column") or hasTestTag("form_dialog_pinned"), useUnmergedTree = true)
                val point = tapOutsideSurface(surface)
                compose.waitForIdle()
                assertTappedDialogArea(point, surface)
                assertEquals("recovery=$recovery: an outside tap doesn't cancel", 0, cancelled)
                assertEquals("recovery=$recovery: nothing verified", 0, verified)
                assertTrue("recovery=$recovery: the dialog stays", compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
                intact()
                // Back, or Cancel by its action (this half checks that Cancel cancels, not where a tap lands), cancels.
                if (recovery) compose.onNode(hasText(context.getString(R.string.cancel)) and hasAnyAncestor(isDialog()))
                    .performSemanticsAction(SemanticsActions.OnClick)
                else androidx.test.espresso.Espresso.pressBack()
                compose.waitForIdle()
                assertEquals("recovery=$recovery: ${if (recovery) "Cancel" else "Back"} still cancels", 1, cancelled)
            }
        }
    }

    /**
     * TL (2026-11-22): the hold hook. With a focused field scrolled out of view, a layout change (the keyboard closing)
     * doesn't scroll back to it while the hold is on; released, the next layout change (the keyboard showing) does, as
     * without a hold. Off by default: every other case here runs without it.
     */
    @Test fun theHoldSuppressesTheFocusedFieldScrollUntilReleased() {
        var hold by androidx.compose.runtime.mutableStateOf(false)
        var keyboard: androidx.compose.ui.platform.SoftwareKeyboardController? = null
        host(2f, landscape = false, {
            FormDialog(onDismissRequest = {}, title = { androidx.compose.material3.Text("Hold") },
                confirmButton = { androidx.compose.material3.TextButton(onClick = {}) { androidx.compose.material3.Text("OK") } },
                holdFocusedInView = hold) {
                // The dialog's own controller: the activity's would target the activity window, not the dialog's field
                // (run 45075992645).
                keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                androidx.compose.material3.Text("The top line", androidx.compose.ui.Modifier.testTag("hold_top"))
                repeat(10) { androidx.compose.material3.Text("A line of body text that takes room, number $it") }
                var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                androidx.compose.material3.OutlinedTextField(text, { text = it },
                    androidx.compose.ui.Modifier.testTag("hold_field"))
            }
        }) {
            compose.onNodeWithTag("hold_field").performScrollTo()
            compose.tapNativeInput("hold_field", imeBottom = { focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } })
            compose.onNodeWithTag("hold_top").performScrollTo()
            compose.waitForIdle()
            fun visible(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.height
            assertTrue("The top line is in view before the hold", visible("hold_top") > 0f)
            compose.runOnIdle { hold = true }
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            val held = "top=${visible("hold_top")} field=${visible("hold_field")}"
            recordFormDialogMetrics("case=hold-held $held")
            assertTrue("Held: the keyboard closing didn't scroll back to the field ($held)", visible("hold_top") > 0f)
            compose.runOnIdle { hold = false; keyboard?.show() }
            compose.waitUntil(5_000) { focusedWindowIme().visible }
            compose.waitForIdle()
            val released = "top=${visible("hold_top")} field=${visible("hold_field")}"
            recordFormDialogMetrics("case=hold-released $released")
            assertTrue("Released: the keyboard showing scrolled back to the field ($released)", visible("hold_field") > 0f)
            compose.onNodeWithTag("hold_field").assertIsFocused()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
        }
    }

    @Test fun stepUpAtDefaultSize() = stepUp(1f, landscape = false, "stepup-default-portrait")
    @Test fun stepUpAt200PercentPortrait() = stepUp(2f, landscape = false, "stepup-font200-portrait")
    @Test fun stepUpAt200PercentLandscape() = stepUp(2f, landscape = true, "stepup-font200-landscape")

    @Test fun reportShapedFormAtDefaultSize() = host(1f, false, { ReportShapedFormDialog() }) { fixture("fixture-default-portrait") }
    @Test fun reportShapedFormAt200PercentPortrait() = host(2f, false, { ReportShapedFormDialog() }) { fixture("fixture-font200-portrait") }
    @Test fun reportShapedFormAt200PercentLandscape() = host(2f, true, { ReportShapedFormDialog() }) { fixture("fixture-font200-landscape") }

    /** TL: with the keyboard up at 200% portrait, a six-line entry keeps its caret line in view above the IME. */
    @Test fun aSixLineEntryKeepsItsCaretAboveTheKeyboard() = host(2f, false, { ReportShapedFormDialog() }) {
        fixture("fixture-font200-portrait-6lines", (1..6).joinToString("\n") { "line $it" })
        compose.onNodeWithTag("fixture_details").performScrollTo() // Back from the buttons; the caret must show.
        compose.waitForIdle()
        val ime = focusedWindowIme()
        val windowHeight = ime.height; val imeBottom = ime.imeBottom
        val field = compose.onNodeWithTag("fixture_details").fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        compose.runOnIdle { field.config[SemanticsActions.GetTextLayoutResult].action!!(results) }
        val selection = field.config[SemanticsProperties.TextSelectionRange]
        val caret: Rect = results.single().getCursorRect(selection.end)
        val padding = 16 * context.resources.displayMetrics.density
        val caretBottom = field.boundsInWindow.top + padding + caret.bottom
        recordFormDialogMetrics("case=fixture-font200-portrait-6lines-caret caretBottom=${caretBottom}px imeTop=${windowHeight - imeBottom}px")
        assertTrue("The caret line above the keyboard (caret=$caretBottom, imeTop=${windowHeight - imeBottom})",
            caretBottom <= windowHeight - imeBottom + 0.5f)
        compose.onNodeWithTag("fixture_details").assertIsFocused()
    }

    /**
     * Fit-today, IME hidden, default size: the scaffold lays out as M3's AlertDialog does (same surface size, title,
     * body and button positions within 1px), so dialogs that fit don't change.
     */
    @Test fun aFittingDialogMatchesTheAlertDialog() {
        var formBoxes = emptyList<Rect>()
        var alertBoxes = emptyList<Rect>()
        fun boxes(): List<Rect> {
            val dialog = compose.onNodeWithTag("compare_dialog", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            return listOf("compare_dialog", "compare_title", "compare_body", "compare_field", "compare_cancel", "compare_confirm").map { tag ->
                compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.translate(-dialog.left, -dialog.top)
            }
        }
        host(1f, false, { CompareDialog(form = true) }) { formBoxes = boxes() }
        host(1f, false, { CompareDialog(form = false) }) { alertBoxes = boxes() }
        val metrics = context.resources.displayMetrics
        recordFormDialogMetrics("case=fit-compare display=${metrics.widthPixels}x${metrics.heightPixels}px density=${metrics.density} form=${formBoxes.joinToString(";") { it.toString() }} " +
            "alert=${alertBoxes.joinToString(";") { it.toString() }}")
        formBoxes.zip(alertBoxes).forEachIndexed { index, (form, alert) ->
            for ((a, b) in listOf(form.left to alert.left, form.top to alert.top, form.right to alert.right, form.bottom to alert.bottom))
                assertTrue("Slot $index: form $form, alert $alert", abs(a - b) <= 1f)
        }
    }

    /** Fit-today, IME hidden: the pinned layout; a short landscape window: one column (the measurement decides). */
    @Test fun theModeFollowsTheMeasuredBody() {
        host(1f, false, { ReportShapedFormDialog() }) { assertTrue("Default portrait stays pinned", present("form_dialog_pinned")) }
        host(2f, false, { ReportShapedFormDialog() }) { assertTrue("200% portrait stays pinned with the keyboard hidden", present("form_dialog_pinned")) }
        host(2f, true, { ReportShapedFormDialog() }) { assertTrue("200% landscape becomes one column", present("form_dialog_column")) }
    }
}
