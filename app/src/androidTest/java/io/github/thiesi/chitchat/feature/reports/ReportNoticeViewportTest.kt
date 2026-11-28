package io.github.thiesi.chitchat.feature.reports

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
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
import io.github.thiesi.chitchat.core.ui.focusedWindowIme
import io.github.thiesi.chitchat.core.ui.recordFormDialogMetrics
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PL and TL rulings (2026-11-22): a line that appears after Send must be in view in its own scroller, at 200% portrait,
 * with both reply timings modelled, since both are real (runs 45062311607 and 45063106736 had only the instant one):
 *
 * - **Network reply** (the reply is held until Sending has rendered): Send disables the details field, so its focus and
 *   the keyboard go before the outcome and the form switches to pinned. The app never brings them back: no focus and no
 *   keyboard at the outcome, the line in view, then the person's native tap brings the caret line back.
 * - **Instant reply** (no Sending frame): the field keeps focus and the keyboard stays. The line must be in view above the
 *   keyboard with nothing else focused, and stay in view when the keyboard closes (the precedence rule: the focused-field
 *   effect must not scroll it away); then a native tap brings the caret line back.
 *
 * Measured from clipped bounds only. `positionInWindow` read about -1370 in column mode for lines whose clipped bounds lay
 * inside the viewport (run 45062311607), so it isn't used. A line taller than its viewport (the snapshot notice at 200%)
 * must fill it with its top showing: its text's clipped top below the viewport top by the notice's top padding, and the
 * text's clipped height reaching the bottom of the lead's last line. Every stage records its facts (`case=notice-...`,
 * also to logcat as CCMNotice) before any assert.
 */
class ReportNoticeViewportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = context.resources.displayMetrics.density
    @After fun reset() {
        if (focusedWindowIme().visible) androidx.test.espresso.Espresso.closeSoftKeyboard()
        FontScaleTestActivity.requestedFontScale = 1f
    }

    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun imeBottom() = focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 }

    /** The line's nearest scrolling ancestor's clipped bounds: the viewport it's shown in. */
    private fun viewportOf(node: SemanticsNode): Rect {
        var scroller = node.parent
        while (scroller != null && SemanticsActions.ScrollBy !in scroller.config) scroller = scroller.parent
        return checkNotNull(scroller) { "The line has no scrolling ancestor" }.boundsInWindow
    }

    /**
     * The in-view rule, from clipped bounds: a line that fits is whole and above the keyboard; a taller one ([lead] given,
     * a WarningNotice) fills the viewport with its top showing and its lead whole.
     */
    private fun inView(line: SemanticsNodeInteraction, lead: String?): Pair<Boolean, String> {
        val node = line.fetchSemanticsNode()
        val viewport = viewportOf(node)
        val clipped = node.boundsInWindow
        val full = node.size.height.toFloat()
        val imeTop = focusedWindowIme().imeTop.toFloat()
        val fits = full <= viewport.height + 0.5f
        var detail = "lineTop=${clipped.top} lineBottom=${clipped.bottom} lineClipped=${clipped.height} lineFull=$full " +
            "viewportTop=${viewport.top} viewportHeight=${viewport.height} fits=$fits imeTop=$imeTop"
        val ok = if (fits) full > 0f && clipped.height >= full - 1f && clipped.bottom <= imeTop + 0.5f
        else {
            val fills = kotlin.math.abs(clipped.top - viewport.top) <= 1f && clipped.height >= viewport.height - 1f
            // The notice's one text node (lead and body in one annotated string), unmerged.
            val text = compose.onNode(hasText(checkNotNull(lead) { "A taller line needs its lead" }, substring = true) and
                hasAnyAncestor(hasTestTag("report_notice")), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { text.config[SemanticsActions.GetTextLayoutResult].action!!(layouts) }
            val leadBottom = layouts.single().let { it.getLineBottom(it.getLineForOffset(lead.length - 1)) }
            val textClipped = text.boundsInWindow
            val padding = 12f * density // The notice's 8dp top padding and the line's 4dp.
            val topShowing = textClipped.top >= viewport.top + padding - 1f
            val leadWhole = textClipped.height >= leadBottom - 0.5f
            detail += " textTop=${textClipped.top} textClipped=${textClipped.height} leadBottom=$leadBottom fills=$fills " +
                "topShowing=$topShowing leadWhole=$leadWhole"
            fills && topShowing && leadWhole && clipped.bottom <= imeTop + 0.5f
        }
        return ok to "$detail inView=$ok"
    }

    /** One line of facts: focus (the field's, and how many nodes are focused), keyboard, mode, plus [extra]. */
    private fun facts(case: String, stage: String, extra: String = ""): Triple<Boolean?, Boolean, Int> {
        val ime = focusedWindowIme()
        val field = runCatching { compose.onNodeWithTag("report_details").fetchSemanticsNode() }.getOrNull()
        val focused = field?.config?.getOrNull(SemanticsProperties.Focused)
        val focusedNodes = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        val mode = if (present("form_dialog_pinned")) "pinned" else if (present("form_dialog_column")) "column" else "none"
        val line = ("case=notice-$case stage=$stage imeVisible=${ime.visible} imeTop=${ime.imeTop} window=${ime.height} " +
            "fieldFocused=$focused focusedNodes=$focusedNodes mode=$mode fieldTop=${field?.boundsInWindow?.top} " +
            "fieldBottom=${field?.boundsInWindow?.bottom} $extra").trim()
        recordFormDialogMetrics(line)
        android.util.Log.i("CCMNotice", line) // Scalars only; also readable without the app's external files.
        return Triple(focused, ime.visible, focusedNodes)
    }

    /** After the person's native tap (strict focus and IME proof): the field whole above the keyboard, its caret inside it. */
    private fun caretBack(case: String) {
        compose.onNodeWithTag("report_details").performScrollTo()
        compose.tapNativeInput("report_details", imeBottom = ::imeBottom)
        compose.waitForIdle()
        val field = compose.onNodeWithTag("report_details").fetchSemanticsNode()
        val ime = focusedWindowIme()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle { field.config[SemanticsActions.GetTextLayoutResult].action!!(layouts) }
        val caret = layouts.single().getCursorRect(field.config[SemanticsProperties.TextSelectionRange].end)
        val box = field.boundsInWindow
        val whole = box.height >= field.size.height - 1f
        facts(case, "tapped", "fieldWhole=$whole caretBottomInField=${caret.bottom} fieldHeight=${field.size.height}")
        assertTrue("$case: after the tap the field is whole above the keyboard with the caret inside it (field $box, " +
            "size ${field.size}, caret $caret, imeTop ${ime.imeTop})",
            ime.visible && whole && box.bottom <= ime.imeTop + 0.5f && caret.bottom <= field.size.height + 0.5f)
    }

    private fun measure(case: String, person: Boolean, category: ReportCategory, results: List<ReportResult>,
        instant: Boolean = false, facts: ReportFacts? = null, thenTap: Boolean = false, repeat: Boolean = false,
        lead: String? = null, notTopAligned: Boolean = false, line: () -> SemanticsNodeInteraction) {
        FontScaleTestActivity.requestedFontScale = 2f
        val session = ReceivedFiles.session()
        val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
        val room = ConversationViewModel(rooms)
        val queue = ArrayDeque(results)
        // Network reply: each send waits for its own release, given once Sending has rendered. Instant: no wait.
        var gate = CompletableDeferred<Unit>()
        val reports = ReportViewModel(object : ReportOperations {
            override val session = session
            override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
                if (!instant) gate.await()
                return synchronized(queue) { queue.removeFirstOrNull() } ?: ReportResult.Sent(1)
            }
            override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = facts
        })
        fun send() {
            compose.onNodeWithTag("report_send").performScrollTo().performNativeTap()
            if (!instant) {
                compose.waitUntil(5_000) { reports.state.value?.phase == ReportPhase.Sending }
                compose.waitForIdle()
                facts(case, "sending")
                gate.complete(Unit); gate = CompletableDeferred()
            }
        }
        try {
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                    } } }
                }
                compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy && room.state.value.context != null }
                if (person) compose.runOnIdle { reports.requestPerson(ReportTarget.Person(8, 42), ReportOrigin.Room(42), true, "Bob", reports.settled) }
                else {
                    val label = context.getString(R.string.message_actions)
                    compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("message:3"))
                    compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
                        hasAnyAncestor(hasTestTag("message:3"))).performSemanticsAction(SemanticsActions.OnClick)
                    compose.waitUntil(5_000) { present("message_report_action") }
                    compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
                }
                compose.waitUntil(5_000) { present("report_dialog") }
                compose.onNodeWithTag("report_category:${category.wire}").performScrollTo().performClick()
                compose.onNodeWithTag("report_details").performScrollTo()
                io.github.thiesi.chitchat.testing.DiagRecording.flash() // DIAGNOSTIC: the sync strip.
                compose.tapNativeInput("report_details", imeBottom = ::imeBottom)
                compose.onNodeWithTag("report_details").performTextInput("Posted the same link in 3 rooms")
                compose.waitForIdle()
                io.github.thiesi.chitchat.testing.DiagRecording.capture("first-arrival-end") // DIAGNOSTIC
                facts(case, "beforeSend")
                send()
                if (facts != null) {
                    // didn't arrive: the Unknown summary first, then Check (a GET) finds nothing and the form returns.
                    compose.waitUntil(5_000) { present("report_check") }
                    compose.onNodeWithTag("report_check").performScrollTo().performNativeTap()
                }
                compose.waitUntil(5_000) { runCatching { line().fetchSemanticsNode() }.isSuccess }
                compose.waitForIdle()
                val (shown, detail) = inView(line(), lead)
                val (focused, imeUp, focusedNodes) = facts(case, "shown", detail)
                if (instant) {
                    // Nothing moved focus: the field keeps it and is the only focused node; the keyboard stays.
                    assertTrue("$case: the field keeps focus after an instant refusal ($detail)", focused == true && focusedNodes == 1)
                    assertTrue("$case: the keyboard stays after an instant refusal ($detail)", imeUp)
                } else {
                    // No focus and no keyboard brought back by the app after a refusal that keeps the form (TL, PL).
                    assertTrue("$case: the field has no focus after the refusal ($detail)", focused != true)
                    assertTrue("$case: the keyboard stays closed after the refusal ($detail)", !imeUp)
                }
                assertTrue("$case: the line is in view when it appears ($detail)", shown)
                if (notTopAligned) {
                    // TL: the top-edge request is a no-op for a line that fits: it stays where the minimal scroll left it,
                    // below the viewport top, not pulled up to it.
                    val node = line().fetchSemanticsNode()
                    val viewport = viewportOf(node)
                    assertTrue("$case: a fitting line isn't pulled to the viewport top ($detail)", node.boundsInWindow.top > viewport.top + 1f)
                }
                if (instant) {
                    // The precedence rule: the keyboard closing must not let the focused-field effect scroll the line away.
                    androidx.test.espresso.Espresso.closeSoftKeyboard()
                    compose.waitForIdle()
                    val (still, stillDetail) = inView(line(), lead)
                    facts(case, "keyboardClosed", stillDetail)
                    assertTrue("$case: the line stays in view when the keyboard closes ($stillDetail)", still)
                }
                if (repeat) {
                    // TL: the same refusal again. The person scrolls back up to the categories (the line leaves the view),
                    // then Sends again; the second identical line must be brought into view again.
                    compose.onNodeWithTag("report_category:${category.wire}").performScrollTo()
                    compose.waitForIdle()
                    facts(case, "scrolledAway")
                    send()
                    compose.waitUntil(5_000) { reports.state.value?.noticeSeq == 2L }
                    compose.waitForIdle()
                    val (again, againDetail) = inView(line(), lead)
                    facts(case, "shownAgain", againDetail)
                    assertTrue("$case: the same line is in view again after a second refusal ($againDetail)", again)
                }
                if (thenTap) caretBack(case)
                io.github.thiesi.chitchat.testing.DiagRecording.beforeClose?.invoke() // DIAGNOSTIC: the hide leg.
            }
        } finally { gate.complete(Unit); compose.runOnIdle { room.cancel(); reports.close() } }
    }

    private val snapshotLead get() = context.getString(R.string.report_person_snapshot_lead)
    private val notSent get() = context.getString(R.string.report_not_sent)

    // The network reply: the ruled sequence (no focus, no keyboard at the outcome; the person's tap brings the caret back).
    // DIAGNOSTIC (never merged), #378: the notice re-tap case with animations off and on (recorded).
    private fun diag(name: String, animations: Boolean, case: () -> Unit = { personSnapshot() }) {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("a3Probe") == "run")
        io.github.thiesi.chitchat.testing.DiagRecording.run(name, animations, hide = {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { !io.github.thiesi.chitchat.core.ui.focusedWindowIme().visible }
            compose.waitForIdle()
        }) { case() }
    }
    // DIAGNOSTIC (never merged), #378 metric diag: the didnotarrive re-tap (tapped-stage fieldTop 50 on main, 158 on #381).
    @Test fun diagDidNotArriveOff() = diag("ccm-a3-didnotarrive-off", false) { personDidNotArrive() }
    @Test fun diagNoticeOff() = diag("ccm-a3-notice-off", false)
    @Test fun diagNoticeOn() = diag("ccm-a3-notice-on", true)

    @Test fun personSnapshot() = measure("person-snapshot", true, ReportCategory.OFFENSIVE_PROFILE_PICTURE,
        listOf(ReportResult.SnapshotUnavailable), thenTap = true, lead = snapshotLead) { compose.onNodeWithTag("report_notice") }

    @Test fun personPictureGone() = measure("person-picture-gone", true, ReportCategory.OFFENSIVE_PROFILE_PICTURE,
        listOf(ReportResult.PictureGone)) { compose.onNode(hasText(context.getString(R.string.report_person_picture_gone, "Bob"))) }

    /** TL: a second identical refusal is brought into view again (the effect keys on the occurrence, not the notice). */
    @Test fun personSnapshotTwice() = measure("person-snapshot-twice", true, ReportCategory.OFFENSIVE_PROFILE_PICTURE,
        listOf(ReportResult.SnapshotUnavailable, ReportResult.SnapshotUnavailable), repeat = true, lead = snapshotLead) {
        compose.onNodeWithTag("report_notice") }

    @Test fun personRate() = measure("person-rate", true, ReportCategory.OFFENSIVE_USERNAME,
        listOf(ReportResult.RateLimited(900))) { compose.onNodeWithTag("report_wait") }

    @Test fun personDidNotArrive() = measure("person-didnotarrive", true, ReportCategory.OFFENSIVE_USERNAME,
        listOf(ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))), facts = ReportFacts(canReport = true, reportedByMe = false),
        thenTap = true, lead = context.getString(R.string.report_person_did_not_arrive), notTopAligned = true) {
        compose.onNodeWithTag("report_notice") }

    @Test fun messageNotSent() = measure("message-notsent", false, ReportCategory.SPAM,
        listOf(ReportResult.Invalid)) { compose.onNode(hasText(notSent)) }

    // The instant reply: focus and the keyboard stay; the line in view, still in view when the keyboard closes.
    @Test fun personSnapshotInstant() = measure("person-snapshot-instant", true, ReportCategory.OFFENSIVE_PROFILE_PICTURE,
        listOf(ReportResult.SnapshotUnavailable), instant = true, thenTap = true, lead = snapshotLead) { compose.onNodeWithTag("report_notice") }

    @Test fun messageNotSentInstant() = measure("message-notsent-instant", false, ReportCategory.SPAM,
        listOf(ReportResult.Invalid), instant = true, thenTap = true) { compose.onNode(hasText(notSent)) }
}
