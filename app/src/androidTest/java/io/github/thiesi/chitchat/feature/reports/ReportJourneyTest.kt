package io.github.thiesi.chitchat.feature.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.IgnoreOperations
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.reports.ReportCategory
import io.github.thiesi.chitchat.core.reports.ReportFacts
import io.github.thiesi.chitchat.core.reports.ReportOperations
import io.github.thiesi.chitchat.core.reports.ReportResult
import io.github.thiesi.chitchat.core.reports.ReportTarget
import io.github.thiesi.chitchat.core.session.SessionState
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Reporting R2: the message report in the real room and DM screens (reporting.md; TL and PL rulings of 2026-11-20). */
class ReportJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = ReceivedFiles.session()
    private val rooms = ReceivedRooms(session)
    private val room = ConversationViewModel(rooms)
    private val directs = ReceivedDirects(session)
    private val direct = DirectThreadViewModel(directs)
    private val fake = Reports(session)
    private val reports = ReportViewModel(fake)
    private val ignoreCalls = CopyOnWriteArrayList<Long>()
    private val ignores = IgnorePersonViewModel(object : IgnoreOperations {
        override val session = this@ReportJourneyTest.session
        override suspend fun change(expected: SessionState, userId: Long, ignored: Boolean): Set<Long> { ignoreCalls += userId; return setOf(userId) }
    })
    /** Swapping the screen out stands in for a navigation departure (the composition leaves; no configuration change). */
    private var screen by mutableStateOf("room")

    @After fun tearDown() { fake.hold?.complete(Unit); compose.runOnIdle { room.cancel(); direct.background(); reports.close() } }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun content() {
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalReports provides reports) {
            Box(Modifier.size(320.dp, 640.dp)) {
                when (screen) {
                    "room" -> ConversationScreen(room, 42, {}, ignores = ignores)
                    "dm" -> DirectThreadScreen(direct, 8, {})
                    else -> Unit
                }
            }
        } } }
    }
    private fun roomContent() { content(); compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy } }
    private fun dmContent() { screen = "dm"; content(); compose.waitUntil(5_000) { direct.state.value.loaded && !direct.state.value.busy } }

    /** Room rows are `message:{id}`, DM rows `direct:{id}`; the row is scrolled into the lazy list's composed range first. */
    private fun openActions(messageId: Long) {
        val label = text(R.string.message_actions)
        val row = if (screen == "dm") "direct:$messageId" else "message:$messageId"
        compose.onNodeWithTag(if (screen == "dm") "dm_timeline" else "conversation_timeline").performScrollToNode(hasTestTag(row))
        compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
            hasAnyAncestor(hasTestTag(row))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { present("message_actions") }
    }
    /** System Back closes the sheet; it stays the default, so Back keeps its coverage on both levels (TL). */
    private fun closeActions() { Espresso.pressBack(); compose.waitUntil(5_000) { !present("message_actions") } }

    /**
     * The sheet's own Cancel, for the DM gating case only: its first system Back once didn't close the sheet on API36
     * (run 44831339730), a one-off, since other Back closes passed in the same run. [backClosesTheMessageActionsInADm]
     * keeps Back's own coverage in a DM (TL).
     */
    private fun cancelActions() {
        compose.onNode(androidx.compose.ui.test.hasText(text(R.string.cancel)) and hasAnyAncestor(hasTestTag("message_actions"))).performClick()
        compose.waitUntil(5_000) { !present("message_actions") }
    }
    private fun report(messageId: Long) {
        openActions(messageId)
        compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
        compose.waitUntil(5_000) { present("report_dialog") }
    }
    private fun choose(category: ReportCategory) = compose.onNodeWithTag("report_category:${category.wire}").performScrollTo().performClick()

    @Test fun reportShowsOnlyWhereTheServerAllowsInARoom() {
        rooms.messages = listOf(roomMessage(3, "hello"),
            roomMessage(4, "mine").let { io.github.thiesi.chitchat.core.conversation.RoomMessage(4, 42, 7, "Alice", "text", "mine", false, it.createdAt) },
            roomMessage(5, null, photo(70)))
        roomContent()
        openActions(3); assertTrue("Someone else's message", present("message_report_action")); closeActions()
        openActions(4); assertTrue("Never your own (pending or unknown delivery is your own too)", !present("message_report_action")); closeActions()
        openActions(5); assertTrue("An attachment-only message is reportable (TL)", present("message_report_action")); closeActions()
    }

    @Test fun reportShowsOnlyOnMessagesYouReceivedInADm() {
        directs.rows = listOf(dm(3, "hi", null), dm(4, "mine", null, outgoing = true), dm(5, null, file(71, "notes.pdf")))
        dmContent()
        openActions(3); assertTrue(present("message_report_action")); cancelActions()
        openActions(4); assertTrue("Only the recipient reports a DM", !present("message_report_action")); cancelActions()
        openActions(5); assertTrue("An attachment-only DM", present("message_report_action")); cancelActions()
    }

    @Test fun backClosesTheMessageActionsInADm() {
        directs.rows = listOf(dm(3, "hi", null))
        dmContent()
        openActions(3)
        Espresso.pressBack()
        compose.waitUntil(5_000) { !present("message_actions") }
    }

    @Test fun theFormNeedsACategoryAndCountsLikeTheServer() {
        rooms.messages = listOf(roomMessage(3, "hello"))
        roomContent(); report(3)
        compose.onNodeWithTag("report_send").assertIsNotEnabled()
        choose(ReportCategory.SPAM)
        compose.onNodeWithTag("report_send").assertIsEnabled()
        compose.onNodeWithTag("report_counter").assertTextEquals(text(R.string.report_counter, 0))
        compose.onNodeWithTag("report_details").performScrollTo().performTextInput("a".repeat(1_000))
        assertTrue("No announcement under the limit", !present("report_counter_crossing"))
        compose.onNodeWithTag("report_details").performTextReplacement("a".repeat(1_012))
        compose.onNodeWithTag("report_counter").assertTextEquals(text(R.string.report_counter_too_long, 1_012))
        compose.onNodeWithTag("report_send").assertIsNotEnabled()
        // TalkBack hears the crossing once: its live node's text doesn't change with each character.
        val crossing = compose.onNodeWithTag("report_counter_crossing", useUnmergedTree = true)
        crossing.assertContentDescriptionEquals(text(R.string.report_counter_too_long, 1_012))
        assertEquals(LiveRegionMode.Polite, crossing.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        compose.onNodeWithTag("report_details").performTextReplacement("a".repeat(1_013))
        crossing.assertContentDescriptionEquals(text(R.string.report_counter_too_long, 1_012))
        assertEquals("Never truncated", 1_013, reports.state.value!!.details.length)
    }

    @Test fun whoSeesItForARoomADmAndAnAttachment() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent(); report(3)
        compose.onNodeWithTag("report_who").assertTextEquals(text(R.string.report_who_room, "Lounge"))
        compose.onNodeWithTag("report_who_attachment").assertTextEquals(text(R.string.report_who_attachment))
        compose.onNodeWithTag("report_excerpt").assertTextEquals(text(R.string.report_photo))
        compose.onNodeWithTag("report_cancel").performClick()
        compose.waitUntil(5_000) { !present("report_dialog") }
        directs.rows = listOf(dm(4, "hi", null))
        compose.runOnIdle { screen = "dm" }
        compose.waitUntil(5_000) { direct.state.value.loaded && !direct.state.value.busy && direct.state.value.messages.isNotEmpty() }
        report(4)
        compose.onNodeWithTag("report_who").assertTextEquals(text(R.string.report_who_dm))
        assertTrue(!present("report_who_attachment"))
    }

    @Test fun a201ClosesTheFormShowsTheLineAndOffersIgnoreWithoutApplyingIt() {
        rooms.messages = listOf(roomMessage(3, "hello"))
        roomContent(); report(3)
        choose(ReportCategory.HARASSMENT)
        compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { present("report_sheet") }
        assertTrue(!present("report_dialog"))
        compose.onNodeWithTag("report_outcome").assertTextEquals(text(R.string.report_sent_room, "Lounge"))
        assertTrue("Nothing applied automatically", ignoreCalls.isEmpty())
        compose.onNodeWithTag("report_sheet_done").performClick()
        compose.waitUntil(5_000) { !present("report_sheet") }
        assertTrue("The line stays until leaving", present("report_outcome"))
        assertEquals(listOf(ReportTarget.RoomMessage(3)), fake.sends.map { it.first })
    }

    @Test fun anAlreadyIgnoredSenderGetsNoSheet() {
        rooms.ignored = setOf(8)
        rooms.messages = listOf(roomMessage(3, "hello"))
        roomContent()
        // An ignored sender's message is collapsed until the person reveals it (M4c3b); then it can be reported.
        compose.onNodeWithText(text(R.string.conversation_ignored)).performScrollTo().performClick()
        report(3)
        choose(ReportCategory.SPAM); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { present("report_outcome") }
        compose.waitForIdle()
        assertTrue("No sheet when Ignore doesn't apply (PL)", !present("report_sheet"))
    }

    @Test fun aBlockedPeerGetsNoSheetAndOtherwiseBlockOpensTheExistingConfirmation() {
        directs.blocked = true
        directs.rows = listOf(dm(3, "hi", null))
        dmContent(); report(3)
        choose(ReportCategory.SPAM); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { present("report_outcome") }
        compose.waitForIdle()
        assertTrue(!present("report_sheet"))
    }

    /** Review: Ignore {name} on the sheet opens the existing ignore dialog for the sender; its own confirm applies it. */
    @Test fun ignoreOnTheSheetOpensTheIgnoreDialogForTheSender() {
        rooms.messages = listOf(roomMessage(3, "hello"))
        roomContent(); report(3)
        choose(ReportCategory.HARASSMENT); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { present("report_sheet") }
        compose.onNodeWithTag("report_sheet_action").performClick()
        compose.waitUntil(5_000) { present("room_person_dialog") }
        assertTrue("The sheet closes", !present("report_sheet"))
        assertTrue("Nothing applied before the dialog's own confirm", ignoreCalls.isEmpty())
        compose.onNodeWithTag("room_ignore_change").performClick()
        compose.waitUntil(5_000) { ignoreCalls.isNotEmpty() }
        assertEquals("The reported message's sender", listOf(8L), ignoreCalls.toList())
    }

    /** Review: Block {name} on the DM sheet opens the existing block confirmation, which applies nothing by itself. */
    @Test fun blockOnTheDmSheetOpensTheBlockConfirmation() {
        directs.rows = listOf(dm(3, "hi", null))
        dmContent(); report(3)
        choose(ReportCategory.SPAM); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { present("report_sheet") }
        compose.onNodeWithTag("report_sheet_action").performClick()
        compose.waitUntil(5_000) { present("dm_confirm_block") }
        assertTrue("The sheet closes", !present("report_sheet"))
    }

    @Test fun whileSendingTheFormCantBeClosed() {
        rooms.messages = listOf(roomMessage(3, "hello"))
        fake.hold = CompletableDeferred()
        roomContent(); report(3)
        choose(ReportCategory.SPAM); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { reports.state.value?.phase == ReportPhase.Sending }
        compose.onNodeWithTag("report_cancel").assertIsNotEnabled()
        compose.onNodeWithTag("report_send", useUnmergedTree = true).assertContentDescriptionEquals(text(R.string.report_sending_spoken))
        Espresso.pressBack()
        compose.waitForIdle()
        assertTrue("Back does nothing while sending", present("report_dialog"))
        fake.hold!!.complete(Unit)
        compose.waitUntil(5_000) { present("report_outcome") }
    }

    @Test fun sendAgainAfterUnknownAnsweredAlreadyReportedReadsAsSent() {
        rooms.messages = listOf(roomMessage(3, "hello"))
        fake.results += ReportResult.Unknown(ApiFailure(FailureKind.TIMEOUT))
        fake.results += ReportResult.AlreadyReported
        roomContent(); report(3)
        choose(ReportCategory.SPAM)
        compose.onNodeWithTag("report_details").performScrollTo().performTextInput("why")
        compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { present("report_send_again") }
        compose.onNodeWithTag("report_reason").assertTextEquals(text(R.string.report_reason, text(R.string.report_spam)))
        compose.onNodeWithTag("report_summary_details").assertTextEquals("why")
        assertTrue("A read-only summary, no field", !present("report_details"))
        compose.onNodeWithTag("report_cancel").assertTextEquals(text(R.string.report_close))
        compose.onNodeWithTag("report_send_again").performClick()
        compose.waitUntil(5_000) { present("report_outcome") }
        compose.onNodeWithTag("report_outcome").assertTextEquals(text(R.string.report_sent_room, "Lounge"))
        assertEquals("The same frozen report", fake.sends[0], fake.sends[1])
    }

    @Test fun leavingWhileSendingKeepsItAndReturnShowsSendingThenTheOutcome() {
        rooms.messages = listOf(roomMessage(3, "hello"))
        fake.hold = CompletableDeferred()
        roomContent(); report(3)
        choose(ReportCategory.SPAM); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { reports.state.value?.phase == ReportPhase.Sending }
        compose.runOnIdle { screen = "none" }
        compose.waitForIdle()
        assertEquals("Leaving never cancels it", ReportPhase.Sending, reports.state.value?.phase)
        compose.runOnIdle { screen = "room" }
        compose.waitUntil(5_000) { present("report_sending_line") }
        assertTrue("The dialog isn't recreated", !present("report_dialog"))
        fake.hold!!.complete(Unit)
        compose.waitUntil(5_000) { present("report_outcome") }
        assertTrue("No sheet on return (PL)", !present("report_sheet"))
    }

    @Test fun aBackgroundWhileSendingOffersReviewAndTheSummary() {
        rooms.messages = listOf(roomMessage(3, "hello"))
        fake.hold = CompletableDeferred()
        roomContent(); report(3)
        choose(ReportCategory.SPAM); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { reports.state.value?.phase == ReportPhase.Sending }
        compose.runOnIdle { reports.backgrounded() }
        compose.waitUntil(5_000) { present("report_review") }
        compose.onNodeWithTag("report_review").performClick()
        compose.waitUntil(5_000) { present("report_send_again") }
        compose.onNodeWithTag("report_cancel").performClick()
        compose.waitUntil(5_000) { !present("report_dialog") }
        assertTrue("Close also dismisses the notice", !present("report_unknown_notice"))
    }

    @Test fun aReportElsewhereWhileOneIsSendingSaysSo() {
        rooms.messages = listOf(roomMessage(3, "hello"), roomMessage(5, "again"))
        fake.hold = CompletableDeferred()
        roomContent(); report(3)
        choose(ReportCategory.SPAM); compose.onNodeWithTag("report_send").performClick()
        compose.waitUntil(5_000) { reports.state.value?.phase == ReportPhase.Sending }
        compose.runOnIdle { screen = "none" }; compose.waitForIdle()
        compose.runOnIdle { screen = "room" }
        compose.waitUntil(5_000) { present("report_sending_line") }
        openActions(5)
        compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
        compose.waitUntil(5_000) { present("report_busy") }
        assertTrue("The sheet closed", !present("message_actions"))
        fake.hold!!.complete(Unit)
        compose.waitUntil(5_000) { !present("report_busy") }
    }

    private class Reports(override val session: kotlinx.coroutines.flow.StateFlow<SessionState>) : ReportOperations {
        val sends = CopyOnWriteArrayList<Triple<ReportTarget, ReportCategory, String?>>()
        val results = ArrayDeque<ReportResult>()
        @Volatile var hold: CompletableDeferred<Unit>? = null
        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?): ReportResult {
            sends += Triple(target, category, details)
            hold?.await()
            return synchronized(results) { results.removeFirstOrNull() } ?: ReportResult.Sent(1)
        }
        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
    }
}
