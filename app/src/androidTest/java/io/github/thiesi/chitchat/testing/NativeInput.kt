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
    val beforeWindow = nativeInputWindow()
    node.performNativeTap()
    val requireIme = InstrumentationRegistry.getArguments().getString("expectSoftKeyboard") == "true"
    try {
        waitUntil(5_000) { (!requireIme || imeBottom() > 0) &&
            runCatching { node.assertIsFocused().assertIsDisplayed() }.isSuccess }
    } catch (failure: ComposeTimeoutException) {
        val current = node.fetchSemanticsNode()
        val extra = runCatching(failureMetadata).getOrDefault("extra diagnostics unavailable")
        val afterWindow = nativeInputWindow()
        val afterIme = imeBottom()
        // Existing window snapshots describe the Activity; a Dialog can own this editor.
        // Observe only after failure and after the existing receipt scalars are frozen.
        val targetWindow = nativeInputTargetWindow(current)
        val inputSessions = NativeTextInputTrace.describe()
        val server = nativeImeServerAfterFailure()
        throw AssertionError("Tap fixture: tag=$tag, IME=$afterIme, focusedBefore=$beforeFocused, focused=${current.config[SemanticsProperties.Focused]}, before=$beforeBounds, after=${current.boundsInWindow}, beforeWindow=$beforeWindow, afterWindow=$afterWindow, targetWindow=$targetWindow, inputSessions=$inputSessions, server=$server, $extra", failure)
    }
    return node.assertIsFocused().assertIsDisplayed()
}
