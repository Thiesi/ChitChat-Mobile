package io.github.thiesi.chitchat.feature.reports

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import io.github.thiesi.chitchat.core.account.VerificationMethods
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
import io.github.thiesi.chitchat.feature.auth.StepUpDialog
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.tapNativeInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #372 (TL and PL): in FormDialog's one-column mode, reached after a pinned inner scroll, a field's window position agrees
 * with where it's drawn, and the accessibility service's SHOW_ON_SCREEN on an already visible node moves nothing. On main
 * before the fix the stale inner value skewed the window position, and SHOW_ON_SCREEN scrolled the report field about 1,040
 * px away (step-up: 46 px; runs 46156251647 and 46159151874). The keyboard comes from a native tap with the strict IME proof;
 * UiAutomation is set up as TextFieldNodeNamesTest does it.
 */
class FormDialogA11yTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val session = ReceivedFiles.session()
    private val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
    private val room = ConversationViewModel(rooms)
    private val operations = object : ReportOperations {
        override val session = this@FormDialogA11yTest.session
        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?) = ReportResult.Sent(1)
        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
    }
    private val ime = { focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } }

    @After fun tearDown() {
        if (focusedWindowIme().visible) androidx.test.espresso.Espresso.closeSoftKeyboard()
        FontScaleTestActivity.requestedFontScale = 1f
        instrumentation.runOnMainSync { room.cancel() }
    }

    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun automation() = instrumentation.uiAutomation.also { it.serviceInfo = it.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS } }

    /** The dialog window's nodes: our package's application window with the highest layer (the dialog). */
    private fun dialogNodes(): List<AccessibilityNodeInfo> {
        val window = automation().windows.filter { w -> w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            w.root?.packageName?.toString() == context.packageName }.maxByOrNull { it.layer } ?: return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            node.refresh(); nodes += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(window.root)
        return nodes
    }

    private fun outerValue(): Float = compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** In one column, [tag]'s window position equals its drawn top, and SHOW_ON_SCREEN on each [targets] node moves nothing. */
    private fun assertOneColumnStable(what: String, tag: String, targets: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>>) {
        compose.waitForIdle()
        assertTrue("$what: one column with the keyboard up", present("form_dialog_column") && focusedWindowIme().visible)
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("$what: the window position equals the drawn top (boundsInWindow ${node.boundsInWindow})",
            node.boundsInWindow.top, node.positionInWindow.y, 1f)
        for ((name, match) in targets) {
            val before = outerValue()
            val boundsBefore = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val target = dialogNodes().firstOrNull(match)
            assertTrue("$what: $name has an accessibility node (visible)", target != null && target.isVisibleToUser)
            target!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            compose.waitForIdle()
            assertEquals("$what: SHOW_ON_SCREEN on the visible $name leaves the scroll where it was", before, outerValue(), 0.5f)
            assertEquals("$what: … and $tag where it was drawn", boundsBefore,
                compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow)
        }
    }

    @Test fun theReportFormInOneColumnAfterAPinnedScrollHasNoSkewAndShowOnScreenDoesNotJump() {
        FontScaleTestActivity.requestedFontScale = 2f
        val reports = ReportViewModel(operations)
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalReports provides reports) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
            } } } }
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            val label = context.getString(R.string.message_actions)
            compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
                hasAnyAncestor(hasTestTag("message:3"))).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { present("message_report_action") }
            compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
            compose.waitUntil(5_000) { present("report_dialog") }
            compose.onNodeWithTag("report_category:${ReportCategory.SPAM.wire}").performScrollTo().performClick()
            compose.onNodeWithTag("report_details").performScrollTo() // Pinned: the inner scroller moves to the field.
            compose.tapNativeInput("report_details", imeBottom = ime)
            compose.onNodeWithTag("report_details").performTextInput("Posted the same link")
            assertOneColumnStable("Report", "report_details", listOf(
                "field" to { n -> n.isEditable },
                "counter" to { n -> n.text?.toString()?.contains("/ 1000") == true }))
        }
    }

    @Test fun stepUpInOneColumnAfterAPinnedScrollHasNoSkewAndShowOnScreenDoesNotJump() {
        FontScaleTestActivity.requestedFontScale = 2f
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { ChitChatTheme(dark = false) {
                StepUpDialog(stringResource(R.string.step_up_title_export), VerificationMethods(false, true, 0), busy = false,
                    errorMessage = null, onVerify = { _, _ -> }, onCancel = {})
            } } }
            compose.waitForIdle()
            compose.onNodeWithTag("step_up_secret").performScrollTo() // Pinned: the inner scroller moves to the field.
            compose.tapNativeInput("step_up_secret", imeBottom = ime)
            assertOneColumnStable("Step-up", "step_up_secret", listOf("field" to { n -> n.isEditable }))
        }
    }
}
