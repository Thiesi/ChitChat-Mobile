package io.github.thiesi.chitchat.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val FLUSH = "ccm-live-flush-"

/**
 * #323b (PL, TL): counts what a screen reader announces from polite live regions: `TYPE_WINDOW_CONTENT_CHANGED` events
 * whose source, queried as the event arrives (as TalkBack does), has a live region and matches [carrier]. A source that
 * has lost its live region by then counts as silent, as it would for TalkBack.
 *
 * Every count is bracketed by delivered sentinels (PL, TL): [mark] changes the test-only [LiveFlushSentinel] twice, each
 * awaited before the next, so every change made before it has been delivered, and returns the count at that point. A
 * delivered sentinel is the positive control: a listener that receives nothing fails the mark, so "0 between mark N and
 * mark N+1" is evidence, and an "exactly 1" closed by a mark would catch a late extra announcement. The sentinel's text
 * is never the full line. A mark that times out is recorded, not thrown, so later steps and the case's capture still
 * run; [verify] rethrows it (cause kept, diagnostics in the message) before any count is asserted. No sleep, no retry.
 * Measurement only: it never acts on the UI.
 *
 * Compose UI 1.11.4 sends accessibility events only when the app's AccessibilityManager is enabled and lists at least
 * one enabled service (or with its internal test flag). On CI the plain UiAutomation connection wasn't enough (run
 * 45200317050: no event at all), so construction gives UiAutomation's service info a spoken feedback type, which lists
 * it as an enabled service, before the screen under test is composed. No touch exploration (taps keep their meaning).
 * [close] restores the original service info in a finally, so the rest of the suite runs as before.
 */
class LiveAnnouncements(private val carrier: (AccessibilityNodeInfo) -> Boolean) : AutoCloseable {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val seen = CopyOnWriteArrayList<String>()
    @Volatile private var flushed = -1
    // Diagnostic scalars only (no text): what reached the listener, for a flush that times out.
    private val events = AtomicInteger()
    private val contentChanges = AtomicInteger()
    private val nullSources = AtomicInteger()
    private val liveSources = AtomicInteger()

    private val originalInfo: AccessibilityServiceInfo? = automation.serviceInfo

    init {
        originalInfo?.let { original ->
            automation.serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
                flags = original.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
                notificationTimeout = original.notificationTimeout
            }
        }
        automation.setOnAccessibilityEventListener { event ->
            events.incrementAndGet()
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return@setOnAccessibilityEventListener
            contentChanges.incrementAndGet()
            val source = event.source ?: run { nullSources.incrementAndGet(); return@setOnAccessibilityEventListener }
            if (source.liveRegion == View.ACCESSIBILITY_LIVE_REGION_NONE) return@setOnAccessibilityEventListener
            liveSources.incrementAndGet()
            val description = source.contentDescription?.toString().orEmpty()
            if (description.startsWith(FLUSH)) description.removePrefix(FLUSH).toIntOrNull()?.let { flushed = maxOf(flushed, it) }
            else if (carrier(source)) seen += "$description|${source.state()}"
        }
    }

    /** Announcements counted so far (each "label|state"). */
    val count get() = seen.size
    fun spoken(): List<String> = seen.toList()

    @Volatile private var failure: AssertionError? = null

    /**
     * Closes and opens a bracket: waits until every event caused by changes made before this call has been delivered
     * (two sentinel changes, each awaited), then returns the count. A timeout is recorded with scalars telling which half
     * is missing (whether this process sees accessibility enabled with enabled services, which Compose 1.11.4 needs, and
     * what reached the listener at all); after one, later marks return at once and [verify] fails.
     */
    fun mark(compose: ComposeTestRule, sentinel: MutableIntState): Int {
        if (failure == null) repeat(2) {
            if (failure != null) return@repeat
            val next = sentinel.intValue + 1
            compose.runOnIdle { sentinel.intValue = next }
            try {
                compose.waitUntil(5_000) { flushed >= next }
            } catch (timeout: ComposeTimeoutException) {
                failure = AssertionError("Live sentinel $next not delivered: ${diagnostics()}", timeout)
            }
        }
        return seen.size
    }

    /** Fails with the first recorded mark timeout, if any: call before asserting any count. */
    fun verify() { failure?.let { throw it } }

    private fun diagnostics(): String {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AccessibilityManager::class.java)
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).size
        return "managerEnabled=${manager.isEnabled}, enabledServices=$services, touchExploration=${manager.isTouchExplorationEnabled}, " +
            "events=${events.get()}, contentChanges=${contentChanges.get()}, nullSources=${nullSources.get()}, " +
            "liveSources=${liveSources.get()}, flushed=$flushed, counted=${seen.size}"
    }

    override fun close() {
        try { automation.setOnAccessibilityEventListener(null) } finally { originalInfo?.let { automation.serviceInfo = it } }
    }
}

/** The node's state description (API 30+), or "". */
fun AccessibilityNodeInfo.state(): String = if (Build.VERSION.SDK_INT >= 30) stateDescription?.toString().orEmpty() else ""

/** A test-only 1dp polite live region whose description changes with [value]; see [LiveAnnouncements.flush]. */
@Composable
fun LiveFlushSentinel(value: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(1.dp).semantics { contentDescription = "$FLUSH$value"; liveRegion = LiveRegionMode.Polite })
}
