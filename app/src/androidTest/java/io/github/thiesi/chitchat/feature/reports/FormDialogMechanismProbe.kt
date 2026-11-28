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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import io.github.thiesi.chitchat.core.ui.KeepProbe
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * DIAGNOSTIC (never merged), #372 mechanism. The same steps as FormDialogA11yTest (200%, a pinned inner scroll to the field,
 * a native tap, one column with the keyboard up) under each runtime variant of KeepProbe.variant. Per state it logs the
 * field's and the body's first child's window position against their drawn top (the skew), the scroll values, SHOW_ON_SCREEN
 * on the visible field, and a dump of the dialog window's accessibility nodes. Logs only (CCMMech); never asserts.
 * Assume-gated (a3Probe=run).
 */
class FormDialogMechanismProbe {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val session = ReceivedFiles.session()
    private val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
    private val room = ConversationViewModel(rooms)
    private val operations = object : ReportOperations {
        override val session = this@FormDialogMechanismProbe.session
        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?) = ReportResult.Sent(1)
        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
    }
    private val ime = { focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } }

    @After fun tearDown() {
        if (focusedWindowIme().visible) androidx.test.espresso.Espresso.closeSoftKeyboard()
        FontScaleTestActivity.requestedFontScale = 1f
        instrumentation.runOnMainSync { KeepProbe.variant = "main"; KeepProbe.nudge.intValue = 0; room.cancel() }
    }

    private fun gate() = assumeTrue(InstrumentationRegistry.getArguments().getString("a3Probe") == "run")
    private fun log(line: String) { android.util.Log.i("CCMMech", line) }
    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun mode() = if (present("form_dialog_column")) "column" else "pinned"

    private fun automation() = instrumentation.uiAutomation.also { it.serviceInfo = it.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS } }

    /** The dialog window's nodes in pre-order (our package's application window with the highest layer). */
    private fun dialogNodes(): List<Pair<Int, AccessibilityNodeInfo>> {
        val window = automation().windows.filter { w -> w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            w.root?.packageName?.toString() == context.packageName }.maxByOrNull { it.layer } ?: return emptyList()
        val nodes = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            node ?: return
            node.refresh(); nodes += depth to node
            for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
        }
        walk(window.root, 0)
        return nodes
    }

    private fun AccessibilityNodeInfo.screen(): android.graphics.Rect = android.graphics.Rect().also { getBoundsInScreen(it) }

    /** PL and TL: per node, everything TalkBack reads or offers (class, text, description, focusability, actions, order). */
    private fun dump(case: String, state: String) {
        val nodes = dialogNodes()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) } ?: return
        val folder = java.io.File(output, "ccm-372-mech").apply { mkdirs() }
        java.io.File(folder, "$case-$state.txt").writeText(nodes.withIndex().joinToString("\n") { (order, pair) ->
            val (depth, n) = pair
            val actions = n.actionList.joinToString(",") { a -> a.label?.let { "${a.id}:$it" } ?: "${a.id}" }
            "#$order d=$depth ${n.className?.toString()?.substringAfterLast('.')} text=${n.text?.toString()?.take(60)} " +
                "desc=${n.contentDescription?.toString()?.take(60)} hint=${n.hintText?.toString()?.take(60)} " +
                "state=${n.stateDescription?.toString()?.take(40)} pane=${n.paneTitle} heading=${n.isHeading} bounds=${n.screen()} " +
                "visible=${n.isVisibleToUser} important=${n.isImportantForAccessibility} focusable=${n.isFocusable} " +
                "srFocusable=${n.isScreenReaderFocusable} clickable=${n.isClickable} editable=${n.isEditable} " +
                "scrollable=${n.isScrollable} collection=${n.collectionInfo?.let { "${it.rowCount}x${it.columnCount}" }} " +
                "travBefore=${n.traversalBefore != null} travAfter=${n.traversalAfter != null} children=${n.childCount} actions=[$actions]"
        })
        log("DUMP $case $state nodes=${nodes.size} file=ccm-372-mech/$case-$state.txt")
    }

    private fun place(node: SemanticsNodeInteraction): String = runCatching {
        val s = node.fetchSemanticsNode()
        "pos=${s.positionInWindow.y} drawn=${s.boundsInWindow.top} skew=${s.boundsInWindow.top - s.positionInWindow.y}"
    }.getOrElse { "absent(${it.javaClass.simpleName})" }

    private fun outerNow(): Float? = runCatching { compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value() }.getOrNull()

    private fun state(case: String, label: String, field: () -> SemanticsNodeInteraction, first: () -> SemanticsNodeInteraction) {
        compose.waitForIdle()
        log("STATE $case $label mode=${mode()} ime=${focusedWindowIme().visible} field[${place(field())}] first[${place(first())}] " +
            "inner=${KeepProbe.inner?.value} innerMax=${KeepProbe.inner?.maxValue} outer=${KeepProbe.outer?.value} outerAxis=${outerNow()}")
    }

    /** SHOW_ON_SCREEN on the visible field: the outer value and the field's drawn top before and after. */
    private fun show(case: String, field: () -> SemanticsNodeInteraction, match: (AccessibilityNodeInfo) -> Boolean) {
        val before = KeepProbe.outer?.value
        val drawnBefore = runCatching { field().fetchSemanticsNode().boundsInWindow.top }.getOrNull()
        val target = dialogNodes().map { it.second }.firstOrNull(match)
        val rect = target?.screen()
        val done = target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        compose.waitForIdle()
        val drawnAfter = runCatching { field().fetchSemanticsNode().boundsInWindow.top }.getOrNull()
        log("SHOW $case node=${if (target == null) "absent" else "present"} visible=${target?.isVisibleToUser} a11yRect=$rect done=$done " +
            "outer=$before->${KeepProbe.outer?.value} drawn=$drawnBefore->$drawnAfter")
    }

    private fun run(variant: String, case: String, field: () -> SemanticsNodeInteraction, first: () -> SemanticsNodeInteraction,
                    match: (AccessibilityNodeInfo) -> Boolean, toPinnedScroll: () -> Unit, tap: () -> Unit) {
        val name = "$variant-$case"
        toPinnedScroll()
        state(name, "pinned-scrolled", field, first)
        dump(name, "pinned")
        tap()
        state(name, "column", field, first)
        dump(name, "column")
        if (variant == "D1") {
            instrumentation.runOnMainSync { KeepProbe.nudge.intValue = 1 }
            state(name, "nudge1", field, first)
            instrumentation.runOnMainSync { KeepProbe.nudge.intValue = 0 }
            state(name, "nudge0", field, first)
        }
        show(name, field, match)
        state(name, "after-show", field, first)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        state(name, "back-pinned", field, first)
        dump(name, "back-pinned")
    }

    private fun report(variant: String) {
        gate()
        instrumentation.runOnMainSync { KeepProbe.variant = variant }
        log("CASE $variant-report")
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
            run(variant, "report", { compose.onNodeWithTag("report_details", useUnmergedTree = true) },
                { compose.onNodeWithTag("report_body", useUnmergedTree = true) }, { n -> n.isEditable },
                { compose.onNodeWithTag("report_details").performScrollTo() },
                { compose.tapNativeInput("report_details", imeBottom = ime)
                  compose.onNodeWithTag("report_details").performTextInput("Posted the same link") })
        }
    }

    private fun stepUp(variant: String) {
        gate()
        instrumentation.runOnMainSync { KeepProbe.variant = variant }
        log("CASE $variant-stepup")
        FontScaleTestActivity.requestedFontScale = 2f
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { ChitChatTheme(dark = false) {
                StepUpDialog(stringResource(R.string.step_up_title_export), VerificationMethods(false, true, 0), busy = false,
                    errorMessage = null, onVerify = { _, _ -> }, onCancel = {})
            } } }
            compose.waitForIdle()
            val explanation = context.getString(R.string.step_up_password_explanation)
            run(variant, "stepup", { compose.onNodeWithTag("step_up_secret", useUnmergedTree = true) },
                { compose.onNodeWithText(explanation, useUnmergedTree = true) }, { n -> n.isEditable },
                { compose.onNodeWithTag("step_up_secret").performScrollTo() },
                { compose.tapNativeInput("step_up_secret", imeBottom = ime) })
        }
    }

    @Test fun mainReport() = report("main")
    @Test fun d1Report() = report("D1")
    @Test fun d2Report() = report("D2")
    @Test fun d4Report() = report("D4")
    @Test fun d5Report() = report("D5")
    @Test fun mainStepUp() = stepUp("main")
    @Test fun d1StepUp() = stepUp("D1")
    @Test fun d2StepUp() = stepUp("D2")
    @Test fun d4StepUp() = stepUp("D4")
    @Test fun d5StepUp() = stepUp("D5")
}
