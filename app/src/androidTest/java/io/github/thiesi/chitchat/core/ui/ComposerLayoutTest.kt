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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.Composable
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

    private fun composer(fontScale: Float, cap: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides phone360(fontScale)) {
            ChitChatTheme(dark = false) {
                Box(Modifier.width(360.dp)) {
                    ComposerLayout(Modifier.fillMaxWidth().heightIn(max = cap).padding(horizontal = 16.dp, vertical = 4.dp).testTag("block"),
                        leading = { Box(Modifier.size(48.dp).testTag("details")) },
                        field = { MessageInput(text.value, { text.value = it }, enabled = true, label = "Message", compact = false,
                            modifier = Modifier.testTag("field")) },
                        trailing = { Box(Modifier.size(48.dp).testTag("attach")); Box(Modifier.size(48.dp).testTag("send")) })
                }
            }
        }
    }
    /** CI emulators can be narrower than 360dp; scale density so a 360dp phone fills the window exactly. */
    @Composable private fun phone360(fontScale: Float) = Density(LocalWindowInfo.current.containerSize.width / 360f, fontScale)
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
        close(block.left.value, details.left.value) // The block's tag sits inside its 16dp side padding.
        close(block.right.value, send.right.value)
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

    /** A short cap (compact mode, large font) keeps the single row rather than squeezing the field. */
    @Test fun aShortCapKeepsOneRowWithAFullHeightField() {
        composer(1.15f, cap = 80.dp)
        val field = at("field"); val send = at("send")
        assertTrue("Field keeps its 48dp target, was ${field.height}", field.height.value >= 47.5f)
        close(field.bottom.value, send.bottom.value) // Same row.
    }

    /**
     * A window resize (the IME opening under adjustResize) can move the composer across the
     * short-cap exception. The field is the same composable in both arrangements, so focus, the
     * IME connection and the draft survive the switch; nothing is re-requested after it.
     */
    @Test fun stackedToOneRowSwitchKeepsFocusImeConnectionAndDraft() {
        val cap = mutableStateOf(androidx.compose.ui.unit.Dp.Infinity)
        val focus = FocusRequester(); lateinit var view: android.view.View
        val instances = mutableListOf<Any>()
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            CompositionLocalProvider(LocalDensity provides phone360(1.15f)) {
                ChitChatTheme(dark = false) {
                    Box(Modifier.width(360.dp)) {
                        ComposerLayout(Modifier.fillMaxWidth().heightIn(max = cap.value).padding(horizontal = 16.dp, vertical = 4.dp),
                            leading = { Box(Modifier.size(48.dp).testTag("details")) },
                            field = {
                                val instance = androidx.compose.runtime.remember { Any() }
                                androidx.compose.runtime.SideEffect { if (instances.lastOrNull() !== instance) instances += instance }
                                MessageInput(text.value, { text.value = it }, enabled = true, label = "Message", compact = false,
                                    modifier = Modifier.focusRequester(focus).testTag("field"))
                            },
                            trailing = { Box(Modifier.size(48.dp).testTag("attach")); Box(Modifier.size(48.dp).testTag("send")) })
                    }
                }
            }
        }
        compose.runOnIdle { text.value = "Draft survives"; focus.requestFocus() } // Setup only; not a tap or IME proof.
        compose.waitForIdle()
        val imm = view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        fun stacked() = at("field").bottom <= at("send").top
        assertTrue("Starts stacked at 115%", stacked())
        compose.onNodeWithTag("field").assertIsFocused()
        val activeBefore = compose.runOnIdle { imm.isActive(view) }
        compose.runOnIdle { cap.value = 80.dp } // Too short to stack: the single row.
        compose.waitForIdle()
        assertFalse("Switched to one row", stacked())
        compose.onNodeWithTag("field").assertIsFocused().assertTextContains("Draft survives")
        assertEquals("IME connection unchanged by the switch", activeBefore, compose.runOnIdle { imm.isActive(view) })
        compose.runOnIdle { cap.value = androidx.compose.ui.unit.Dp.Infinity }
        compose.waitForIdle()
        assertTrue("Back to stacked", stacked())
        compose.onNodeWithTag("field").assertIsFocused().assertTextContains("Draft survives")
        assertEquals("One field instance across both switches", 1, instances.size)
    }

    /** Keyboard Next follows placement order, which is field, Details, Attach, Send when stacked. */
    @Test fun nextFocusFollowsTheStackedVisualOrder() {
        val start = FocusRequester(); lateinit var focus: FocusManager
        val focused = mutableStateOf<String?>(null)
        fun Modifier.slot(tag: String) = size(48.dp).testTag(tag).onFocusChanged { if (it.isFocused) focused.value = tag }.focusable()
        compose.setContent {
            focus = LocalFocusManager.current
            CompositionLocalProvider(LocalDensity provides phone360(1.15f)) {
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
