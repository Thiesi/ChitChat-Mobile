package io.github.thiesi.chitchat.core.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #351 fix B (TL): while the keyboard's inset is above 0, a pinned action absent with the keyboard (Create, the reset
 * action) is not in the accessibility tree, not even as a zero-size node: the host's [collapsedWhile] neither measures
 * nor places it, and Compose's accessibility tree leaves unplaced nodes out (`getAllUncoveredSemanticsNodesToIntObjectMap`
 * starts with `LayoutNode.isPlaced`). Checked on the tree an accessibility service gets (UiAutomation, every interactive
 * window, the TextFieldNodeNamesTest way), with a positive control: the same read and the same bounded wait see the node
 * before the collapse and after it, so its absence while collapsed isn't a tree that hasn't caught up.
 */
class CollapsedWhileTest {
    @get:Rule val compose = createComposeRule()

    private val label = "Create my account"

    private fun automation() = InstrumentationRegistry.getInstrumentation().uiAutomation.also { automation ->
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
    }

    /** The accessibility nodes carrying the action's label, in every interactive window, each refreshed. */
    private fun accessibilityNodes(): Int = automation().windows.sumOf { window ->
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            node.refresh()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) count++
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        walk(window.root)
        count
    }

    /** The one bounded wait every phase uses: the accessibility tree reaching [expected] nodes. */
    private fun awaitAccessibilityNodes(expected: Int, phase: String) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { accessibilityNodes() == expected }
        assertEquals(phase, expected, accessibilityNodes())
    }

    @Test fun aCollapsedActionIsOutOfTheAccessibilityTreeAndComesBackWhole() {
        var inset by mutableStateOf(0)
        compose.setContent {
            Box(Modifier.collapsedWhile { inset > 0 }.testTag("pinned_action")) {
                Button(onClick = {}, modifier = Modifier.testTag("registration_submit")) { Text(label) }
            }
        }
        // The positive control: the read sees the action when it's there.
        awaitAccessibilityNodes(1, "Before the collapse, the accessibility tree has the action once")
        inset = 259
        awaitAccessibilityNodes(0, "While collapsed, the accessibility tree has no node for the action")
        // The test finder reads the semantics tree without the accessibility tree's isPlaced check, so it can still return
        // the composed but unplaced button: every such node must be unplaced.
        val found = compose.onAllNodesWithTag("registration_submit", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Every node the finder returns while collapsed is unplaced (${found.map { it.layoutInfo.isPlaced }})",
            found.all { !it.layoutInfo.isPlaced })
        inset = 0
        awaitAccessibilityNodes(1, "Uncollapsed, the accessibility tree has the action once again")
        compose.onNodeWithTag("registration_submit").assertIsDisplayed()
    }
}
