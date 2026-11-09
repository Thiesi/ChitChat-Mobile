package io.github.thiesi.chitchat.testing

/**
 * Hidden at cleanup completion: Compose is zero and both native observations are hidden.
 * A later re-show from the test's earlier tap is outside this completion-time contract.
 * Keep both native probes inside the original wait; this performs no input or hide action.
 */
internal fun awaitCompletionImeHidden(
    waitUntil: (Long, () -> Boolean) -> Unit,
    composeImeBottom: () -> Int,
    nativeImeVisible: () -> Boolean?,
) {
    waitUntil(5_000) {
        composeImeBottom() == 0 && nativeImeVisible() == false && nativeImeVisible() == false
    }
}
