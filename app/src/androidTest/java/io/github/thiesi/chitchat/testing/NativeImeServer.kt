package io.github.thiesi.chitchat.testing

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/** Failure-only observation. No EditorInfo, input connection, window title or raw dump retained. */
internal data class NativeImeServer(
    val startedUptimeMillis: Long,
    val finishedUptimeMillis: Long,
    val flags: List<String>?,
    val limitReached: Boolean = false,
)

private const val MAX_SERVER_CHARS = 65_536
private const val MAX_SERVER_FLAGS = 40
private val serverFlag = Regex(
    "(?<![\\w.])(?:" +
        "(?:mShowRequested|mShowExplicitlyRequested|mShowForced|mInputShown|" +
        "mShowInputRequested|mIsInputViewShown|mDecorViewVisible|mWindowVisible|" +
        "mInShowWindow|mWindowWasVisible)=(?:true|false)" +
        "|mImeWindowVis=(?:0x[0-9a-fA-F]{1,8}|[0-9]{1,10}))(?=\\s|[,;}]|$)"
)

/** Pure fixed-field filter; unknown metadata is unavailable, not a fabricated hidden keyboard. */
internal fun imeServerFlags(raw: CharSequence): List<String>? = serverFlag.findAll(raw)
    .take(MAX_SERVER_FLAGS).map { it.value }.toList().takeIf { it.isNotEmpty() }

/** Call only AFTER the original tap deadline, BEFORE @After can hide/retire the IME.
 * dumpsys has its own two-second service timeout; input and focus are never changed.
 * Bound the transient dump and retain only the fixed flags, never a raw fallback/error string.
 */
internal fun nativeImeServerAfterFailure(): NativeImeServer {
    val started = SystemClock.uptimeMillis()
    var limitReached = false
    val flags = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys -t 2 input_method")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            val buffer = CharArray(MAX_SERVER_CHARS)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            limitReached = count == buffer.size
            imeServerFlags(String(buffer, 0, count))
        }
    }.getOrNull()
    return NativeImeServer(started, SystemClock.uptimeMillis(), flags, limitReached)
}
