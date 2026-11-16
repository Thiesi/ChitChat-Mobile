package io.github.thiesi.chitchat.testing

import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException

/**
 * Hidden at cleanup completion: Compose is zero and both native observations are hidden.
 * A later re-show from the test's earlier tap is outside this completion-time contract.
 * Keep both native probes inside the original wait; this performs no input or hide action.
 */
internal fun awaitCompletionImeHidden(
    waitUntil: (Long, () -> Boolean) -> Unit,
    composeImeBottom: () -> Int,
    nativeImeVisible: () -> Boolean?,
    identity: String? = null,
    emitTimeout: ((String) -> Unit)? = null,
) {
    // Default: logcat plus a small file. The logcat line can be lost to ring-buffer rotation before the wrapper reads it,
    // so the same allowlisted line also goes to CompletionSampleFile. Both run inside the runCatching below: neither can
    // replace the timeout.
    val emit = emitTimeout ?: { line: String ->
        Log.w("CCMCompletionIme", line)
        identity?.let { CompletionSampleFile.write(it, line) }
        Unit
    }
    var compose: Int? = null
    var first = "unobserved"
    var second = "unobserved"
    try {
        waitUntil(5_000) {
            // Capture only this evaluation; short-circuited probes stay unobserved.
            // No extra native/Compose read, including after the deadline.
            compose = null; first = "unobserved"; second = "unobserved"
            composeImeBottom().also { compose = it } == 0 &&
                nativeImeVisible().also { first = cleanupProbe(it) } == false &&
                nativeImeVisible().also { second = cleanupProbe(it) } == false
        }
    } catch (failure: ComposeTimeoutException) {
        // This is the last in-wait sample, not a new atomic or post-timeout observation.
        // Diagnostic failure must never replace or suppress the original timeout.
        runCatching {
            emit("sample=last_wait_predicate compose=${compose ?: "unobserved"} native1=$first native2=$second")
        }
        throw failure
    }
}

private fun cleanupProbe(visible: Boolean?) = when (visible) {
    false -> "hidden"
    true -> "visible"
    null -> "unknown"
}
