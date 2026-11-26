package io.github.thiesi.chitchat.feature.reports

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportFacts
import io.github.thiesi.chitchat.core.reports.ReportOperations
import io.github.thiesi.chitchat.core.reports.ReportResult
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ReceivedFiles
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.core.ui.ReceivedRooms
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.OnCreateContentTestActivity
import io.github.thiesi.chitchat.testing.tapNativeInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Reporting R2 (TL): the form keeps its fields across a real configuration change and holds nothing after process death,
 * and Send report and Cancel stay reachable with the keyboard up at 200%, portrait and landscape (the StepUpDialog
 * form-dialog geometry: a scrolling body above the keyboard, the buttons below it).
 */
class ReportFormLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = ReceivedFiles.session()
    private val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
    private val room = ConversationViewModel(rooms)
    private val operations = object : ReportOperations {
        override val session = this@ReportFormLayoutTest.session
        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?) = ReportResult.Sent(1)
        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
    }
    @Volatile private var imeBottom = 0

    @After fun tearDown() {
        FontScaleTestActivity.requestedFontScale = 1f; OnCreateContentTestActivity.content = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { room.cancel() }
    }

    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun report() {
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
        val label = context.getString(R.string.message_actions)
        compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
            hasAnyAncestor(hasTestTag("message:3"))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { present("message_report_action") }
        compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
        compose.waitUntil(5_000) { present("report_dialog") }
    }

    @Test fun aRealRecreationKeepsTheFormAndItsFields() {
        val reports = ReportViewModel(operations)
        OnCreateContentTestActivity.content = {
            MaterialTheme { CompositionLocalProvider(LocalReports provides reports) { ConversationScreen(room, 42, {}) } }
        }
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use { scenario ->
            report()
            compose.onNodeWithTag("report_category:${ReportCategory.HATE.wire}").performScrollTo().performClick()
            compose.onNodeWithTag("report_details").performScrollTo().performTextInput("kept why")
            // The caret stays at the end of the typed text (a value lagging behind the field reset it, TL).
            compose.onNodeWithTag("report_details").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(8)))
            val before = reports.state.value?.details
            scenario.recreate()
            compose.waitUntil(5_000) { present("report_dialog") }
            assertEquals("A configuration change keeps the category (TL)", ReportCategory.HATE, reports.state.value?.category)
            assertEquals("The VM held the details across it (before: $before)", "kept why", reports.state.value?.details)
            compose.onNodeWithTag("report_details").performScrollTo().assertTextEquals("kept why")
        }
        // Process death: a new process has a new model, and nothing comes back (the details were never saved).
        val fresh = ReportViewModel(operations)
        OnCreateContentTestActivity.content = {
            MaterialTheme { CompositionLocalProvider(LocalReports provides fresh) { ConversationScreen(room, 42, {}) } }
        }
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use {
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            assertTrue(!present("report_dialog"))
            assertEquals(null, fresh.state.value)
        }
    }

    /**
     * PL: while the room's name isn't known, the who-sees line and the sent line say "this room’s"; the wording is chosen
     * when the line shows, so each switches to the named line once the name arrives. Never an empty name.
     */
    @Test fun anUnknownRoomNameSaysThisRoomsUntilTheNameArrives() {
        val reports = ReportViewModel(operations)
        val origin = ReportOrigin.Room(42)
        var place by mutableStateOf<ReportPlace>(ReportPlace.Room(null))
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Column {
                        ReportNotices(origin, place)
                        ReportHost(origin, place, subject = { null }, followUp = { null })
                    } }
                } } }
            }
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals(ReportOpen.OPENED, reports.request(ReportTarget.RoomMessage(3), origin))
            }
            compose.waitUntil(5_000) { present("report_who") }
            compose.onNodeWithTag("report_who").assertTextEquals(context.getString(R.string.report_who_room_unnamed))
            place = ReportPlace.Room("Lounge")
            compose.waitForIdle()
            compose.onNodeWithTag("report_who").assertTextEquals(context.getString(R.string.report_who_room, "Lounge"))
            place = ReportPlace.Room(null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { reports.choose(ReportCategory.SPAM); reports.send() }
            compose.waitUntil(5_000) { present("report_outcome") }
            compose.onNodeWithTag("report_outcome").assertTextEquals(context.getString(R.string.report_sent_room_unnamed))
            place = ReportPlace.Room("Lounge")
            compose.waitForIdle()
            compose.onNodeWithTag("report_outcome").assertTextEquals(context.getString(R.string.report_sent_room, "Lounge"))
        }
    }

    /**
     * PL: a native tap outside the report form's surface does nothing; the chosen reason and the typed details stay.
     * Landscape leaves a side margin outside the 320dp surface to tap.
     */
    @Test fun anOutsideTapLeavesTheReportFormAndItsFieldsAlone() {
        val reports = ReportViewModel(operations)
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5_000) {
                var orientation = 0
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                } } }
            }
            report()
            compose.onNodeWithTag("report_category:${ReportCategory.SPAM.wire}").performScrollTo().performClick()
            compose.onNodeWithTag("report_details").performScrollTo().performTextInput("kept why")
            compose.waitForIdle()
            io.github.thiesi.chitchat.core.ui.tapOutsideSurface(compose.onNodeWithTag("report_dialog", useUnmergedTree = true))
            compose.waitForIdle()
            assertTrue("The form stays open", present("report_dialog"))
            assertEquals(ReportCategory.SPAM, reports.state.value?.category)
            assertEquals("kept why", reports.state.value?.details)
        }
    }

    /** R3c (PL): a person report's form ignores an outside tap too, while editing and in its Unknown summary. */
    @Test fun anOutsideTapLeavesAPersonReportAloneInEveryPhase() {
        val reports = ReportViewModel(object : ReportOperations {
            override val session = this@ReportFormLayoutTest.session
            override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?) =
                ReportResult.Unknown(io.github.thiesi.chitchat.core.network.ApiFailure(io.github.thiesi.chitchat.core.network.FailureKind.TIMEOUT))
            override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
        })
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5_000) {
                var orientation = 0
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                } } }
            }
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            compose.runOnIdle { reports.requestPerson(ReportTarget.Person(8, 42), ReportOrigin.Room(42), true, "Bob", reports.settled) }
            compose.waitUntil(5_000) { present("report_dialog") }
            compose.onNodeWithTag("report_category:${ReportCategory.OFFENSIVE_USERNAME.wire}").performScrollTo().performClick()
            compose.onNodeWithTag("report_details").performScrollTo().performTextInput("kept why")
            compose.waitForIdle()
            io.github.thiesi.chitchat.core.ui.tapOutsideSurface(compose.onNodeWithTag("report_dialog", useUnmergedTree = true))
            compose.waitForIdle()
            assertTrue("Editing: the form stays open", present("report_dialog"))
            assertEquals("kept why", reports.state.value?.details)
            compose.onNodeWithTag("report_send").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(5_000) { present("report_check") }
            io.github.thiesi.chitchat.core.ui.tapOutsideSurface(compose.onNodeWithTag("report_dialog", useUnmergedTree = true))
            compose.waitForIdle()
            assertTrue("Unknown: the summary stays", present("report_check"))
            assertTrue(reports.state.value?.phase is ReportPhase.Unknown)
        }
    }

    /**
     * PL and TL: the details question is a separate label above the field. At 200% its bounds touch neither the last
     * category row (with its helper) above nor the field below. The question is exposed exactly once, as the label's own
     * text immediately before the field, which carries no contentDescription (Compose drops it on a text field).
     */
    @Test fun theDetailsQuestionIsALabelAboveTheFieldAnnouncedOnce() {
        val reports = ReportViewModel(operations)
        FontScaleTestActivity.requestedFontScale = 2f
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                } } }
            }
            report()
            compose.onNodeWithTag("report_details").performScrollTo()
            compose.waitForIdle()
            fun box(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val label = box("report_details_label")
            val row = box("report_category:${ReportCategory.messageCategories.last().wire}")
            val field = box("report_details")
            assertTrue("The label sits below the last category row ($label, $row)", label.top >= row.bottom)
            assertTrue("The label sits above the field ($label, $field)", label.bottom <= field.top)
            val question = context.getString(R.string.report_details_label)
            val spoken = compose.onAllNodes(hasAnyAncestor(hasTestTag("report_dialog")) or hasTestTag("report_dialog"))
                .fetchSemanticsNodes().sumOf { node ->
                    (node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } +
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty())
                        .count { question in it }
                }
            assertEquals("The question is announced exactly once", 1, spoken)
            compose.onNodeWithTag("report_details").assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
            // In traversal order, the text-bearing node right before the field is the question.
            val textual = compose.onAllNodes(hasAnyAncestor(hasTestTag("report_dialog"))).fetchSemanticsNodes().filter {
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text) != null ||
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText) != null }
            val at = textual.indexOfFirst { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == "report_details" }
            assertTrue("The field is in the dialog's text order", at > 0)
            assertEquals("The question comes right before the field", listOf(question),
                textual[at - 1].config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.map { it.text })
        }
    }

    /**
     * #369 (TL, PL, 2026-11-25): the form dialog swaps between pinned and one column when the keyboard shows or hides,
     * which used to lose the scroll position and send a field taller than the view to its top, so its caret could leave
     * the view. With long details at 200%, the caret's line stays inside the field's visible part and above the keyboard
     * after typing, a keyboard hide (a swap) and a paste, in portrait and
     * landscape; the keyboard showing over existing text (the swap back) is the text-first cases. The
     * label is never cut in half: the field's top is either uncut or cut by at least the label's line.
     */
    @Test fun at200PercentPortraitTheCaretLineStaysInViewAcrossKeyboardAndModeChanges() = caretStaysInView(landscape = false)

    @Test fun at200PercentLandscapeTheCaretLineStaysInViewAcrossKeyboardAndModeChanges() = caretStaysInView(landscape = true)

    /**
     * #369 (TL): the late-keyboard order. The text arrives first, through semantics (as an accessibility service sets it),
     * and the keyboard shows after it, swapping the dialog to one column: the caret's line ends in view.
     */
    @Test fun at200PercentPortraitTextFirstThenTheKeyboardKeepsTheCaretLineInView() = textFirst(landscape = false)

    @Test fun at200PercentLandscapeTextFirstThenTheKeyboardKeepsTheCaretLineInView() = textFirst(landscape = true)

    private fun launchAt200(landscape: Boolean, reports: ReportViewModel, body: () -> Unit) =
        launchAt(2f, landscape, reports, body)

    private fun launchAt(fontScale: Float, landscape: Boolean, reports: ReportViewModel, body: () -> Unit) {
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
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    val band = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toPx() }
                    SideEffect { labelBand = band }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                } } }
            }
            body()
        }
    }

    private fun textFirst(landscape: Boolean) {
        val reports = ReportViewModel(operations)
        launchAt200(landscape, reports) {
            report()
            val case = "report-font200-${if (landscape) "landscape" else "portrait"} text first"
            compose.onNodeWithTag("report_details").performScrollTo().performTextInput("Posted the same link in 3 rooms. ".repeat(24))
            if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true") {
                // The dialog's content bottom at the window height less the keyboard's final inset: the inset has settled.
                compose.waitUntil(5_000) {
                    val window = io.github.thiesi.chitchat.core.ui.focusedWindowIme()
                    val area = compose.onNodeWithTag("form_dialog_area").fetchSemanticsNode().boundsInWindow
                    window.visible && kotlin.math.abs(area.bottom - (window.height - window.imeBottom)) <= 1f
                }
            }
            assertCaretInView(case)
        }
    }

    /**
     * #371 (TL): the proof case (it failed on bc154c1 without the fix, diagnostic run 45787378337: field 153–245 in a 189px
     * viewport). A short filled field stays whole in view, with the label's 8dp margin, when the keyboard arrives (a
     * height change). The height step's caret clamp alone had left it cut at the bottom edge (the form-filled captures on
     * bc154c1), where Compose's own keep-in-view had shown it whole. The same container-based math as the caret asserts.
     */
    @Test fun atDefaultSizeAShortFilledFieldStaysWholeInViewWhenTheKeyboardArrives() = shortFieldStaysWhole(1f)

    /**
     * A regression guard, not proof (TL): unlike the default-size case, this one passed on bc154c1 without the fix
     * (diagnostic run 45787378337), so it guards the 200% geometry without showing the defect.
     */
    @Test fun at200PercentAShortFilledFieldStaysWholeInViewWhenTheKeyboardArrives() = shortFieldStaysWhole(2f)

    private fun shortFieldStaysWhole(fontScale: Float) {
        val reports = ReportViewModel(operations)
        launchAt(fontScale, landscape = false, reports = reports) {
            report()
            compose.onNodeWithTag("report_category:${ReportCategory.HARASSMENT.wire}").performScrollTo().performClick()
            compose.onNodeWithTag("report_details").performScrollTo().performTextInput("Posted the same link in 3 rooms")
            if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true") {
                compose.waitUntil(5_000) {
                    val window = io.github.thiesi.chitchat.core.ui.focusedWindowIme()
                    val area = compose.onNodeWithTag("form_dialog_area").fetchSemanticsNode().boundsInWindow
                    window.visible && kotlin.math.abs(area.bottom - (window.height - window.imeBottom)) <= 1f
                }
            }
            val step = "report-${if (fontScale == 1f) "default" else "font200"} short field"
            val line = assertCaretInView(step)
            val margin = 8 * context.resources.displayMetrics.density
            assertTrue("The short field is whole in view, with the label's margin (${line.detail})",
                line.fieldTop >= margin - 1f && line.fieldBottom <= line.height + 1f)
        }
    }

    /**
     * A2 (PL ruling, "a disabled action must not hide its reason"): in the too-long state, with the caret on the field's
     * last line (by line index), the counter line ("Too long: 1012 / 1000") is in view together with the caret's line.
     * The text is typed through semantics with the keyboard up. The same container math as the caret asserts, logged.
     */
    @Test fun atDefaultSizeTheTooLongCounterStaysInViewWithTheCaretOnTheLastLine() = counterInView(1f)

    @Test fun at200PercentTheTooLongCounterStaysInViewWithTheCaretOnTheLastLine() = counterInView(2f)

    private fun counterInView(fontScale: Float) {
        val reports = ReportViewModel(operations)
        launchAt(fontScale, landscape = false, reports = reports) {
            report()
            compose.onNodeWithTag("report_category:${ReportCategory.SPAM.wire}").performScrollTo().performClick()
            compose.onNodeWithTag("report_details").performScrollTo()
            compose.tapNativeInput("report_details", imeBottom = {
                io.github.thiesi.chitchat.core.ui.focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } })
            compose.onNodeWithTag("report_details").performTextInput("Posted the same link in 3 rooms. ".repeat(31).take(1_012))
            val step = "report-${if (fontScale == 1f) "default" else "font200"} toolong counter"
            val line = assertCaretInView(step)
            // The caret is on the field's last line (line index, not pixels; TL).
            val node = compose.onNodeWithTag("report_details").fetchSemanticsNode()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            val end = node.config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].end
            check(layout.getLineForOffset(end.coerceIn(0, layout.layoutInput.text.length)) == layout.lineCount - 1) {
                "$step: the caret isn't on the last line (${line.detail})" }
            val counter = rectInViewport("report_counter")
            io.github.thiesi.chitchat.core.ui.recordFormDialogMetrics("case=report-counter-" +
                (if (fontScale == 1f) "default" else "font200") + " counterTop=${"%.1f".format(java.util.Locale.ROOT, counter.first)} " +
                "counterBottom=${"%.1f".format(java.util.Locale.ROOT, counter.second)} viewport=${"%.1f".format(java.util.Locale.ROOT, line.height)} " +
                "caretTop=${"%.1f".format(java.util.Locale.ROOT, line.top)} caretBottom=${"%.1f".format(java.util.Locale.ROOT, line.bottom)}")
            assertTrue("The counter line is in view with the caret's line ($step: counter=[${counter.first}, ${counter.second}] ${line.detail})",
                counter.first >= -1f && counter.second <= line.height + 1f)
            // PL: keyboard first, the span came in from below, so it ends 8dp above the edge when that fits.
            val margin = 8 * context.resources.displayMetrics.density
            if (counter.second + margin - line.top <= line.height)
                assertTrue("The counter keeps its 8dp bottom margin ($step: counter=[${counter.first}, ${counter.second}] ${line.detail})",
                    kotlin.math.abs(line.height - counter.second - margin) <= 1f)
        }
    }

    /** A tagged node's top and bottom in the viewport that clips it (the same container math as [caretLine]). */
    private fun rectInViewport(tag: String): Pair<Float, Float> {
        compose.waitForIdle()
        val pinned = compose.onAllNodesWithTag("form_dialog_pinned", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val viewport = compose.onNodeWithTag(if (pinned) "form_dialog_body_viewport" else "form_dialog_viewport", useUnmergedTree = true)
            .fetchSemanticsNode().layoutInfo.coordinates
        val target = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        val top = viewport.localPositionOf(target, androidx.compose.ui.geometry.Offset.Zero).y
        return top to top + target.size.height
    }

    /**
     * DIAGNOSTIC, never merged (#374, TL): opt-in by `-e keyboardRepeat alone|after-counter`. Repeats the cases whose
     * keyboard didn't show on API36 (text first in landscape at 200%, the 200% short field), 20 times each on fresh
     * activities, recording per repetition whether it passed and, if not, the failure's type (a ComposeTimeoutException
     * is the bounded wait for the keyboard's inset). `after-counter` runs the two counter cases before them, in CI's order.
     * Observation only: no retry inside a case, no forced focus or IME.
     */
    @Test fun keyboardRepeatDiagnostic() {
        val mode = InstrumentationRegistry.getArguments().getString("keyboardRepeat")
        org.junit.Assume.assumeTrue(mode == "alone" || mode == "after-counter")
        val cases = buildList<Pair<String, () -> Unit>> {
            if (mode == "after-counter") {
                add("counter-font200" to { counterInView(2f) })
                add("counter-default" to { counterInView(1f) })
            }
            add("text-first-landscape" to { textFirst(landscape = true) })
            add("short-field-font200" to { shortFieldStaysWhole(2f) })
        }
        val reps = org.json.JSONArray()
        for (rep in 0 until 20) for ((name, case) in cases) {
            val outcome = runCatching { case() }
            val failure = outcome.exceptionOrNull()
            reps.put(org.json.JSONObject().put("rep", rep).put("case", name).put("ok", outcome.isSuccess)
                .put("failure", failure?.javaClass?.simpleName ?: org.json.JSONObject.NULL))
            FontScaleTestActivity.requestedFontScale = 1f
        }
        io.github.thiesi.chitchat.testing.ScrollSettleRecorder.write("keyboard-repeat-$mode.json",
            org.json.JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("mode", mode).put("reps", reps))
    }

    /** Every vertical scroller's value, to show a change that keeps the caret's line in view moves nothing. */
    private fun scrollValues(): List<Float> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() }

    @Volatile private var labelBand = 0f

    private fun caretStaysInView(landscape: Boolean) {
        FontScaleTestActivity.requestedFontScale = 2f
        val reports = ReportViewModel(operations)
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            if (landscape) {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(5_000) {
                    var orientation = 0
                    scenario.onActivity { orientation = it.resources.configuration.orientation }
                    orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            }
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    val band = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toPx() }
                    SideEffect { labelBand = band }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                } } }
            }
            report()
            val ime = { io.github.thiesi.chitchat.core.ui.focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } }
            val case = "report-font200-${if (landscape) "landscape" else "portrait"}"
            compose.onNodeWithTag("report_details").performScrollTo()
            compose.tapNativeInput("report_details", imeBottom = ime)
            // TL: a change that keeps the caret's line in view moves nothing.
            compose.onNodeWithTag("report_details").performTextInput("Posted the same link")
            assertCaretInView("$case short")
            val still = scrollValues()
            compose.onNodeWithTag("report_details").performTextInput(" twice")
            compose.waitForIdle()
            assertEquals("$case: a change that keeps the caret's line in view moves nothing", still, scrollValues())
            compose.onNodeWithTag("report_details").performTextInput(" in 3 rooms. " + "Posted the same link in 3 rooms. ".repeat(23))
            val typed = assertCaretInView("$case typed")
            if (!landscape) {
                // TL: the restore itself, not the caret clamp, keeps the offset across a mode swap. The caret goes to a line
                // in the viewport's upper third (a selection change, which moves nothing while the line is in view), then
                // the keyboard hides (one column to pinned): the caret's line keeps its offset from the viewport's top.
                compose.onNodeWithTag("report_details").performTextInputSelection(
                    androidx.compose.ui.text.TextRange(offsetAt(typed.height / 3f)))
                val placed = assertCaretInView("$case caret placed")
                check(placed.top > 8f && placed.bottom < placed.height - 8f) { "Not clear of the edges: ${placed.detail}" }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitUntil(5_000) { !io.github.thiesi.chitchat.core.ui.focusedWindowIme().visible }
                val restored = assertCaretInView("$case keyboard hidden")
                assertTrue("The restore keeps the caret's offset across the swap (${placed.detail} -> ${restored.detail})",
                    kotlin.math.abs(restored.top - placed.top) <= 1f)
            } else {
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitUntil(5_000) { !io.github.thiesi.chitchat.core.ui.focusedWindowIme().visible }
                assertCaretInView("$case keyboard hidden")
            }
            // The keyboard showing again over existing text (the swap back) is the text-first cases below: in touch mode
            // nothing in this dialog can take focus from the field without a tap on an already-focused field, which the
            // strict input contract avoids (TL).
            compose.onNodeWithTag("report_details").performTextInput(" Pasted: the same link again, in another room.".repeat(4))
            assertCaretInView("$case pasted")
        }
    }

    /**
     * The caret's line (from the field's own text layout and selection) inside the viewport of the scroller that holds
     * the field, measured in that scroller's own layout coordinates. In this dialog the window-coordinate paths
     * (positionInWindow, localToWindow) disagreed with boundsInWindow by the inner scroll value (#371's runs, #372), so
     * nothing here converts to the window. The dialog's viewport already ends above the keyboard (imePadding).
     */
    private class CaretLine(val top: Float, val bottom: Float, val height: Float, val cut: Float, val detail: String,
        val metrics: String, val fieldTop: Float = 0f, val fieldBottom: Float = 0f)

    private fun assertCaretInView(step: String): CaretLine {
        val line = caretLine(step)
        val case = "report-caret-" + step.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(50)
        io.github.thiesi.chitchat.core.ui.recordFormDialogMetrics("case=$case ${line.metrics}")
        assertTrue("The caret line is in view (${line.detail})", line.top >= -1f && line.bottom <= line.height + 1f)
        assertTrue("The label is never cut in half (${line.detail})", line.cut <= 0.5f || line.cut >= labelBand - 0.5f)
        compose.onNodeWithTag("report_details").assertIsFocused()
        return line
    }

    /** The field's text layout, and where its top sits in the viewport of the scroller in use. */
    private fun caretLine(step: String, offset: Int? = null): CaretLine {
        compose.waitForIdle()
        val node = compose.onNodeWithTag("report_details").fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val selection = node.config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        val cursor = layout.getCursorRect((offset ?: selection.end).coerceIn(0, layout.layoutInput.text.length))
        // The area that clips the field: the pinned body's viewport, or (one column) the whole dialog's. Measured in that
        // container's own layout coordinates (its content box is the viewport), so no padding constant or scroll value
        // enters; the field's text starts at M3's outlined content padding. Cross-checked against boundsInWindow below.
        val pinned = compose.onAllNodesWithTag("form_dialog_pinned", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val container = compose.onNodeWithTag(if (pinned) "form_dialog_body_viewport" else "form_dialog_viewport", useUnmergedTree = true)
            .fetchSemanticsNode()
        val viewport = container.layoutInfo.coordinates
        val field = node.layoutInfo.coordinates
        val fieldTop = viewport.localPositionOf(field, androidx.compose.ui.geometry.Offset.Zero).y
        val inset = with(androidx.compose.ui.unit.Density(context)) {
            androidx.compose.material3.OutlinedTextFieldDefaults.contentPadding().calculateTopPadding().toPx()
        }
        val caretTop = fieldTop + inset + cursor.top
        val caretBottom = fieldTop + inset + cursor.bottom
        val height = viewport.size.height.toFloat()
        val cut = -fieldTop
        // The cross-check: where the visible field begins in the window, against the container's visible top plus the
        // field's offset (they agree while the field's top is inside the viewport).
        val visible = node.boundsInWindow
        val crossCheck = container.boundsInWindow.top + maxOf(fieldTop, 0f)
        val detail = "$step: caret=[$caretTop, $caretBottom] viewport height=$height fieldTop=$fieldTop pinned=$pinned " +
            "visibleTop=${visible.top} crossCheck=$crossCheck cut=$cut labelBand=$labelBand selection=$selection"
        fun Float.r() = "%.1f".format(java.util.Locale.ROOT, this)
        val metrics = "caretTop=${caretTop.r()} caretBottom=${caretBottom.r()} viewport=${height.r()} fieldTop=${fieldTop.r()} " +
            "pinned=$pinned visibleTop=${visible.top.r()} crossCheck=${crossCheck.r()} cut=${cut.r()} selection=${selection.start}.${selection.end}"
        val fieldBottom = fieldTop + field.size.height
        return CaretLine(caretTop, caretBottom, height, cut, "$detail fieldBottom=$fieldBottom", "$metrics fieldBottom=${fieldBottom.r()}",
            fieldTop, fieldBottom)
    }

    /** The text offset whose line sits at [y] in the viewport (the field's own layout), for a caret away from the edges. */
    private fun offsetAt(y: Float): Int {
        val node = compose.onNodeWithTag("report_details").fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val line = caretLine("offset", 0)
        // caretLine(…, 0) gives the first line's top in the viewport; the text's top is that minus offset 0's cursor top.
        val textTop = line.top - layouts.single().getCursorRect(0).top
        return layouts.single().getOffsetForPosition(androidx.compose.ui.geometry.Offset(8f, y - textTop))
    }

    @Test fun at200PercentPortraitTheButtonsStayReachableWithTheKeyboardUp() = buttonsAboveTheKeyboard(landscape = false)

    @Test fun at200PercentLandscapeTheButtonsStayReachableWithTheKeyboardUp() = buttonsAboveTheKeyboard(landscape = true)

    private fun buttonsAboveTheKeyboard(landscape: Boolean) {
        FontScaleTestActivity.requestedFontScale = 2f
        val reports = ReportViewModel(operations)
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            if (landscape) {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                compose.waitUntil(5_000) {
                    var orientation = 0
                    scenario.onActivity { orientation = it.resources.configuration.orientation }
                    orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            }
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    SideEffect { imeBottom = bottom }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                } } }
            }
            report()
            compose.onNodeWithTag("report_details").performScrollTo()
            // Evidence only, still a failure: with no keyboard yet, the field must be displayed; if not, say where things are.
            try { compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("report_details").assertIsDisplayed() }.isSuccess } }
            catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                fun bounds(tag: String) = runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow }.getOrNull()
                throw AssertionError("Details not displayed before the tap: dialog=${bounds("report_dialog")}, body=${bounds("report_body")}, " +
                    "details=${bounds("report_details")}, send=${bounds("report_send")}, cancel=${bounds("report_cancel")}, " +
                    "roots=${compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().map { it.boundsInWindow }}", timeout)
            }
            compose.tapNativeInput("report_details", imeBottom = {
                io.github.thiesi.chitchat.core.ui.focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } })
            compose.onNodeWithTag("report_details").performTextInput("why")
            compose.waitForIdle()
            // Against the dialog window's own IME top, not isDisplayed alone (which passed with both buttons under the
            // keyboard in portrait, TL). Each button is scrolled to (one column may hold it below the fold) and must be
            // whole, so a clipped-away node can't pass the edge check vacuously.
            val ime = io.github.thiesi.chitchat.core.ui.focusedWindowIme()
            val field = compose.onNodeWithTag("report_details").fetchSemanticsNode().boundsInWindow
            fun scrolledTo(tag: String): androidx.compose.ui.geometry.Rect {
                compose.onNodeWithTag(tag).performScrollTo()
                return compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            }
            val send = scrolledTo("report_send"); val cancel = scrolledTo("report_cancel")
            val case = "report-font200-${if (landscape) "landscape" else "portrait"}"
            io.github.thiesi.chitchat.core.ui.recordFormDialogMetrics("case=$case window=${ime.height}px imeVisible=${ime.visible} " +
                "imeBottom=${ime.imeBottom}px imeTop=${ime.imeTop}px sendBottom=${send.bottom}px cancelBottom=${cancel.bottom}px " +
                "fieldTop=${field.top}px fieldBottom=${field.bottom}px")
            val detail = "$case: imeTop=${ime.imeTop} send=$send cancel=$cancel field=$field"
            if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true")
                assertTrue("The keyboard showed ($detail)", ime.visible)
            val whole = 40 * context.resources.displayMetrics.density
            assertTrue("Send is whole ($detail)", send.height >= whole)
            assertTrue("Cancel is whole ($detail)", cancel.height >= whole)
            assertTrue("Send above the keyboard ($detail)", send.bottom <= ime.imeTop + 0.5f)
            assertTrue("Cancel above the keyboard ($detail)", cancel.bottom <= ime.imeTop + 0.5f)
            assertTrue("The field above the keyboard ($detail)", field.top < ime.imeTop && field.height > 0f)
            compose.onNodeWithTag("report_details").assertIsFocused()
        }
    }
}
