package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.session.RateBudget
import io.github.thiesi.chitchat.core.session.RateLimitNotice
import io.github.thiesi.chitchat.core.session.RateWait
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.testing.LiveAnnouncements
import io.github.thiesi.chitchat.testing.LiveFlushSentinel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * #323b (PL, TL): the composer wait line's announcements, counted from real accessibility events
 * ([LiveAnnouncements]: Compose's event gate opened by [io.github.thiesi.chitchat.testing.ComposeAccessibilityForcing],
 * every count between two delivered sentinels, spoken-text changes only). In #344 rather than beside RateWaitLineTest in
 * #342, which keeps the line's forms and semantics, because #342's diff hit the 126,000-byte review budget; #342
 * merges only after a paired run of these on its exact head.
 */
class RateLineAnnouncementTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = 1_000_000L

    private fun session(deadline: Long?) = SessionState(generation = 3, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        rateWaits = deadline?.let { mapOf(RateBudget.ROOM_SEND to RateWait(it, 3, 7)) } ?: emptyMap())

    private fun minutes(n: Int) = context.resources.getQuantityString(R.plurals.wait_about_minutes, n, n)
    private fun full5() = context.getString(R.string.rate_wait_messages, minutes(5))

    // The composer slot laid out as the screens do (see RateWaitLineTest), plus the test-only live sentinel.
    private var columnHeight by mutableStateOf(600.dp)
    private var window by mutableStateOf<Any>("portrait")
    private var fit: RateLineFit? = null
    private var notice by mutableStateOf<RateLimitNotice?>(null)
    private var deadline by mutableStateOf<Long?>(null)
    private var repeats by mutableIntStateOf(0)
    private val sentinel = mutableIntStateOf(0)

    private fun slot(paused: Boolean = false) {
        compose.mainClock.autoAdvance = !paused
        compose.setContent {
            CompositionLocalProvider(LocalMonotonicClock provides { base + compose.mainClock.currentTime }) {
                ChitChatTheme(dark = false) {
                    val held = remember { RateLineFit() }.also { fit = it }
                    Box {
                        Column(Modifier.width(320.dp).height(columnHeight), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f).fillMaxWidth().heightIn(min = 1.dp)) {
                                Box(Modifier.weight(1f).fillMaxWidth().rateFitTimeline(held, window))
                                RateWaitSlot(held, session(deadline), notice, R.string.rate_wait_messages,
                                    R.string.rate_wait_messages_unknown, window, 320.dp, 6.dp, Modifier.fillMaxWidth(),
                                    parity = repeats % 2 == 1)
                            }
                            Box(Modifier.fillMaxWidth().height(48.dp))
                        }
                        LiveFlushSentinel(sentinel.intValue)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun known5() = base + compose.mainClock.currentTime + 5 * 60_000
    private fun line() = compose.onNodeWithTag("rate_wait_line").fetchSemanticsNode()
    private fun lineDescription() = line().config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private fun announcements(text: () -> String) = LiveAnnouncements { node -> node.contentDescription?.toString()?.trimEnd() == text() }

    /** Opens Compose's accessibility-event gate on this test's window (see ComposeAccessibilityForcing). */
    private fun LiveAnnouncements.attachHost() {
        val activity = (compose as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity as android.app.Activity
        compose.runOnUiThread { attach(activity.window.decorView) }
    }

    /**
     * TL/PL: exactly one announcement when a notice appears and per refused tap; none for full/short/hidden swaps or on
     * clearing. Every count is between two delivered sentinels (the positive control).
     */
    @Test fun theLineAnnouncesOnceOnAppearanceAndPerRefusalAndIsSilentOtherwise() {
        val unknown = context.getString(R.string.rate_wait_messages_unknown)
        announcements { unknown }.use { heard ->
            slot()
            heard.attachHost()
            val start = heard.mark(compose, sentinel)
            compose.runOnIdle { notice = RateLimitNotice(RateBudget.ROOM_SEND, false) }
            val appeared = heard.mark(compose, sentinel)
            compose.runOnIdle { repeats++ }
            val firstTap = heard.mark(compose, sentinel)
            compose.runOnIdle { repeats++ }
            val secondTap = heard.mark(compose, sentinel)
            compose.runOnIdle { columnHeight = 120.dp; window = "tight" }
            val toShort = heard.mark(compose, sentinel)
            compose.runOnIdle { columnHeight = 70.dp; window = "keyboard" }
            val toHidden = heard.mark(compose, sentinel)
            compose.runOnIdle { columnHeight = 600.dp; window = "no keyboard" }
            val back = heard.mark(compose, sentinel)
            compose.runOnIdle { notice = null } // The unknown wait dropped on the next edit, or a successful send.
            val cleared = heard.mark(compose, sentinel)
            heard.verify()
            val all = heard.spoken()
            assertEquals("Nothing before the notice: $all", 0, start)
            assertEquals("Once on appearance: $all", 1, appeared - start)
            assertEquals("Once per refused tap: $all", 1, firstTap - appeared)
            assertEquals("Once per refused tap: $all", 1, secondTap - firstTap)
            assertEquals("Full to short is silent: $all", 0, toShort - secondTap)
            assertEquals("Short to hidden is silent: $all", 0, toHidden - toShort)
            assertEquals("Hidden to full is silent: $all", 0, back - toHidden)
            assertEquals("Clearing is silent: $all", 0, cleared - back)
            assertEquals("Appearance and two refused taps, in order: $all", listOf(unknown, "$unknown ", unknown), heard.texts())
        }
    }

    /**
     * TL's branch 2 for the hidden state (compact with the keyboard): a zero-height node announced nothing (run
     * 45233308933), so the line is a 1dp full-width strip at its place while hidden; a notice appearing then announces
     * once, and a refused tap once more.
     */
    @Test fun aNoticeAppearingHiddenAnnouncesOnceOnTheStrip() {
        announcements { full5() }.use { heard ->
            columnHeight = 70.dp; window = "keyboard"
            slot()
            heard.attachHost()
            val start = heard.mark(compose, sentinel)
            compose.runOnIdle { deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true) }
            val appeared = heard.mark(compose, sentinel)
            compose.runOnIdle { repeats++ }
            val tapped = heard.mark(compose, sentinel)
            heard.verify()
            assertEquals(RateLineForm.HIDDEN, fit?.form)
            val strip = line()
            val dp = context.resources.displayMetrics.density
            assertEquals("A 1dp strip", kotlin.math.round(dp), strip.size.height.toFloat(), 1f)
            assertEquals("Full width", kotlin.math.round(320 * dp), strip.size.width.toFloat(), 1f)
            val facts = nodeFacts(full5())
            assertEquals("Once on appearance while hidden: ${heard.spoken()} facts=$facts", 1, appeared - start)
            assertEquals("Once per refused tap while hidden: ${heard.spoken()}", 1, tapped - appeared)
            assertEquals(listOf(full5(), "${full5()} "), heard.texts())
        }
    }

    private data class NodeFacts(val visibleToUser: Boolean, val important: Boolean, val focusable: Boolean,
        val screenReaderFocusable: Boolean, val bounds: String)

    /** The accessibility node (as a screen reader sees it) whose description is [text]; null if there's none. */
    private fun nodeFacts(text: String): NodeFacts? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.contentDescription?.toString()?.trimEnd() == text) {
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                return NodeFacts(node.isVisibleToUser, node.isImportantForAccessibility, node.isFocusable,
                    android.os.Build.VERSION.SDK_INT >= 28 && node.isScreenReaderFocusable, bounds.toShortString())
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return null
    }

    /** PL condition 3: the known wait's deadline clears the carrier silently (between two delivered sentinels). */
    @Test fun theDeadlineClearsTheLineSilently() {
        announcements { full5() }.use { heard ->
            notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
            deadline = base + compose.mainClock.currentTime + 2_000
            slot()
            heard.attachHost()
            val before = heard.mark(compose, sentinel)
            compose.mainClock.advanceTimeBy(2_500)
            compose.waitForIdle()
            val descriptionAfter = lineDescription()
            val after = heard.mark(compose, sentinel)
            heard.verify()
            assertNull("Cleared at the deadline", descriptionAfter)
            assertEquals("The deadline is silent: ${heard.spoken()}", 0, after - before)
        }
    }
}
