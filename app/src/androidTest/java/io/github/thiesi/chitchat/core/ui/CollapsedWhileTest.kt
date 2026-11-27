package io.github.thiesi.chitchat.core.ui

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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #351 fix B (TL): while the keyboard's inset is above 0, a pinned action absent with the keyboard (Create, the reset
 * action) leaves no semantics node, not even a zero-size one, because the host's [collapsedWhile] neither measures nor
 * places it. The same predicate shape as the host's: state read in layout.
 */
class CollapsedWhileTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aCollapsedActionLeavesNoSemanticsNodeAndComesBackWhole() {
        var inset by mutableStateOf(259)
        compose.setContent {
            Box(Modifier.collapsedWhile { inset > 0 }.testTag("pinned_action")) {
                Button(onClick = {}, modifier = Modifier.testTag("registration_submit")) { Text("Create my account") }
            }
        }
        compose.waitForIdle()
        assertTrue("No Create node, merged or not, while collapsed",
            compose.onAllNodesWithTag("registration_submit").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("registration_submit", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Create my account", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        inset = 0
        compose.waitForIdle()
        compose.onNodeWithTag("registration_submit").assertIsDisplayed()
    }
}
