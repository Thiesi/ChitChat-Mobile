package io.github.thiesi.chitchat.testing

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
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

/** Scan fixed fields across read boundaries without retaining lines or a complete service dump. */
internal class ImeServerFlagStream {
    private var tail = ""
    private var start = 0
    private val selected = mutableListOf<String>()
    val flagLimitReached: Boolean get() = selected.size == MAX_SERVER_FLAGS

    fun accept(chunk: CharSequence) {
        if (flagLimitReached) return
        // Each fixed field is shorter than64 chars. Keep its preceding character for the
        // negative lookbehind and do not treat a chunk end as the end of the dump.
        val input = tail + chunk
        val cut = (input.length - 64).coerceAtLeast(0)
        serverFlag.findAll(input, start).takeWhile { it.range.first < cut }
            .take(MAX_SERVER_FLAGS - selected.size).forEach { selected += it.value }
        if (cut > 0) { tail = input.substring(cut - 1); start = 1 }
        else tail = input
    }

    fun result(endOfStream: Boolean): List<String>? {
        if (endOfStream && !flagLimitReached) {
            serverFlag.findAll(tail, start).take(MAX_SERVER_FLAGS - selected.size)
                .forEach { selected += it.value }
            tail = ""; start = 0
        }
        return selected.toList().takeIf { it.isNotEmpty() }
    }
}

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
 * Stream input_method through the fixed-field filter; window capture retains its65KiB bound.
 * Both retain only flags, never a raw fallback/error string.
 */
internal fun nativeImeServerAfterFailure(): NativeImeServer = NativeImeServer(
    inputMethod = captureInputMethodStream(),
    imeWindow = captureImeDump("dumpsys -t 2 window windows", ::imeWindowFlags),
)

private fun captureInputMethodStream(): NativeImeDump {
    val started = SystemClock.uptimeMillis()
    var incomplete = false
    val scanner = ImeServerFlagStream()
    val flags = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys -t 2 input_method")
        descriptor.use {
            val poll = StructPollfd().apply {
                fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort()
            }
            val buffer = ByteArray(4096)
            var eof = false
            while (true) {
                val remaining = 2_000 - (SystemClock.uptimeMillis() - started)
                if (remaining <= 0 || Os.poll(arrayOf(poll), remaining.toInt()) == 0) break
                val read = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                if (read == 0) { eof = true; break }
                scanner.accept(String(buffer, 0, read, Charsets.UTF_8))
            }
            incomplete = !eof
            scanner.result(endOfStream = eof)
        }
    }.getOrNull()
    return NativeImeDump(started, SystemClock.uptimeMillis(), flags, incomplete || scanner.flagLimitReached)
}

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
