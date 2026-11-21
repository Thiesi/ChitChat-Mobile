package io.github.thiesi.chitchat.feature.reports

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PL (#326 label fix): the details field's accessible name is the question, and its content description must not replace
 * the typed text. Read through UiAutomation, the framework path an accessibility service such as TalkBack uses: the
 * filled field's node keeps its text as the text, with the question as the separate name; the empty field has the name.
 */
class ReportDetailsAccessibilityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = ReceivedFiles.session()
    private val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
    private val room = ConversationViewModel(rooms)
    private val operations = object : ReportOperations {
        override val session = this@ReportDetailsAccessibilityTest.session
        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?) = ReportResult.Sent(1)
        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
    }

    @After fun tearDown() { InstrumentationRegistry.getInstrumentation().runOnMainSync { room.cancel() } }

    /** The active window's editable node, as an accessibility service sees it; null until it's there. */
    private fun editable(): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isEditable) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }

    @Test fun theFieldKeepsItsTypedTextBesideTheQuestionAsItsName() {
        val reports = ReportViewModel(operations)
        val question = context.getString(R.string.report_details_label)
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
                } } }
            }
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            val label = context.getString(R.string.message_actions)
            compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
                hasAnyAncestor(hasTestTag("message:3"))).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("message_report_action").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("report_details").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("report_details").performScrollTo()
            compose.waitUntil(5_000) { editable() != null }
            val empty = checkNotNull(editable())
            assertEquals("The empty field's name is the question", question, empty.contentDescription?.toString())
            assertTrue("The empty field has no typed text (${empty.text})", empty.text.isNullOrEmpty() || empty.text.toString() == question)
            val typed = "Posted the same link in 3 rooms"
            compose.onNodeWithTag("report_details").performTextInput(typed)
            compose.waitUntil(5_000) { editable()?.text?.toString() == typed }
            val filled = checkNotNull(editable())
            assertEquals("The typed text stays the node's text", typed, filled.text?.toString())
            assertEquals("The question stays the separate name", question, filled.contentDescription?.toString())
        }
    }
}
