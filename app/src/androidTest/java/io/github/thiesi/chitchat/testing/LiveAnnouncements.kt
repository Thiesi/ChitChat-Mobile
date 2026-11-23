package io.github.thiesi.chitchat.testing

import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList

private const val FLUSH = "ccm-live-flush-"

/**
 * #323b (PL, TL): counts what a screen reader announces from polite live regions: `TYPE_WINDOW_CONTENT_CHANGED` events
 * whose source, queried as the event arrives (as TalkBack does), has a live region and matches [carrier]. A source that
 * has lost its live region by then counts as silent, as it would for TalkBack.
 *
 * "No further events" is made deterministic with [flush]: events are delivered in order, so after two changes of the
 * test-only [LiveFlushSentinel], each awaited before the next, every change made before the first has been delivered.
 * No sleep and no retry. Measurement only: it never acts on the UI.
 */
class LiveAnnouncements(private val carrier: (AccessibilityNodeInfo) -> Boolean) : AutoCloseable {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val seen = CopyOnWriteArrayList<String>()
    @Volatile private var flushed = -1

    init {
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return@setOnAccessibilityEventListener
            val source = event.source ?: return@setOnAccessibilityEventListener
            if (source.liveRegion == View.ACCESSIBILITY_LIVE_REGION_NONE) return@setOnAccessibilityEventListener
            val description = source.contentDescription?.toString().orEmpty()
            if (description.startsWith(FLUSH)) description.removePrefix(FLUSH).toIntOrNull()?.let { flushed = maxOf(flushed, it) }
            else if (carrier(source)) seen += "$description|${source.state()}"
        }
    }

    /** Announcements counted so far (each "label|state"). */
    val count get() = seen.size
    fun spoken(): List<String> = seen.toList()

    /** Waits until every event caused by changes made before this call has been delivered. */
    fun flush(compose: ComposeTestRule, sentinel: MutableIntState) {
        repeat(2) {
            val next = sentinel.intValue + 1
            compose.runOnIdle { sentinel.intValue = next }
            compose.waitUntil(5_000) { flushed >= next }
        }
    }

    override fun close() = automation.setOnAccessibilityEventListener(null)
}

/** The node's state description (API 30+), or "". */
fun AccessibilityNodeInfo.state(): String = if (Build.VERSION.SDK_INT >= 30) stateDescription?.toString().orEmpty() else ""

/** A test-only 1dp polite live region whose description changes with [value]; see [LiveAnnouncements.flush]. */
@Composable
fun LiveFlushSentinel(value: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(1.dp).semantics { contentDescription = "$FLUSH$value"; liveRegion = LiveRegionMode.Polite })
}
