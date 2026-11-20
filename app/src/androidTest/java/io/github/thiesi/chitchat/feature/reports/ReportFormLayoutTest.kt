package io.github.thiesi.chitchat.feature.reports

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
            compose.tapNativeInput("report_details", imeBottom = { imeBottom })
            compose.onNodeWithTag("report_details").performTextInput("why")
            compose.waitForIdle()
            compose.onNodeWithTag("report_send").assertIsDisplayed()
            compose.onNodeWithTag("report_cancel").assertIsDisplayed()
        }
    }
}
