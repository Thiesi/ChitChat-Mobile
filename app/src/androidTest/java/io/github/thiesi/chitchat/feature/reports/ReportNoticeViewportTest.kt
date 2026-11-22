package io.github.thiesi.chitchat.feature.reports

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PL and TL rulings (2026-11-22), measured first at 200% portrait: Send disables the details field, so its focus and the
 * keyboard go before the outcome, and the form switches to its pinned layout. A line that appears after Send must then be
 * in view in its own scroller: fully when it fits, otherwise with its top at the viewport top (the 200% snapshot notice
 * is taller than the body). The app never brings focus or the keyboard back after a refusal that keeps the form; the
 * person taps the field, and the caret line is then in view above the keyboard. Each stage records its facts
 * (`case=notice-...`, also to logcat as CCMNotice) before any assert, so a failure still says where things were.
 */
class ReportNoticeViewportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun reset() {
        if (focusedWindowIme().visible) androidx.test.espresso.Espresso.closeSoftKeyboard()
        FontScaleTestActivity.requestedFontScale = 1f
    }

    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun imeBottom() = focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 }

    /** The line's own scroller viewport (its nearest scrolling ancestor), and whether the line meets the in-view rule. */
    private fun inView(line: SemanticsNodeInteraction): Pair<Boolean, String> {
        val node = line.fetchSemanticsNode()
        var scroller = node.parent
        while (scroller != null && SemanticsActions.ScrollBy !in scroller.config) scroller = scroller.parent
        val viewport = checkNotNull(scroller) { "The line has no scrolling ancestor" }.boundsInWindow
        val clipped = node.boundsInWindow
        val top = node.positionInWindow.y
        val full = node.size.height.toFloat()
        val imeTop = focusedWindowIme().imeTop.toFloat()
        val fits = full <= viewport.height + 0.5f
        val ok = full > 0f && clipped.bottom <= imeTop + 0.5f && if (fits) clipped.height >= full - 1f
            else kotlin.math.abs(top - viewport.top) <= 1f && clipped.height >= viewport.height - 1f
        return ok to "lineTop=$top lineFull=$full lineClipped=${clipped.height} viewportTop=${viewport.top} " +
            "viewportHeight=${viewport.height} fits=$fits imeTop=$imeTop inView=$ok"
    }

    private fun facts(case: String, stage: String, extra: String = ""): Pair<Boolean?, Boolean> {
        val ime = focusedWindowIme()
        val field = runCatching { compose.onNodeWithTag("report_details").fetchSemanticsNode() }.getOrNull()
        val focused = field?.config?.getOrNull(SemanticsProperties.Focused)
        val mode = if (present("form_dialog_pinned")) "pinned" else if (present("form_dialog_column")) "column" else "none"
        val line = ("case=notice-$case stage=$stage imeVisible=${ime.visible} imeTop=${ime.imeTop} window=${ime.height} " +
            "fieldFocused=$focused mode=$mode fieldTop=${field?.boundsInWindow?.top} fieldBottom=${field?.boundsInWindow?.bottom} $extra").trim()
        recordFormDialogMetrics(line)
        android.util.Log.i("CCMNotice", line) // Scalars only; also readable without the app's external files.
        return focused to ime.visible
    }

    /**
     * One refusal at 200% portrait: type with the keyboard up, Send by a native tap, then the line. [thenTap]: the person
     * taps the field again (native tap, strict focus and IME proof) and the caret line must be in view above the keyboard.
     */
    private fun measure(case: String, person: Boolean, category: ReportCategory, results: List<ReportResult>,
        facts: ReportFacts? = null, thenTap: Boolean = false, line: () -> SemanticsNodeInteraction) {
        FontScaleTestActivity.requestedFontScale = 2f
        val session = ReceivedFiles.session()
        val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
        val room = ConversationViewModel(rooms)
        val queue = ArrayDeque(results)
        val reports = ReportViewModel(object : ReportOperations {
            override val session = session
            override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?) =
                synchronized(queue) { queue.removeFirstOrNull() } ?: ReportResult.Sent(1)
            override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = facts
        })
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
                compose.tapNativeInput("report_details", imeBottom = ::imeBottom)
                compose.onNodeWithTag("report_details").performTextInput("Posted the same link in 3 rooms")
                compose.waitForIdle()
                facts(case, "beforeSend")
                compose.onNodeWithTag("report_send").performScrollTo().performNativeTap()
                if (facts != null) {
                    // didn't arrive: the Unknown summary first, then Check (a GET) finds nothing and the form returns.
                    compose.waitUntil(5_000) { present("report_check") }
                    compose.onNodeWithTag("report_check").performScrollTo().performNativeTap()
                }
                compose.waitUntil(5_000) { runCatching { line().fetchSemanticsNode() }.isSuccess }
                compose.waitForIdle()
                val (shown, detail) = inView(line())
                val (focused, imeUp) = facts(case, "shown", detail)
                // No focus and no keyboard brought back by the app after a refusal that keeps the form (TL, PL).
                assertTrue("$case: the field has no focus after the refusal ($detail)", focused != true)
                assertTrue("$case: the keyboard stays closed after the refusal ($detail)", !imeUp)
                assertTrue("$case: the line is in view when it appears ($detail)", shown)
                if (thenTap) {
                    // The person resumes: one native tap with the strict focus and IME proof; the caret line is then in view.
                    compose.onNodeWithTag("report_details").performScrollTo()
                    compose.tapNativeInput("report_details", imeBottom = ::imeBottom)
                    compose.waitForIdle()
                    val field = compose.onNodeWithTag("report_details").fetchSemanticsNode()
                    val ime = focusedWindowIme()
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    compose.runOnIdle { field.config[SemanticsActions.GetTextLayoutResult].action!!(layouts) }
                    val caret = layouts.single().getCursorRect(field.config[SemanticsProperties.TextSelectionRange].end)
                    val caretTop = field.positionInWindow.y + caret.top
                    val caretBottom = field.positionInWindow.y + caret.bottom
                    facts(case, "tapped", "caretTop=$caretTop caretBottom=$caretBottom")
                    assertTrue("$case: the caret line is in view above the keyboard (caret $caretTop-$caretBottom, " +
                        "imeTop ${ime.imeTop}, field ${field.boundsInWindow})", ime.visible &&
                        caretTop >= field.boundsInWindow.top - 0.5f &&
                        caretBottom <= minOf(field.boundsInWindow.bottom, ime.imeTop.toFloat()) + 0.5f)
                }
            }
        } finally { compose.runOnIdle { room.cancel(); reports.close() } }
    }

    @Test fun personSnapshot() = measure("person-snapshot", true, ReportCategory.OFFENSIVE_PROFILE_PICTURE,
        listOf(ReportResult.SnapshotUnavailable), thenTap = true) { compose.onNodeWithTag("report_notice") }

    @Test fun personPictureGone() = measure("person-picture-gone", true, ReportCategory.OFFENSIVE_PROFILE_PICTURE,
        listOf(ReportResult.PictureGone)) { compose.onNode(hasText(context.getString(R.string.report_person_picture_gone, "Bob"))) }

    @Test fun personRate() = measure("person-rate", true, ReportCategory.OFFENSIVE_USERNAME,
        listOf(ReportResult.RateLimited(900))) { compose.onNodeWithTag("report_wait") }

    @Test fun personDidNotArrive() = measure("person-didnotarrive", true, ReportCategory.OFFENSIVE_USERNAME,
        listOf(ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))), ReportFacts(canReport = true, reportedByMe = false), thenTap = true) {
        compose.onNodeWithTag("report_notice") }

    @Test fun messageNotSent() = measure("message-notsent", false, ReportCategory.SPAM,
        listOf(ReportResult.Invalid)) { compose.onNode(hasText(context.getString(R.string.report_not_sent))) }
}
