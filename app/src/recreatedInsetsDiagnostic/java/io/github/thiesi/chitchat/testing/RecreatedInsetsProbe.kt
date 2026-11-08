package io.github.thiesi.chitchat.testing

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Scalar records only. No listener replacement, insets dispatch, editor reads or input action. */
internal class RecreatedInsetsProbe(private val cycle: Int) {
    private var sequence = 0
    private var dropped = 0
    private val controls = ArrayList<JSONObject>()
    private val progress = ArrayDeque<JSONObject>()
    var stage = "START"
    @Volatile var mirroredBottom = 0
    @Volatile var mirroredProducer = 0

    fun sample(event: String, activity: ComponentActivity, root: View, composeBottom: Int) {
        val native = root.rootWindowInsets
        val row = JSONObject().put("sequence", ++sequence).put("uptimeMillis", SystemClock.uptimeMillis())
            .put("event", event).put("stage", stage)
            .put("activity", System.identityHashCode(activity)).put("window", System.identityHashCode(activity.window))
            .put("root", System.identityHashCode(root)).put("attached", root.isAttachedToWindow)
            .put("destroyed", activity.isDestroyed).put("windowFocused", root.hasWindowFocus())
            .put("rootMatchesWindow", root.rootView === activity.window.decorView)
            .put("focusedRootMatches", activity.window.decorView.findFocus() === root)
            .put("composeBottom", composeBottom).put("mirroredBottom", mirroredBottom)
            .put("mirroredProducer", mirroredProducer)
            .put("nativeVisible", native?.isVisible(WindowInsets.Type.ime()) ?: JSONObject.NULL)
            .put("nativeBottom", native?.getInsets(WindowInsets.Type.ime())?.bottom ?: JSONObject.NULL)
            .put("holderAccessors", accessorCount(root) ?: JSONObject.NULL)
            .put("applyListenerPresent", applyListenerPresent(root) ?: JSONObject.NULL)
        if (event == "draw" || event == "compose") {
            if (progress.size == 128) { progress.removeFirst(); dropped++ }
            progress.addLast(row)
        } else {
            check(controls.size < 64) { "Diagnostic control-record bound" }
            controls.add(row)
        }
    }

    /** Read the existing pinned Compose holder; never call getOrCreateFor or change its count. */
    private fun accessorCount(root: View): Int? = runCatching {
        val type = Class.forName("androidx.compose.foundation.layout.WindowInsetsHolder")
        val field = type.getDeclaredField("viewMap").apply { isAccessible = true }
        val map = field.get(null) as Map<*, *>
        val holder = synchronized(map) { map[root] } ?: return@runCatching null
        holder.javaClass.getDeclaredField("accessCount").apply { isAccessible = true }.getInt(holder)
    }.getOrNull()

    /** Hidden-field access may be unavailable: null is unknown, never "no listener". */
    private fun applyListenerPresent(root: View): Boolean? = runCatching {
        val info = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(root)
            ?: return@runCatching false
        info.javaClass.getDeclaredField("mOnApplyWindowInsetsListener").apply { isAccessible = true }.get(info) != null
    }.getOrNull()

    fun emit(failed: Boolean, cleanupFailed: Boolean) {
        val rows = (controls + progress).sortedBy { it.getInt("sequence") }
        val record = JSONObject().put("schema", 1).put("cycle", cycle).put("failed", failed)
            .put("cleanupFailed", cleanupFailed).put("droppedProgress", dropped).put("records", JSONArray(rows))
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("ccmInsetsCycle", record.toString()) })
    }
}
