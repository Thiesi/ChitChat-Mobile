package io.github.thiesi.chitchat.feature.reports

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
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
import io.github.thiesi.chitchat.core.ui.KeepProbe
import io.github.thiesi.chitchat.core.ui.ReceivedFiles
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.core.ui.ReceivedRooms
import io.github.thiesi.chitchat.core.ui.focusedWindowIme
import io.github.thiesi.chitchat.feature.auth.StepUpDialog
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.DiagRecording
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.tapNativeInput
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * DIAGNOSTIC (never merged), #372 (TL and PL, 2026-11-28): the report dialog's and step-up's accessibility node bounds
 * against the semantics bounds, in pinned and one-column states (the one-column state after a pinned inner scroll is where
 * positionInWindow was measured off by the inner scroller's value), the production accessibility actions (SHOW_ON_SCREEN
 * on visible and partly visible nodes, accessibility focus, SCROLL_FORWARD/BACKWARD), the native-tap offset assumption,
 * and the coordinator chain. Logs only (CCMBounds); never asserts, so every state is recorded. Assume-gated (a3Probe=run).
 */
class FormDialogBoundsProbe {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val session = ReceivedFiles.session()
    private val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, "hello")) }
    private val room = ConversationViewModel(rooms)
    private val operations = object : ReportOperations {
        override val session = this@FormDialogBoundsProbe.session
        override suspend fun send(expected: SessionState, target: ReportTarget, category: ReportCategory, details: String?) = ReportResult.Sent(1)
        override suspend fun personFacts(expected: SessionState, target: ReportTarget.Person): ReportFacts? = null
    }

    @After fun tearDown() {
        if (focusedWindowIme().visible) androidx.test.espresso.Espresso.closeSoftKeyboard()
        FontScaleTestActivity.requestedFontScale = 1f
        instrumentation.runOnMainSync { room.cancel() }
    }

    private fun gate() = assumeTrue(InstrumentationRegistry.getArguments().getString("a3Probe") == "run")
    private fun log(line: String) { android.util.Log.i("CCMBounds", line); KeepProbe.log("B372 $line") }
    private val ime = { focusedWindowIme().let { if (it.visible) maxOf(1, it.imeBottom) else 0 } }
    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** A screencap of the whole screen at this state (the PL's per-state capture), named after the case and state. */
    private fun shot(case: String, state: String) {
        instrumentation.waitForIdleSync()
        DiagRecording.shell("screencap -p /data/local/tmp/ccm-372-$case-$state.png")
        log("SHOT $case $state")
    }

    // ---- The accessibility side (UiAutomation, as TextFieldNodeNamesTest does) ----

    private fun automation() = instrumentation.uiAutomation.also { it.serviceInfo = it.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS } }

    /**
     * Follow-up (TL, PL): the dialog window's nodes, refreshed. The window is chosen by type and package, never by a text
     * that may scroll away (0186fa0 chose it by "Send report", which in one column is off screen and has no node): the
     * TYPE_APPLICATION window of our package with the highest layer (the dialog sits above the activity). [marker] is
     * unused, kept so the call sites stay as they were.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun windowNodes(marker: String): List<AccessibilityNodeInfo> {
        val window = automation().windows.filter { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString() == context.packageName
        }.maxByOrNull { it.layer } ?: return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            node.refresh(); nodes += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(window.root)
        return nodes
    }

    /** Every window, per state: id, type, layer, title, focus, its root's package, class and child count. */
    private fun windows(case: String, state: String) {
        val text = automation().windows.joinToString(" | ") { w ->
            val r = w.root
            "id=${w.id} type=${w.type} layer=${w.layer} title=${w.title?.toString()?.take(40)} active=${w.isActive} " +
                "focused=${w.isFocused} root=${r?.packageName}/${r?.className?.toString()?.substringAfterLast('.')} children=${r?.childCount}"
        }
        log("WINDOWS $case $state $text rootInActiveWindow=${automation().rootInActiveWindow?.packageName}")
    }

    /** A dump file of the dialog window's node tree (class, text, description, bounds, flags), per state. */
    private fun dump(case: String, state: String, nodes: List<AccessibilityNodeInfo>) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: return
        val folder = java.io.File(output, "ccm-372-dumps").apply { mkdirs() }
        java.io.File(folder, "$case-$state.txt").writeText(nodes.joinToString("\n") { n ->
            "${n.className?.toString()?.substringAfterLast('.')} text=${n.text?.toString()?.take(60)} desc=${n.contentDescription?.toString()?.take(60)} " +
                "hint=${n.hintText?.toString()?.take(60)} bounds=${n.screen()} visible=${n.isVisibleToUser} clickable=${n.isClickable} " +
                "editable=${n.isEditable} scrollable=${n.isScrollable} focusable=${n.isFocusable} children=${n.childCount}"
        })
        log("DUMP $case $state nodes=${nodes.size} file=ccm-372-dumps/$case-$state.txt")
    }

    private fun AccessibilityNodeInfo.screen(): android.graphics.Rect = android.graphics.Rect().also { getBoundsInScreen(it) }

    /** The dialog window's decor-view location on screen (the window offset). */
    private fun decorOffset(): IntArray {
        val out = IntArray(2)
        instrumentation.runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }.getLocationOnScreen(out)
        }
        return out
    }

    private fun ranges(): String = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true).fetchSemanticsNodes().joinToString(",") {
        val r = it.config[SemanticsProperties.VerticalScrollAxisRange]; "${r.value()}/${r.maxValue()}@${it.boundsInWindow.top}" }

    private fun mode() = if (present("form_dialog_pinned")) "pinned" else if (present("form_dialog_column")) "column" else "none"

    /** The labelPlace-style top: the node in the tagged viewport container's own coordinates. */
    private fun viewportTop(tag: String): Float? = runCatching {
        val viewport = compose.onNodeWithTag(if (present("form_dialog_pinned")) "form_dialog_body_viewport" else "form_dialog_viewport",
            useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        viewport.localPositionOf(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates, Offset.Zero).y
    }.getOrNull()

    /**
     * (a) and (c): per node, the semantics side (boundsInWindow, positionInWindow, positionOnScreen, viewport-local top) and
     * its accessibility node's boundsInScreen; delta = a11y − (boundsInWindow + the decor offset). OK when every side is
     * within 1 px. (c): positionOnScreen − positionInWindow against the decor offset.
     */
    private fun state(case: String, state: String, pairs: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>>, marker: String) {
        compose.waitForIdle()
        val decor = decorOffset()
        val nodes = windowNodes(marker)
        log("STATE $case $state mode=${mode()} ime=${ime()} decor=${decor[0]},${decor[1]} ranges=${ranges()} " +
            "innerValue=${KeepProbe.inner?.value} outerValue=${KeepProbe.outer?.value} a11yNodes=${nodes.size}")
        windows(case, state)
        dump(case, state, nodes)
        // What TalkBack reads on the field, and whether the separate label has its own node (or is merged into the field).
        val field = nodes.firstOrNull { it.isEditable }
        val labelText = context.getString(R.string.report_details_label)
        val labelNodes = nodes.filter { it.text?.toString() == labelText || it.contentDescription?.toString() == labelText }
        log("READS $case $state field=${field?.let { "text=${it.text?.toString()?.take(40)} desc=${it.contentDescription?.toString()?.take(60)} " +
            "hint=${it.hintText?.toString()?.take(60)} labeledBy=${it.labeledBy?.text?.toString()?.take(40)}" } ?: "absent"} " +
            "labelOwnNodes=${labelNodes.size} labelNodeRects=${labelNodes.joinToString(";") { it.screen().toShortString() }}")
        // Buttons: the node carrying the text, and its clickable ancestor (what explore-by-touch and the outline use).
        for (name in listOf(R.string.report_send, R.string.cancel, R.string.verify_and_continue)) {
            val text = context.getString(name)
            val textNode = nodes.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text } ?: continue
            var clickable: AccessibilityNodeInfo? = textNode
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            log("BUTTON $case $state \"$text\" textNode=${textNode.screen().toShortString()} textNodeClickable=${textNode.isClickable} " +
                "clickableAncestor=${clickable?.screen()?.toShortString()} sameNode=${clickable == textNode}")
        }
        for ((tag, match) in pairs) {
            val sem = runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode() }.getOrNull()
            if (sem == null) { log("NODE $case $state $tag semantics=absent"); continue }
            val b = sem.boundsInWindow
            val pw = sem.positionInWindow; val ps = sem.positionOnScreen
            val a = nodes.firstOrNull(match)
            val r = a?.screen()
            val d = r?.let { listOf(it.left - (b.left + decor[0]), it.top - (b.top + decor[1]), it.right - (b.right + decor[0]), it.bottom - (b.bottom + decor[1])) }
            val ok = d?.all { kotlin.math.abs(it) <= 1f }
            val tapOffset = ps - pw
            val cOk = kotlin.math.abs(tapOffset.x - decor[0]) <= 1f && kotlin.math.abs(tapOffset.y - decor[1]) <= 1f
            log("NODE $case $state $tag boundsInWindow=${b.left},${b.top},${b.right},${b.bottom} positionInWindow=${pw.x},${pw.y} " +
                "positionOnScreen=${ps.x},${ps.y} size=${sem.size.width}x${sem.size.height} viewportTop=${viewportTop(tag)} " +
                "a11y=${r?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "absent"} visibleToUser=${a?.isVisibleToUser} " +
                "delta=${d?.joinToString(",")} aOk=$ok tapOffset=${tapOffset.x},${tapOffset.y} cOk=$cOk")
        }
        shot(case, state)
    }

    /** (e): the node's coordinator chain up to the root: each step's offset in its parent and its positionInWindow. */
    private fun chain(case: String, state: String, tag: String) {
        val sem = runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode() }.getOrNull() ?: return
        var c: androidx.compose.ui.layout.LayoutCoordinates? = sem.layoutInfo.coordinates
        var step = 0
        val out = StringBuilder()
        while (c != null && step < 80) {
            val parent = c.parentCoordinates
            val offset = parent?.localPositionOf(c, Offset.Zero)
            out.append(" [$step ${c.javaClass.simpleName} h=${c.size.height} off=${offset?.y} win=${c.positionInWindow().y}]")
            c = parent; step++
        }
        log("CHAIN $case $state $tag$out")
    }

    private fun scrollNode(nodes: List<AccessibilityNodeInfo>, from: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = from?.parent
        while (n != null) { if (n.isScrollable) return n; n = n.parent }
        return nodes.firstOrNull { it.isScrollable }
    }

    /** The label's place in the viewport and whether it's half (top inside the 8dp margin, bottom below the top). */
    private fun labelState(): String {
        val top = viewportTop("report_details_label") ?: return "label=absent"
        val h = runCatching { compose.onNodeWithTag("report_details_label", useUnmergedTree = true).fetchSemanticsNode().size.height }.getOrNull() ?: 0
        val margin = 8f * context.resources.displayMetrics.density
        val half = top + h > 0f && top < margin
        return "label=$top..${top + h} half=$half"
    }

    /** Re-run: puts the one-column scroller back at [outerValue] (each SHOW case starts from its own logged state). */
    private fun outerTo(outerValue: Float) {
        if (mode() != "column") return
        val now = compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        if (now != outerValue) runCatching { compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, outerValue - now) } }
        compose.waitForIdle()
    }

    private fun outerNow(): Float? = runCatching { compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value() }.getOrNull()

    /** (b) and PL item 5: SHOW_ON_SCREEN through the accessibility node; scroll values and the label before and after. */
    private fun showOnScreen(case: String, what: String, marker: String, match: (AccessibilityNodeInfo) -> Boolean) {
        val nodes = windowNodes(marker)
        val node = nodes.firstOrNull(match) ?: run { log("SHOW $case $what node=absent"); return }
        val before = "ranges=${ranges()} inner=${KeepProbe.inner?.value} outer=${KeepProbe.outer?.value} ${labelState()} a11y=${node.screen()}"
        DiagRecording.flash()
        val done = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        DiagRecording.hold(compose, 800)
        compose.waitForIdle()
        node.refresh()
        log("SHOW $case $what performed=$done before[$before] after[ranges=${ranges()} inner=${KeepProbe.inner?.value} " +
            "outer=${KeepProbe.outer?.value} ${labelState()} a11y=${node.screen()}]")
        DiagRecording.capture("show-$what")
    }

    /** (b): accessibility focus on the field; its focus rect (boundsInScreen) against the semantics bounds. */
    private fun touchExploration(): Boolean = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled

    /**
     * Follow-up: accessibility focus needs touch exploration (Compose's requestAccessibilityFocus returns false without it).
     * It's switched on for this step only and off again in a finally block, before any later native tap; both logged.
     */
    private fun focus(case: String, marker: String) {
        val automation = automation()
        val before = automation.serviceInfo
        try {
            automation.serviceInfo = before.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE }
            DiagRecording.hold(compose, 500)
            log("FOCUS $case touchExploration on=${touchExploration()}")
            val node = windowNodes(marker).firstOrNull { it.isEditable } ?: run { log("FOCUS $case field=absent"); return }
            val done = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            compose.waitForIdle(); node.refresh()
            val b = compose.onNodeWithTag("report_details", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val decor = decorOffset()
            log("FOCUS $case performed=$done focused=${node.isAccessibilityFocused} a11y=${node.screen()} " +
                "semantics+decor=${b.left + decor[0]},${b.top + decor[1]},${b.right + decor[0]},${b.bottom + decor[1]}")
            DiagRecording.capture("a11y-focus")
            node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv() }
            DiagRecording.hold(compose, 500)
            log("FOCUS $case touchExploration off=${!touchExploration()}")
        }
    }

    /** PL item 2: SCROLL_FORWARD until it stops, then SCROLL_BACKWARD until it stops, on the scroller holding the field. */
    private fun a11yScroll(case: String, which: String, marker: String) {
        for ((direction, action) in listOf("forward" to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            "backward" to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            var step = 0
            while (step < 30) {
                val nodes = windowNodes(marker)
                val scroller = scrollNode(nodes, nodes.firstOrNull { it.isEditable }) ?: run { log("SCROLL $case $which none"); return }
                val before = ranges()
                DiagRecording.flash()
                val done = scroller.performAction(action)
                DiagRecording.hold(compose, 500)
                compose.waitForIdle()
                val field = runCatching { compose.onNodeWithTag("report_details", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.top }.getOrNull()
                log("SCROLL $case $which $direction step=$step performed=$done before=$before after=${ranges()} " +
                    "inner=${KeepProbe.inner?.value} outer=${KeepProbe.outer?.value} fieldTopInWindow=$field ${labelState()} scroller=${scroller.screen()}")
                if (!done || before == ranges()) break
                step++
            }
            val visible = windowNodes(marker).filter { it.isVisibleToUser && !it.text.isNullOrBlank() }.joinToString("|") { it.text.toString().take(24) }
            log("SCROLLEND $case $which $direction visible=[$visible]")
            DiagRecording.capture("scroll-$which-$direction")
        }
    }

    private fun report() {
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
        val label = context.getString(R.string.message_actions)
        compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
            hasAnyAncestor(hasTestTag("message:3"))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { present("message_report_action") }
        compose.onNodeWithTag("message_report_action").performScrollTo().performClick()
        compose.waitUntil(5_000) { present("report_dialog") }
    }

    private val reportMarker get() = context.getString(R.string.report_send)
    private val reportPairs: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>> get() {
        val label = context.getString(R.string.report_details_label)
        val send = context.getString(R.string.report_send)
        val cancel = context.getString(R.string.cancel)
        return listOf(
            "report_details" to { n -> n.isEditable },
            "report_details_label" to { n -> n.text?.toString() == label },
            "report_counter" to { n -> n.text?.toString()?.contains("/ 1000") == true },
            "report_send" to { n -> n.text?.toString() == send || n.contentDescription?.toString() == send },
            "report_cancel" to { n -> n.text?.toString() == cancel || n.contentDescription?.toString() == cancel },
        )
    }

    private fun launchReport(scale: Float, reports: ReportViewModel, body: (ActivityScenario<FontScaleTestActivity>) -> Unit) {
        FontScaleTestActivity.requestedFontScale = scale
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { content(reports) } }
            body(scenario)
        }
    }

    @androidx.compose.runtime.Composable
    private fun content(reports: ReportViewModel) = ChitChatTheme(dark = false) {
        CompositionLocalProvider(LocalReports provides reports) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}) }
        }
    }

    /** The report states (a), (c), (e), then (b), PL items 5 and 2 in the skew state, then back to pinned and PL item 2 there. */
    private fun reportCase(case: String, scale: Float) {
        val reports = ReportViewModel(operations)
        launchReport(scale, reports) {
            report()
            compose.onNodeWithTag("report_category:${ReportCategory.SPAM.wire}").performScrollTo().performClick()
            state(case, "1-pinned-unscrolled", reportPairs, reportMarker)
            compose.onNodeWithTag("report_details").performScrollTo()
            state(case, "2-pinned-inner-scrolled", reportPairs, reportMarker)
            chain(case, "2-pinned-inner-scrolled", "report_details")
            compose.tapNativeInput("report_details", imeBottom = ime)
            compose.onNodeWithTag("report_details").performTextInput("Posted the same link")
            state(case, "3-after-tap-skew", reportPairs, reportMarker)
            chain(case, "3-after-tap-skew", "report_details")
            chain(case, "3-after-tap-skew", "report_details_label")
            focus(case, reportMarker)
            val start = outerNow()
            showOnScreen(case, "field-visible", reportMarker) { it.isEditable }
            start?.let { outerTo(it) } // Independent: the counter case starts from the same state (TL).
            showOnScreen(case, "counter-visible", reportMarker) { it.text?.toString()?.contains("/ 1000") == true }
            // PL: the field cut at its top (its top 20 px above the viewport), then at its bottom (20 px below), SHOW each.
            if (mode() == "column") {
                start?.let { outerTo(it) }
                viewportTop("report_details")?.let { top -> runCatching { compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top + 20f) } } }
                compose.waitForIdle()
                log("PARTLY $case field-cut-top fieldTop=${viewportTop("report_details")} ${labelState()} ranges=${ranges()}")
                showOnScreen(case, "field-cut-top", reportMarker) { it.isEditable }
                start?.let { outerTo(it) }
                val fieldTop = viewportTop("report_details")
                val fieldH = runCatching { compose.onNodeWithTag("report_details", useUnmergedTree = true).fetchSemanticsNode().size.height }.getOrNull()
                val viewportH = runCatching { compose.onNodeWithTag("form_dialog_viewport", useUnmergedTree = true).fetchSemanticsNode().size.height }.getOrNull()
                if (fieldTop != null && fieldH != null && viewportH != null) runCatching { compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, fieldTop + fieldH + 20f - viewportH) } }
                compose.waitForIdle()
                log("PARTLY $case field-cut-bottom fieldTop=${viewportTop("report_details")} fieldH=$fieldH viewportH=$viewportH ${labelState()} ranges=${ranges()}")
                showOnScreen(case, "field-cut-bottom", reportMarker) { it.isEditable }
                start?.let { outerTo(it) }
            }
            // PL item 5: the field with its label cut (the label's top 20 px above the viewport), then SHOW_ON_SCREEN.
            if (mode() == "column") {
                val top = viewportTop("report_details_label")
                if (top != null) runCatching { compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top + 20f) } }
                compose.waitForIdle()
                log("PARTLY $case label-cut ${labelState()} ranges=${ranges()}")
                showOnScreen(case, "field-label-cut", reportMarker) { it.isEditable }
                state(case, "4-column-outer-scrolled", reportPairs, reportMarker)
                // PL item 5: the counter partly under the viewport's bottom edge (half its height below), then SHOW_ON_SCREEN.
                val counterTop = viewportTop("report_counter")
                val counterH = runCatching { compose.onNodeWithTag("report_counter", useUnmergedTree = true).fetchSemanticsNode().size.height }.getOrNull()
                val viewportH = runCatching { compose.onNodeWithTag("form_dialog_viewport", useUnmergedTree = true).fetchSemanticsNode().size.height }.getOrNull()
                if (counterTop != null && counterH != null && viewportH != null) runCatching {
                    compose.onNodeWithTag("form_dialog_column", useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, counterTop + counterH / 2f - viewportH) } }
                compose.waitForIdle()
                log("PARTLY $case counter-bottom counterTop=${viewportTop("report_counter")} counterH=$counterH viewportH=$viewportH ranges=${ranges()}")
                showOnScreen(case, "counter-bottom-cut", reportMarker) { it.text?.toString()?.contains("/ 1000") == true }
            } else log("PARTLY $case skipped mode=${mode()} (no one-column state at this scale with the keyboard up)")
            a11yScroll(case, mode(), reportMarker)
            DiagRecording.flash() // Re-run: the sync strip right before the column -> pinned return (PL item 1).
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitUntil(5_000) { !focusedWindowIme().visible }
            state(case, "5-back-pinned", reportPairs, reportMarker)
            chain(case, "5-back-pinned", "report_details")
            showOnScreen(case, "counter-pinned", reportMarker) { it.text?.toString()?.contains("/ 1000") == true }
            a11yScroll(case, "pinned-after", reportMarker)
        }
    }

    private fun recorded(name: String, animations: Boolean, body: () -> Unit) {
        gate()
        DiagRecording.run(name, animations, hide = {}) { body() }
    }

    @Test fun diag372Report200Off() = recorded("ccm-372-report200-off", false) { reportCase("report200-off", 2f) }
    @Test fun diag372Report200On() = recorded("ccm-372-report200-on", true) { reportCase("report200-on", 2f) }
    @Test fun diag372Report100() { gate(); reportCase("report100", 1f) }

    /**
     * PL item 3: a one-column state without the keyboard after a pinned inner scroll: portrait 200% pinned, the field
     * scrolled into view, then landscape (one column at 200%). The test activity is recreated; the same view models are
     * composed again, so the dialog's saveable scroll state is what carries the inner value, if it does (logged).
     */
    private fun rotateCase(case: String) {
        val reports = ReportViewModel(operations)
        launchReport(2f, reports) { scenario ->
            report()
            compose.onNodeWithTag("report_category:${ReportCategory.SPAM.wire}").performScrollTo().performClick()
            compose.onNodeWithTag("report_details").performScrollTo()
            state(case, "1-portrait-pinned-inner-scrolled", reportPairs, reportMarker)
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                var o = 0; scenario.onActivity { o = it.resources.configuration.orientation }
                o == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            scenario.onActivity { it.setContent { content(reports) } }
            compose.waitUntil(5_000) { present("report_dialog") }
            state(case, "2-landscape-no-ime", reportPairs, reportMarker)
            chain(case, "2-landscape-no-ime", "report_details")
            showOnScreen(case, "field-landscape", reportMarker) { it.isEditable }
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    @Test fun diag372RotateOff() = recorded("ccm-372-rotate-off", false) { rotateCase("rotate-off") }
    @Test fun diag372RotateOn() = recorded("ccm-372-rotate-on", true) { rotateCase("rotate-on") }

    /** PL item 4: step-up at 200%, the field tapped (the one-column state with the keyboard), (a) and SHOW_ON_SCREEN. */
    private fun stepUpCase(case: String) {
        FontScaleTestActivity.requestedFontScale = 2f
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { ChitChatTheme(dark = false) {
                StepUpDialog(stringResource(R.string.step_up_title_export), VerificationMethods(false, true, 0), busy = false,
                    errorMessage = null, onVerify = { _, _ -> }, onCancel = {})
            } } }
            compose.waitForIdle()
            val marker = context.getString(R.string.verify_and_continue)
            val pairs: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>> = listOf("step_up_secret" to { n -> n.isEditable })
            state(case, "1-pinned", pairs, marker)
            compose.onNodeWithTag("step_up_secret").performScrollTo()
            state(case, "2-pinned-scrolled", pairs, marker)
            compose.tapNativeInput("step_up_secret", imeBottom = ime)
            state(case, "3-after-tap", pairs, marker)
            chain(case, "3-after-tap", "step_up_secret")
            showOnScreen(case, "secret-visible", marker) { it.isEditable }
        }
    }
    @Test fun diag372StepUpOff() = recorded("ccm-372-stepup-off", false) { stepUpCase("stepup-off") }
    @Test fun diag372StepUpOn() = recorded("ccm-372-stepup-on", true) { stepUpCase("stepup-on") }
}
