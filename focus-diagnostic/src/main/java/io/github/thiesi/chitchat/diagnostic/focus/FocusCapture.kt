package io.github.thiesi.chitchat.diagnostic.focus

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.testing.focus.focusMetadata
import io.github.thiesi.chitchat.testing.nativeImeServerAfterFailure
import io.github.thiesi.chitchat.testing.nativeInputWindow
import org.json.JSONArray
import org.json.JSONObject

internal fun reportFocus(value: JSONObject) {
    InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("focusDiagnostic", value.toString()) })
}

/** Called in the test body, after failure detection and before its finally cleanup or rule teardown.
 * Source samples are sequential and separately timestamped, never a deadline/atomic-state claim.
 */
internal fun captureFocusFailure(stage: String, failure: Throwable): JSONObject {
    val receipt = SystemClock.uptimeMillis()
    val own = nativeInputWindow()
    val keyguardStart = SystemClock.uptimeMillis()
    val keyguard = runCatching {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }.getOrNull()
    val locked = runCatching { keyguard?.isKeyguardLocked }.getOrNull()
    val deviceLocked = runCatching { keyguard?.isDeviceLocked }.getOrNull()
    val secure = runCatching { keyguard?.isDeviceSecure }.getOrNull()
    val keyguardEnd = SystemClock.uptimeMillis()
    val windowStart = SystemClock.uptimeMillis()
    var limited = false
    val focus = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys -t 2 window displays")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            val buffer = CharArray(65_536)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            limited = count == buffer.size
            focusMetadata(String(buffer, 0, count), limited)
        }
    }.getOrNull()
    val windowEnd = SystemClock.uptimeMillis()
    val server = nativeImeServerAfterFailure()
    fun nullable(value: Any?): Any = value ?: JSONObject.NULL
    return JSONObject().put("kind", "failure").put("stage", stage)
        .put("failureClass", failure.javaClass.simpleName).put("detectedUptimeMillis", receipt)
        .put("ownWindow", JSONObject().put("uptimeMillis", own.uptimeMillis)
            .put("resumedActivities", nullable(own.resumedActivities))
            .put("windowFocused", nullable(own.windowFocused)).put("attached", nullable(own.attached))
            .put("viewFocused", nullable(own.viewFocused)).put("inTouchMode", nullable(own.inTouchMode))
            .put("textEditor", nullable(own.textEditor)).put("imeVisible", nullable(own.imeVisible))
            .put("imeBottom", nullable(own.imeBottom)).put("softInputMode", nullable(own.softInputMode))
            .put("imeControllable", nullable(own.imeControllable)))
        .put("keyguard", JSONObject().put("startedUptimeMillis", keyguardStart).put("finishedUptimeMillis", keyguardEnd)
            .put("locked", nullable(locked)).put("deviceLocked", nullable(deviceLocked)).put("deviceSecure", nullable(secure)))
        .put("windowManager", JSONObject().put("startedUptimeMillis", windowStart).put("finishedUptimeMillis", windowEnd)
            .put("limitReached", limited).put("focusedWindow", nullable(focus?.window?.name))
            .put("focusedApp", nullable(focus?.app?.name)).put("windowFields", nullable(focus?.windowFields))
            .put("appFields", nullable(focus?.appFields)))
        .put("imeServer", JSONObject().apply {
            listOf("inputMethod" to server.inputMethod, "imeWindow" to server.imeWindow).forEach { (name, dump) ->
                put(name, JSONObject().put("startedUptimeMillis", dump.startedUptimeMillis)
                    .put("finishedUptimeMillis", dump.finishedUptimeMillis).put("limitReached", dump.limitReached)
                    .put("flags", dump.flags?.let(::JSONArray) ?: JSONObject.NULL))
            }
        })
}
