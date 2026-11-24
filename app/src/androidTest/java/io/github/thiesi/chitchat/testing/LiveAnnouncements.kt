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
 * **What counts (TL and PL rule, a model of TalkBack, not a proof):** an announcement is a carrier event with a
 * non-empty spoken text (its contentDescription) that differs from the last counted one. Clearing to empty counts 0; a
 * move or a resize with identical text is an event (Compose sends `SUBTREE` changes, run 45234305565) but isn't
 * counted; every raw event stays listed in [spoken]. A zero-size node sends nothing at all (run 45233308933). Real speech is gated by the owner's TalkBack check: no speech on a keyboard swap, rotation or full/short
 * swap; one per appearance and per refused tap.
 *
 * Every count is bracketed by delivered sentinels (PL, TL): [mark] changes the test-only [LiveFlushSentinel] twice, each
 * awaited before the next, so every change made before it has been delivered, and returns the count at that point. A
 * delivered sentinel is the positive control: a listener that receives nothing fails the mark, so "0 between mark N and
 * mark N+1" is evidence, and an "exactly 1" closed by a mark would catch a late extra announcement. The sentinel's text
 * is never the full line. A mark that times out is recorded, not thrown, so later steps and the case's capture still
 * run; [verify] rethrows it (cause kept, diagnostics in the message) before any count is asserted. No sleep, no retry.
 * Measurement only: it never acts on the UI.
 *
 * Compose UI 1.11.4 sends accessibility events only when the app's AccessibilityManager is enabled and lists an enabled
 * service, or with its internal test flag. On CI the manager is enabled but lists none (runs 45200317050, 45210942147),
 * so [attach] sets that flag on the screen's Compose views ([ComposeAccessibilityForcing], reset in [close]). Giving
 * UiAutomation a spoken feedback type was tried first (4246bc3) and dropped: the list stayed empty, and it touched global
 * state.
 */
class LiveAnnouncements(private val carrier: (AccessibilityNodeInfo) -> Boolean) : AutoCloseable {
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val seen = CopyOnWriteArrayList<String>()
    private val counted = CopyOnWriteArrayList<String>()
    private val raw = CopyOnWriteArrayList<String>()
    @Volatile private var lastCounted: String? = null
    // The counted total at each sentinel's delivery (in the listener), so a bracket ends exactly at its second sentinel.
    private val countAt = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile private var flushed = -1
    // TL diagnostic (run 45299066691): each sentinel's event time (uptime), beside the counted events' t=, so a failure
    // says whether an event was late (after its bracket's sentinels) or caused by a later change.
    private val sentinelAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    // Diagnostic scalars only (no text): what reached the listener, for a flush that times out.
    private val events = AtomicInteger()
    private val contentChanges = AtomicInteger()
    private val nullSources = AtomicInteger()
    private val liveSources = AtomicInteger()

    private val forced = mutableListOf<View>()
    @Volatile private var primed = false

    init {
        automation.setOnAccessibilityEventListener { event ->
            events.incrementAndGet()
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return@setOnAccessibilityEventListener
            contentChanges.incrementAndGet()
            val source = event.source ?: run { nullSources.incrementAndGet(); return@setOnAccessibilityEventListener }
            if (source.liveRegion == View.ACCESSIBILITY_LIVE_REGION_NONE) return@setOnAccessibilityEventListener
            liveSources.incrementAndGet()
            val description = source.contentDescription?.toString().orEmpty()
            if (description.startsWith(FLUSH)) description.removePrefix(FLUSH).toIntOrNull()?.let {
                countAt[it] = counted.size
                sentinelAt.putIfAbsent(it, event.eventTime)
                flushed = maxOf(flushed, it)
            }
            // Measurement (TL): each counted event's change types and the carrier's scalar state at that moment, so a
            // count above one shows which of the carrier's properties changed. No text beyond the carrier's own label.
            else {
                if (carrier(source)) {
                    val bounds = android.graphics.Rect().also { source.getBoundsInScreen(it) }
                    val entry = "$description|${source.state()}|types=${changeTypes(event.contentChangeTypes)}|enabled=${source.isEnabled}" +
                        "|live=${source.liveRegion}|bounds=${bounds.toShortString()}|t=${event.eventTime}"
                    raw += entry
                    // The rule: non-empty (the carrier matches only its full line) and different from the last counted.
                    if (description.isNotEmpty() && description != lastCounted) { lastCounted = description; counted += description; seen += entry }
                }
            }
        }
    }

    private fun changeTypes(types: Int): String = if (types == 0) "UNDEFINED" else listOf(
        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE to "SUBTREE", AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT to "TEXT",
        AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION to "CONTENT_DESCRIPTION",
        AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION to "STATE_DESCRIPTION",
        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE to "PANE_TITLE", AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED to "PANE_APPEARED",
        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED to "PANE_DISAPPEARED", 0x800 to "ERROR", 0x1000 to "ENABLED",
    ).filter { (bit, _) -> types and bit != 0 }.joinToString("+") { it.second }.ifEmpty { "0x" + Integer.toHexString(types) }

    /** Announcements counted so far (rule 1). */
    val count get() = counted.size
    /** The counted spoken texts, in order. */
    fun texts(): List<String> = counted.toList()
    /** Diagnostics: the counted events, then every raw carrier event (text, change types, scalars). */
    fun spoken(): String = "counted=${seen.toList()} raw=${raw.toList()} " +
        "sentinels=${sentinelAt.toSortedMap().map { (n, t) -> "$n@t=$t(count=${countAt[n]})" }}"

    @Volatile private var failure: AssertionError? = null

    /**
     * Closes and opens a bracket: waits until every event caused by changes made before this call has been delivered
     * (two sentinel changes, each awaited), then returns the count. A timeout is recorded with scalars telling which half
     * is missing (whether this process sees accessibility enabled with enabled services, which Compose 1.11.4 needs, and
     * what reached the listener at all); after one, later marks return at once and [verify] fails.
     */
    fun mark(compose: ComposeTestRule, sentinel: MutableIntState): Int {
        // One unawaited change first: once the gate opens, the delegate's first diff may only take its baseline.
        if (!primed) { primed = true; compose.runOnIdle { sentinel.intValue += 1 }; compose.waitForIdle() }
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
        // TL: the bracket ends at this mark's second sentinel; the first was set after the counted action, so every diff
        // holding the action had been processed before the second was even set.
        return countAt[sentinel.intValue] ?: counted.size
    }

    /** Fails with the first recorded mark timeout, if any: call before asserting any count. */
    fun verify() { failure?.let { throw it } }

    private fun diagnostics(): String {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AccessibilityManager::class.java)
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).size
        return "managerEnabled=${manager.isEnabled}, enabledServices=$services, touchExploration=${manager.isTouchExplorationEnabled}, " +
            "events=${events.get()}, contentChanges=${contentChanges.get()}, nullSources=${nullSources.get()}, " +
            "liveSources=${liveSources.get()}, flushed=$flushed, counted=${counted.size}"
    }

    /**
     * Opens Compose's accessibility-event gate on every Compose view under [root] (main thread, after the content is
     * attached, before any counted change). Throws loudly when it can't ([ComposeAccessibilityForcing]).
     */
    fun attach(root: View) { forced += ComposeAccessibilityForcing.forceAll(root, true) }

    override fun close() {
        try { automation.setOnAccessibilityEventListener(null) } finally {
            if (forced.isNotEmpty()) InstrumentationRegistry.getInstrumentation().runOnMainSync {
                forced.forEach { runCatching { ComposeAccessibilityForcing.set(it, false) } }
            }
        }
    }
}

/** The node's state description (API 30+), or "". */
fun AccessibilityNodeInfo.state(): String = if (Build.VERSION.SDK_INT >= 30) stateDescription?.toString().orEmpty() else ""

/** A test-only 1dp polite live region whose description changes with [value]; see [LiveAnnouncements.flush]. */
@Composable
fun LiveFlushSentinel(value: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(1.dp).semantics { contentDescription = "$FLUSH$value"; liveRegion = LiveRegionMode.Polite })
}
