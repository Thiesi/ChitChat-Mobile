package io.github.thiesi.chitchat.imereproducer

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.testing.NativeInputWindow
import io.github.thiesi.chitchat.testing.nativeImeServerAfterFailure
import io.github.thiesi.chitchat.testing.nativeInputWindow
import org.json.JSONArray
import org.json.JSONObject

internal fun reportCycle(value: JSONObject) {
    InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("imeReproducer", value.toString()) })
}

internal fun windowJson(value: NativeInputWindow): JSONObject = JSONObject()
    .put("uptimeMillis", value.uptimeMillis)
    .put("resumedActivities", value.resumedActivities ?: JSONObject.NULL)
    .put("windowFocused", value.windowFocused ?: JSONObject.NULL)
    .put("attached", value.attached ?: JSONObject.NULL)
    .put("viewFocused", value.viewFocused ?: JSONObject.NULL)
    .put("imeVisible", value.imeVisible ?: JSONObject.NULL)
    .put("imeBottom", value.imeBottom ?: JSONObject.NULL)
    .put("softInputMode", value.softInputMode ?: JSONObject.NULL)

/** Never persisted: bounded transient command output, discarded after fixed-field filtering. */
private data class ShellSample(val text: String, val incomplete: Boolean)
private fun shellSample(command: String, limit: Int, milliseconds: Int): ShellSample {
    val deadline = SystemClock.uptimeMillis() + milliseconds
    val text = StringBuilder()
    var count = 0
    var complete = false
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    descriptor.use {
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val buffer = ByteArray(4096)
        while (count < limit) {
            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtMost(milliseconds.toLong()).toInt()
            if (remaining <= 0 || Os.poll(arrayOf(poll), remaining) == 0) break
            val size = Os.read(descriptor.fileDescriptor, buffer, 0, minOf(buffer.size, limit - count))
            if (size == 0) { complete = true; break }
            text.append(String(buffer, 0, size, Charsets.UTF_8))
            count += size
        }
    }
    return ShellSample(text.toString(), !complete)
}

/** One best-effort Java dump, after failure only. Never root, change IME, retry or retain raw text.
 * debuggerd may deny access on Google images; unavailable is a result, not evidence of an idle IME.
 */
private fun imeMainThread(): JSONObject {
    val started = SystemClock.uptimeMillis()
    var frames: List<String>? = null
    var incomplete = false
    runCatching {
        val selected = shellSample("settings get secure default_input_method", 512, 1000)
        check(!selected.incomplete && Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(selected.text.trim()))
        val imePackage = selected.text.trim().substringBefore('/')
        val process = shellSample("pidof " + imePackage, 64, 1000)
        val pid = process.text.trim()
        check(!process.incomplete && Regex("[1-9][0-9]{0,8}").matches(pid))
        val sample = shellSample("debuggerd -j " + pid, 65_536, 3000)
        incomplete = sample.incomplete
        frames = mainThreadFrames(sample.text, sample.incomplete)
    }
    return JSONObject().put("startedUptimeMillis", started)
        .put("finishedUptimeMillis", SystemClock.uptimeMillis()).put("incomplete", incomplete)
        .put("available", frames != null).put("frames", frames?.let(::JSONArray) ?: JSONObject.NULL)
}

internal fun failureObservation(stage: String, failure: Throwable, before: NativeInputWindow?): JSONObject {
    val detected = SystemClock.uptimeMillis()
    val own = nativeInputWindow()
    val server = nativeImeServerAfterFailure()
    return JSONObject().put("stage", stage).put("failureClass", failure.javaClass.simpleName)
        .put("detectedUptimeMillis", detected).put("beforeTap", before?.let(::windowJson) ?: JSONObject.NULL)
        .put("afterFailure", windowJson(own))
        .put("imeServer", JSONObject().apply {
            listOf("inputMethod" to server.inputMethod, "imeWindow" to server.imeWindow).forEach { (name, dump) ->
                put(name, JSONObject().put("startedUptimeMillis", dump.startedUptimeMillis)
                    .put("finishedUptimeMillis", dump.finishedUptimeMillis).put("limitReached", dump.limitReached)
                    .put("flags", dump.flags?.let(::JSONArray) ?: JSONObject.NULL))
            }
        }).put("imeMainThread", imeMainThread())
}
