package io.github.thiesi.chitchat.testing

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/** One real tap before text injection, which can otherwise manufacture focus after a missed tap. */
internal fun ComposeTestRule.tapNativeInput(tag: String, imeBottom: () -> Int,
    failureMetadata: () -> String = { "" }): SemanticsNodeInteraction {
    val node = onNodeWithTag(tag)
    waitUntil(5_000) { runCatching { node.assertIsDisplayed() }.isSuccess }
    val before = node.fetchSemanticsNode()
    val beforeBounds = before.boundsInWindow
    val beforeFocused = before.config[SemanticsProperties.Focused]
    node.performClick()
    val requireIme = InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true"
    try {
        waitUntil(5_000) { (!requireIme || imeBottom() > 0) &&
            runCatching { node.assertIsFocused().assertIsDisplayed() }.isSuccess }
    } catch (failure: ComposeTimeoutException) {
        val current = node.fetchSemanticsNode()
        val extra = runCatching(failureMetadata).getOrDefault("extra diagnostics unavailable")
        throw AssertionError("Tap fixture: tag=$tag, IME=${imeBottom()}, focusedBefore=$beforeFocused, focused=${current.config[SemanticsProperties.Focused]}, before=$beforeBounds, after=${current.boundsInWindow}, $extra", failure)
    }
    return node.assertIsFocused().assertIsDisplayed()
}
