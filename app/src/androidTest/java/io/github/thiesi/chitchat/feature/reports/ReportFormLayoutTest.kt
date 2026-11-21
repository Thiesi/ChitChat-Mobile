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
            scenario.recreate()
            compose.waitUntil(5_000) { present("report_dialog") }
            assertEquals("A configuration change keeps the category (TL)", ReportCategory.HATE, reports.state.value?.category)
            compose.onNodeWithTag("report_details").performScrollTo().assertTextEquals(
                context.getString(R.string.report_details_label), "kept why")
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
