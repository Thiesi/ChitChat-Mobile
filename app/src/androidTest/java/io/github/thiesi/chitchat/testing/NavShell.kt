package io.github.thiesi.chitchat.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag

/**
 * The navigation shell's bottom-bar tab (navigation-shell.md): "rooms", "direct", "activity" or "you".
 * It waits only for the bar item to exist (the shell is entered after sign-in) and returns the node;
 * each journey keeps its original input routing (performNativeTap or performClick).
 */
internal fun ComposeTestRule.openTab(name: String): SemanticsNodeInteraction {
    waitUntil(5_000) { onAllNodesWithTag("nav_tab_$name").fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithTag("nav_tab_$name")
}
