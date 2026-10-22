package io.github.thiesi.chitchat.testing

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/** Failure-only observation. No EditorInfo, input connection, window title or raw dump retained. */
internal data class NativeImeDump(
    val startedUptimeMillis: Long,
    val finishedUptimeMillis: Long,
    val flags: List<String>?,
    val limitReached: Boolean = false,
)

internal data class NativeImeServer(val inputMethod: NativeImeDump, val imeWindow: NativeImeDump)

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

private val windowHeader = Regex("^\\s*Window #\\d+ Window\\{")
private val imeAttributes = Regex("^\\s*mAttrs=\\{.*\\bty=(?:INPUT_METHOD|2011)(?=\\s|\\})")
private val windowFlag = Regex(
    "(?<![\\w.])(?:" +
        "(?:mGivenInsetsPending|mHasSurface|isReadyForDisplay\\(\\)|mPolicyVisibility|" +
        "mAppOpVisibility|mSurfaceShown|isOnScreen|isVisible|mLastHidden)=(?:true|false)" +
        "|mDrawState=(?:NO_SURFACE|DRAW_PENDING|COMMIT_DRAW_PENDING|READY_TO_SHOW|HAS_DRAWN))" +
        "(?=\\s|[,;}]|$)"
)

/** Identify by fixed window type, never by its title. Ambiguous/missing IME windows are unknown.
 * Other-window flags are discarded; retain no raw lines, window identifiers or names.
 */
internal fun imeWindowFlags(raw: CharSequence): List<String>? {
    var inWindow = false
    var isIme = false
    var imeWindows = 0
    var selected: List<String>? = null
    val fields = mutableListOf<String>()
    fun finishWindow() {
        if (inWindow && isIme) {
            imeWindows++
            selected = if (imeWindows == 1) fields.toList().takeIf { it.isNotEmpty() } else null
        }
    }
    raw.lineSequence().forEach { line ->
        if (windowHeader.containsMatchIn(line)) {
            finishWindow()
            inWindow = true; isIme = false; fields.clear()
        }
        if (inWindow) {
            if (imeAttributes.containsMatchIn(line)) isIme = true
            windowFlag.findAll(line).take(MAX_SERVER_FLAGS - fields.size).forEach { fields += it.value }
        }
    }
    finishWindow()
    return selected.takeIf { imeWindows == 1 }
}

/** Call only AFTER the original tap deadline, BEFORE @After can hide/retire the IME.
 * dumpsys has its own two-second service timeout; input and focus are never changed.
 * Bound the transient dump and retain only the fixed flags, never a raw fallback/error string.
 */
internal fun nativeImeServerAfterFailure(): NativeImeServer = NativeImeServer(
    inputMethod = captureImeDump("dumpsys -t 2 input_method", ::imeServerFlags),
    imeWindow = captureImeDump("dumpsys -t 2 window windows", ::imeWindowFlags),
)

private fun captureImeDump(command: String, filter: (CharSequence) -> List<String>?): NativeImeDump {
    val started = SystemClock.uptimeMillis()
    var limitReached = false
    val flags = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            val buffer = CharArray(MAX_SERVER_CHARS)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            limitReached = count == buffer.size
            filter(String(buffer, 0, count))
        }
    }.getOrNull()
    return NativeImeDump(started, SystemClock.uptimeMillis(), flags, limitReached)
}
