package io.github.thiesi.chitchat.testing

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The active window's real accessibility nodes (as TalkBack sees them), in tree order, as scalars only: whether each
 * is an action (clickable, not an editable field), its spoken label and its screen bounds. #323b2 (PL, TL): where a dialog's wait line sits in
 * the reading order, relative to the dialog's actions. The live-flush sentinel isn't listed.
 */
data class ReadingNode(val label: String, val action: Boolean, val bounds: Rect)

fun readingOrder(): List<ReadingNode> {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
    val found = mutableListOf<ReadingNode>()
    fun walk(node: AccessibilityNodeInfo) {
        val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
        if (node.isVisibleToUser && !label.startsWith("ccm-live-flush-") &&
            (node.isClickable || node.isFocusable || node.isScreenReaderFocusable || label.isNotEmpty()))
            // An editable field isn't an action here (PL): the edit body may sit above the line; buttons may not.
            found += ReadingNode(label, node.isClickable && !node.isEditable, Rect().also { node.getBoundsInScreen(it) })
        for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
    }
    walk(root)
    return found
}

/**
 * The wait line [line] comes before every action of the dialog in the reading order, and isn't visually below any of
 * them (no jumping around). Returns a one-line record for the PL: the line's position and the actions after it.
 */
fun assertLineBeforeEveryAction(line: String, what: String): String {
    val nodes = readingOrder()
    val index = nodes.indexOfFirst { it.label.trimEnd() == line }
    check(index >= 0) { "$what: the line isn't in the real node tree: ${nodes.map { it.label.take(24) }}" }
    val actions = nodes.withIndex().filter { it.value.action }
    val before = actions.filter { it.index < index }
    check(before.isEmpty()) { "$what: actions read before the line: ${before.map { it.value.label.take(24) }}" }
    val above = actions.filter { it.value.bounds.top < nodes[index].bounds.top }
    check(above.isEmpty()) { "$what: actions visually above the line: ${above.map { it.value.label.take(24) }}" }
    return "$what: the line is node ${index + 1} of ${nodes.size} in the reading order, before all ${actions.size} actions"
}
