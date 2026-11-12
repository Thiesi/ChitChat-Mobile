package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * P-216 on a 360dp phone with the room composer's 16dp padding and its three 48dp controls: the
 * single-row field is 160dp, so it stays one row at 100% (threshold 144dp) and stacks at 115%
 * (threshold 184dp). Stacked, the field is full width above Details (start) and Attach/Send (end).
 */
class ComposerLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val text = mutableStateOf("")

    private fun composer(fontScale: Float) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            ChitChatTheme(dark = false) {
                Box(Modifier.width(360.dp)) {
                    ComposerLayout(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("block"),
                        leading = { Box(Modifier.size(48.dp).testTag("details")) },
                        field = { MessageInput(text.value, { text.value = it }, enabled = true, label = "Message", compact = false,
                            modifier = Modifier.testTag("field")) },
                        trailing = { Box(Modifier.size(48.dp).testTag("attach")); Box(Modifier.size(48.dp).testTag("send")) })
                }
            }
        }
    }
    private fun at(tag: String): DpRect = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
    private fun close(expected: Float, actual: Float) = assertEquals(expected, actual, 1f)

    @Test fun oneRowAtDefaultScale() {
        composer(1f)
        val details = at("details"); val field = at("field"); val attach = at("attach"); val send = at("send")
        close(160f, field.width.value)
        assertTrue("Details, field, Attach, Send left to right",
            details.right <= field.left && field.right <= attach.left && attach.right <= send.left)
        close(field.bottom.value, send.bottom.value) // Bottom-aligned on one row.
    }

    @Test fun stacksAt115PercentWithTheFieldAloneAboveTheControls() {
        composer(1.15f)
        val block = at("block"); val details = at("details"); val field = at("field"); val attach = at("attach"); val send = at("send")
        close(328f, field.width.value)
        assertTrue("Field row above the controls row", field.bottom <= details.top && field.bottom <= send.top)
        close(block.left.value + 16f, details.left.value)
        close(block.right.value - 16f, send.right.value)
        assertTrue(attach.right <= send.left)
        listOf(details, attach, send).forEach { close(48f, it.width.value); close(48f, it.height.value) }
    }

    @Test fun typingNeverFlipsTheArrangement() {
        composer(1f)
        text.value = "A longer draft that wraps onto a few lines of the composer field while typing"
        compose.waitForIdle()
        close(160f, at("field").width.value)
        close(at("field").bottom.value, at("send").bottom.value)
    }

    /** Keyboard Next follows placement order, which is field, Details, Attach, Send when stacked. */
    @Test fun nextFocusFollowsTheStackedVisualOrder() {
        val start = FocusRequester(); lateinit var focus: FocusManager
        val focused = mutableStateOf<String?>(null)
        fun Modifier.slot(tag: String) = size(48.dp).testTag(tag).onFocusChanged { if (it.isFocused) focused.value = tag }.focusable()
        compose.setContent {
            focus = LocalFocusManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.15f)) {
                ChitChatTheme(dark = false) {
                    Box(Modifier.width(360.dp)) {
                        ComposerLayout(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            leading = { Box(Modifier.slot("details")) },
                            field = { Box(Modifier.fillMaxWidth().height(48.dp).focusRequester(start).testTag("field")
                                .onFocusChanged { if (it.isFocused) focused.value = "field" }.focusable()) },
                            trailing = { Box(Modifier.slot("attach")); Box(Modifier.slot("send")) })
                    }
                }
            }
        }
        compose.runOnIdle { start.requestFocus() }
        val order = mutableListOf(compose.runOnIdle { focused.value })
        repeat(3) { compose.runOnIdle { focus.moveFocus(FocusDirection.Next) }; order += compose.runOnIdle { focused.value } }
        assertEquals(listOf("field", "details", "attach", "send"), order)
    }
}
