package io.github.thiesi.chitchat.testing

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange

/**
 * #350 (PL, TL): where a capture's subject isn't the caret's placement, the caret goes to the end of the text after the
 * tap's strict IME proof, so the image doesn't follow where the native tap landed. A selection change only, through the
 * field's own semantics: no focus move, no tap, no change to the text. Captures whose subject is the placement assert it
 * from the layout instead. The capture hosts also provide `LocalCursorBlinkEnabled provides false`, so the caret is
 * drawn steadily rather than at a varying blink phase. Test-only; production is unchanged.
 */
internal fun ComposeTestRule.caretToEnd(tag: String) {
    val node = onNodeWithTag(tag)
    val length = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text?.length ?: return
    node.performTextInputSelection(TextRange(length))
    waitForIdle()
}
