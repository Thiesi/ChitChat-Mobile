package io.github.thiesi.chitchat.feature.reports

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportFacts
import io.github.thiesi.chitchat.core.reports.ReportOperations
import io.github.thiesi.chitchat.core.reports.ReportRefusal
import io.github.thiesi.chitchat.core.reports.ReportResult
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ReceivedDirects
import io.github.thiesi.chitchat.core.ui.ReceivedFiles
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.dm
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.file
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.core.ui.ReceivedRooms
import io.github.thiesi.chitchat.feature.account.IgnorePersonViewModel
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadScreen
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Reporting R2c for J (PL list of 2026-11-20): the message report in the real room and DM screens at a real Configuration
 * font scale, light at default and 200%, plus dark at default for the form and the sheet. Scripted report results.
 */
class ReportPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private class Shot(val name: String, val node: String = "report_host", val dm: Boolean = false,
        val rooms: List<RoomMessage> = listOf(roomMessage(3, "check out my channel, link in bio, cheap followers")),
        val dms: List<DirectMessage> = listOf(dm(3, "check out my channel, link in bio, cheap followers", null)),
        val results: List<ReportResult> = emptyList(), val hold: Boolean = false, val ignored: Set<Long> = emptySet(),
        /** Only at this scale (the 200% scrolled-to-end form). */
        val onlyAt: String? = null,
        /** Landscape (the TL's geometry evidence, 200% only). */
        val landscape: Boolean = false,
        /** The StepUpDialog alone, which shares the form-dialog geometry (TL). */
        val stepUp: Boolean = false,
        val act: ReportPresentationTest.(Fixture) -> Unit = {})

    /** The live objects a shot drives. */
    class Fixture(val reports: ReportViewModel, val hold: CompletableDeferred<Unit>, val swap: (String) -> Unit)

    private val longFile = "quarterly-financial-report-for-the-steering-committee-final.pdf"

    private fun shots(): List<Shot> = listOf(
        Shot("actions-room", node = "message_actions", act = { actions(3) }),
        Shot("actions-dm", node = "message_actions", dm = true, act = { actions(3) }),
        Shot("actions-own", node = "message_actions", rooms = listOf(RoomMessage(3, 42, 7, "Alice", "text", "my own message", false,
            "2026-10-20 12:00:00+00")), act = { actions(3) }),
        Shot("form-room", node = "report_dialog", act = { report(3) }),
        // PL: the true end, with no bottom divider and nothing cut. Every scroller around the who-sees line goes to its end
        // (performScrollTo on the line stopped short of the bottom in the nested scrollers).
        Shot("form-room-end", node = "report_dialog", onlyAt = "font200", act = {
            report(3)
            compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(
                androidx.compose.ui.test.hasTestTag("report_who"))).fetchSemanticsNodes().forEach { node ->
                compose.onNode(androidx.compose.ui.test.SemanticsMatcher("scroller ${node.id}") { it.id == node.id })
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            }
        }),
        Shot("form-dm", node = "report_dialog", dm = true, act = { report(3) }),
        Shot("form-photo", node = "report_dialog", rooms = listOf(roomMessage(3, null, photo(70))), act = { report(3) }),
        Shot("form-file", node = "report_dialog", rooms = listOf(roomMessage(3, null, file(71, longFile))), act = { report(3) }),
        // PL: the attachment line ("They'll see the file's name…") at the form's end, light default.
        Shot("form-file-end", node = "report_dialog", onlyAt = "default", rooms = listOf(roomMessage(3, null, file(71, longFile))), act = {
            report(3); compose.onNodeWithTag("report_who_attachment").performScrollTo() }),
        Shot("form-filled", node = "report_dialog", act = { report(3); fill() }),
        Shot("form-toolong", node = "report_dialog", act = {
            report(3); choose(ReportCategory.SPAM)
            compose.onNodeWithTag("report_details").performScrollTo().performTextInput("Posted the same link in 3 rooms. ".repeat(31).take(1_012))
            // #369: the keyboard can arrive after the input and swap the dialog to one column. The form dialog keeps the
            // caret's line in view through that (with the counter just below it), so the capture waits for the dialog's
            // layout to reach the keyboard's final inset (a bounded positive condition) and doesn't scroll on its own:
            // a performScrollTo to the already visible counter moved the view to the reasons list (N=40 runs
            // 45746286440, 45753087572).
            awaitKeyboardLayout()
        }),
        Shot("sending", node = "report_dialog", hold = true, act = { report(3); fill(); send() }),
        Shot("rate", node = "report_dialog", results = listOf(ReportResult.RateLimited(900)), act = { report(3); fill(); send()
            compose.waitUntil(5_000) { (it.reports.state.value?.phase as? ReportPhase.Editing)?.notice != null } }),
        Shot("unknown", node = "report_dialog", results = listOf(unknown), act = { report(3); fill(); send(); awaitUnknown() }),
        Shot("unknown-nodetails", node = "report_dialog", results = listOf(unknown), act = {
            report(3); choose(ReportCategory.HARASSMENT); send(); awaitUnknown() }),
        Shot("sent-sheet-room", node = "report_sheet", act = { report(3); fill(); send(); await("report_sheet") }),
        Shot("sent-sheet-dm", node = "report_sheet", dm = true, act = { report(3); fill(); send(); await("report_sheet") }),
        // A sender already ignored: no sheet, so the stack's line is the subject.
        Shot("already", ignored = setOf(8), results = listOf(ReportResult.AlreadyReported), act = {
            // An ignored sender's message is collapsed until revealed (an explicit person action), then reported.
            compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.conversation_ignored))
                .performScrollTo().performClick()
            report(3); fill(); send(); await("report_outcome") }),
        Shot("refused", results = listOf(ReportResult.Refused(ReportRefusal.NOT_ALLOWED)), act = { report(3); fill(); send(); await("report_outcome") }),
        Shot("notreportable", results = listOf(ReportResult.Refused(ReportRefusal.NOT_REPORTABLE)), act = {
            report(3); fill(); send(); await("report_outcome") }),
        Shot("sending-line", hold = true, act = { report(3); fill(); send(); away(it); await("report_sending_line") }),
        Shot("review", hold = true, act = { report(3); fill(); send(); compose.runOnIdle { it.reports.backgrounded() }; await("report_review") }),
        // Evidence for the TL and PL (#326 run 44823638278: the details field not displayed at 200% landscape): the form
        // as it opens, the form scrolled to its field, and the StepUpDialog with its field, all at 200% landscape.
        Shot("diag-landscape-form", node = "report_dialog", onlyAt = "font200", landscape = true, act = { report(3) }),
        Shot("diag-landscape-form-field", node = "report_dialog", onlyAt = "font200", landscape = true, act = {
            report(3); compose.onNodeWithTag("report_details").performScrollTo() }),
        Shot("diag-landscape-stepup", node = "dialog", onlyAt = "font200", landscape = true, stepUp = true),
        // On the form-dialog scaffold (#328), for the PL's J (TL): the form scrolled to its end (Send report stacked above
        // Cancel) and the Unknown summary, both at 200% landscape.
        Shot("diag-landscape-form-end", node = "report_dialog", onlyAt = "font200", landscape = true, act = {
            report(3); compose.onNodeWithTag("report_cancel").performScrollTo() }),
        Shot("diag-landscape-unknown", node = "report_dialog", onlyAt = "font200", landscape = true, results = listOf(unknown),
            act = { report(3); fill(); send(); awaitUnknown() }),
        Shot("busy", hold = true, rooms = listOf(roomMessage(3, "check out my channel, link in bio, cheap followers"), roomMessage(5, "and again")),
            act = { report(3); fill(); send(); away(it); await("report_sending_line"); actions(5)
                compose.onNodeWithTag("message_report_action").performScrollTo().performClick(); await("report_busy") }),
    )

    private val unknown = ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))

    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    /** The dialog's content bottom at the window height less the keyboard's final inset: the inset has settled. */
    private fun awaitKeyboardLayout() {
        if (InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") != "true") return
        compose.waitUntil(5_000) {
            val window = io.github.thiesi.chitchat.core.ui.focusedWindowIme()
            val area = compose.onNodeWithTag("form_dialog_area").fetchSemanticsNode().boundsInWindow
            window.visible && kotlin.math.abs(area.bottom - (window.height - window.imeBottom)) <= 1f
        }
        compose.waitForIdle()
    }
    private fun await(tag: String) = compose.waitUntil(5_000) { present(tag) }
    private var dmScreen = false

    /** Room rows are `message:{id}`, DM rows `direct:{id}`; the row is scrolled into the lazy list's composed range first. */
    private fun actions(messageId: Long) {
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.message_actions)
        val row = if (dmScreen) "direct:$messageId" else "message:$messageId"
        compose.onNodeWithTag(if (dmScreen) "dm_timeline" else "conversation_timeline").performScrollToNode(hasTestTag(row))
        compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
            hasAnyAncestor(hasTestTag(row))).performSemanticsAction(SemanticsActions.OnClick)
        await("message_actions")
    }
    private fun report(messageId: Long) {
        actions(messageId)
        compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
        await("report_dialog")
    }
    private fun choose(category: ReportCategory) = compose.onNodeWithTag("report_category:${category.wire}").performScrollTo().performClick()
    private fun fill() {
        choose(ReportCategory.HARASSMENT)
        compose.onNodeWithTag("report_details").performScrollTo().performTextInput("Posted the same link in 3 rooms")
    }
    // Captures don't test input (the layout tests keep native taps at 200% landscape): Send is scrolled to and clicked
    // through its semantics action, with no geometry. A positional click raced the IME animation at 200% landscape and
    // landed outside the shrinking viewport, where the report form ignores taps by design (run 44872841961, API36).
    private fun send() = compose.onNodeWithTag("report_send").performScrollTo()
        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    private fun ReportPresentationTest.awaitUnknown() = await("report_send_again")
    /** A navigation departure and return: the screen leaves composition and comes back (no configuration change). */
    private fun away(fixture: Fixture) {
        compose.waitUntil(5_000) { fixture.reports.state.value?.phase == ReportPhase.Sending }
        compose.runOnIdle { fixture.swap("none") }; compose.waitForIdle()
        compose.runOnIdle { fixture.swap("screen") }
    }

    @Test fun presentationLight() = presentation(dark = false, scales = listOf("default" to 1f, "font200" to 2f), only = null)
    @Test fun presentationDark() = presentation(dark = true, scales = listOf("default" to 1f), only = setOf("form-room", "sent-sheet-room"))

    private fun presentation(dark: Boolean, scales: List<Pair<String, Float>>, only: Set<String>?) {
        val theme = if (dark) "dark" else "light"
        for ((scaleName, fontScale) in scales) for (shot in shots().filter { (only == null || it.name in only) && (it.onlyAt == null || it.onlyAt == scaleName) }) {
            FontScaleTestActivity.requestedFontScale = fontScale
            dmScreen = shot.dm
            // The session's chat policy as a real bootstrap carries it, matching the room's: the Ignore follow-up reads it (TL).
            val session = ReceivedFiles.session().apply { value = value.copy(chatPolicy =
                io.github.thiesi.chitchat.core.conversation.ChatPolicy(shot.ignored, null, io.github.thiesi.chitchat.core.conversation.DisplayPreferences())) }
            val rooms = ReceivedRooms(session).apply { messages = shot.rooms; ignored = shot.ignored }
            val room = ConversationViewModel(rooms)
            val directs = ReceivedDirects(session).apply { rows = shot.dms }
            val direct = DirectThreadViewModel(directs)
            val gate = CompletableDeferred<Unit>()
            val results = ArrayDeque(shot.results)
            val reports = ReportViewModel(object : ReportOperations {
                override val session = session
                override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
                    if (shot.hold) gate.await()
                    return synchronized(results) { results.removeFirstOrNull() } ?: ReportResult.Sent(1)
                }
                override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
            })
            val ignores = IgnorePersonViewModel(object : IgnoreOperations {
                override val session = session
                override suspend fun change(expected: SessionState, userId: Long, ignored: Boolean) = setOf(userId)
            })
            var screen by mutableStateOf("screen")
            try {
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    if (shot.landscape) {
                        scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                        compose.waitUntil(5_000) {
                            var orientation = 0
                            scenario.onActivity { orientation = it.resources.configuration.orientation }
                            orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        }
                    }
                    scenario.onActivity { activity ->
                        // #351 (found by #367's stability rerun): a steady caret in the form captures, test-only.
                        activity.setContent { ChitChatTheme(dark = dark) { CompositionLocalProvider(LocalReports provides reports,
                            androidx.compose.ui.platform.LocalCursorBlinkEnabled provides false) {
                            Surface(Modifier.fillMaxSize().testTag("report_host"), color = MaterialTheme.colorScheme.background) {
                                if (shot.stepUp) io.github.thiesi.chitchat.feature.auth.StepUpDialog(
                                    androidx.compose.ui.res.stringResource(R.string.step_up_title_export),
                                    io.github.thiesi.chitchat.core.account.VerificationMethods(false, true, 0), busy = false,
                                    errorMessage = null, onVerify = { _, _ -> }, onCancel = {})
                                else if (screen == "screen") {
                                    if (shot.dm) DirectThreadScreen(direct, 8, {}) else ConversationScreen(room, 42, {}, ignores = ignores)
                                }
                            }
                        } } }
                    }
                    if (!shot.stepUp) compose.waitUntil(5_000) {
                        if (shot.dm) direct.state.value.loaded && !direct.state.value.busy else room.state.value.loaded && !room.state.value.busy
                    }
                    shot.act(this, Fixture(reports, gate) { screen = it })
                    capture("report-$theme-$scaleName-${shot.name}", shot.node)
                }
            } finally { gate.complete(Unit); compose.runOnIdle { room.cancel(); direct.background(); reports.close() } }
        }
    }

    private fun capture(name: String, node: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-report-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = (if (node == "dialog") compose.onNode(androidx.compose.ui.test.isDialog()) else compose.onNodeWithTag(node))
            .captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
