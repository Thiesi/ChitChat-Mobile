package io.github.thiesi.chitchat.testing

import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * #351/#369 scroll-settle DIAGNOSTIC (TL, 2026-11-25): uncommitted, never merged, observation only. On every frame for a
 * bounded window it reads each root's current semantics directly (no test synchronization, which would wait animations
 * out and hide the motion): every vertical scroller's value, the tagged nodes' window bounds, a tagged field's first text
 * child (its label) and caret, and the window's IME inset. A frame is kept only when something changed, capped. It
 * requests nothing: no focus, IME, scroll or retry.
 */
internal class ScrollSettleRecorder(private val roots: List<ViewRootForTest>, private val tags: List<String>,
    private val windowMs: Long = 3_000) {
    val samples = JSONArray()
    @Volatile var done = false
        private set
    private var start = 0L
    private var last: String? = null

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.uptimeMillis() - start
            val snapshot = snapshot(roots, tags)
            val key = snapshot.toString()
            if (key != last && samples.length() < 240) { samples.put(JSONObject().put("t", now).put("s", snapshot)); last = key }
            if (now < windowMs) Choreographer.getInstance().postFrameCallback(this) else done = true
        }
    }

    fun start(): ScrollSettleRecorder {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            start = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallback(callback)
        }
        return this
    }

    fun elapsed(): Long = SystemClock.uptimeMillis() - start

    companion object {
        private fun Float.r() = Math.round(this * 100) / 100.0

        fun snapshotNow(roots: List<ViewRootForTest>, tags: List<String>): JSONObject {
            var out = JSONObject()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { out = snapshot(roots, tags) }
            return out
        }

        /** Main thread only. */
        fun snapshot(roots: List<ViewRootForTest>, tags: List<String>): JSONObject {
            val windows = JSONArray()
            for (root in roots) {
                val insets = ViewCompat.getRootWindowInsets(root.view)
                val window = JSONObject().put("ime", insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: JSONObject.NULL)
                val scrollers = JSONArray()
                val tagged = JSONObject()
                fun walk(node: SemanticsNode) {
                    val config = node.config
                    config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range ->
                        val b = node.boundsInWindow
                        scrollers.put(JSONObject().put("value", range.value().r()).put("max", range.maxValue().r())
                            .put("top", b.top.r()).put("bottom", b.bottom.r()))
                    }
                    val tag = config.getOrNull(SemanticsProperties.TestTag)
                    if (tag != null && tag in tags) {
                        val b = node.boundsInWindow
                        val entry = JSONObject().put("top", b.top.r()).put("bottom", b.bottom.r())
                        node.children.firstOrNull { it.config.getOrNull(SemanticsProperties.Text) != null }?.let {
                            entry.put("labelTop", it.boundsInWindow.top.r())
                        }
                        config.getOrNull(SemanticsProperties.TextSelectionRange)?.let { selection ->
                            val layouts = mutableListOf<TextLayoutResult>()
                            config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                            layouts.firstOrNull()?.let { layout ->
                                val offset = selection.end.coerceIn(0, layout.layoutInput.text.length)
                                entry.put("caretTopInText", layout.getCursorRect(offset).top.r())
                                    .put("caretBottomInText", layout.getCursorRect(offset).bottom.r())
                            }
                        }
                        tagged.put(tag, entry)
                    }
                    node.children.forEach(::walk)
                }
                walk(root.semanticsOwner.unmergedRootSemanticsNode)
                windows.put(window.put("scrollers", scrollers).put("tagged", tagged))
            }
            return JSONObject().put("windows", windows)
        }

        /** sha256 (first 16 hex) of a capture this test just wrote, so variants group without opening images. */
        fun pngHash(folder: String, name: String): String {
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
                ?: checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
            val bytes = java.io.File(java.io.File(output, folder), "$name.png").readBytes()
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        }

        fun write(name: String, value: JSONObject) {
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
                ?: checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
            val folder = java.io.File(output, "ccm-scroll-settle")
            check(folder.isDirectory || folder.mkdirs())
            java.io.File(folder, name).writeText(value.toString(), Charsets.UTF_8)
        }
    }
}
