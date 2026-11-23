package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * #323b1 (PL asserts): the wait line reads the stored deadline on the injected monotonic clock, under a minute reads
 * "less than a minute", minutes never tick, ONE composition-bound one-shot clears it at the deadline (nothing is sent:
 * the line has no action at all), and an unknown wait reads "Try again later." with no gate.
 *
 * #323b refinement (PL, TL): the line is silent text whose description is the full line in both forms; the composer slot
 * picks FULL (a 48dp timeline row left), SHORT or HIDDEN (not even the short form fits at the composer's natural height),
 * once per notice and window, never from the draft, SHORT while no measurement exists. The line is the one persistent
 * carrier node (identical semantics in all forms, empty with no notice); Send reads the line as its state without a live
 * region. The announcement counts (real accessibility events) live in #344's RateLineAnnouncementTest.
 */
class RateWaitLineTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = 1_000_000L
    private var shown: Pair<Boolean, String>? = null

    private fun session(deadline: Long?) = SessionState(generation = 3, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        rateWaits = deadline?.let { mapOf(RateBudget.ROOM_SEND to RateWait(it, 3, 7)) } ?: emptyMap())

    private fun host(paused: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = !paused
        compose.setContent {
            CompositionLocalProvider(LocalMonotonicClock provides { base + compose.mainClock.currentTime },
                LocalRateLineProbe provides { short, visible -> shown = short to visible }) {
                ChitChatTheme(dark = false) { content() }
            }
        }
        if (paused) compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun show(session: SessionState, notice: RateLimitNotice, short: Boolean = false) = host {
        RateWaitLine(session, notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown, short = short, tag = "rate_wait_line")
    }

    private fun lines() = compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes()
    private fun line() = compose.onNodeWithTag("rate_wait_line").fetchSemanticsNode()
    private fun spoken() = line().config[SemanticsProperties.ContentDescription].single()
    private fun minutes(n: Int) = context.resources.getQuantityString(R.plurals.wait_about_minutes, n, n)
    private fun full5() = context.getString(R.string.rate_wait_messages, minutes(5))

    @Test fun aKnownWaitShowsUntilItsDeadlineAndOneOneShotClearsIt() {
        val start = compose.mainClock.currentTime
        show(session(base + start + 5 * 60_000), RateLimitNotice(RateBudget.ROOM_SEND, true))
        assertEquals(false to full5(), shown)
        assertEquals(full5(), spoken())
        compose.mainClock.advanceTimeBy(4 * 60_000L)
        assertEquals(full5(), shown?.second) // Minutes never tick.
        compose.mainClock.advanceTimeBy(60_000L + 32)
        assertEquals("Cleared at the deadline", 0, lines().size)
    }

    @Test fun underAMinuteReadsLessThanAMinute() {
        val start = compose.mainClock.currentTime
        show(session(base + start + 20_000), RateLimitNotice(RateBudget.ROOM_SEND, true))
        assertEquals(context.getString(R.string.rate_wait_messages, context.getString(R.string.rate_wait_under_minute)), shown?.second)
    }

    @Test fun anUnknownWaitReadsTryAgainLater() {
        show(session(null), RateLimitNotice(RateBudget.ROOM_SEND, false))
        assertEquals(context.getString(R.string.rate_wait_messages_unknown), shown?.second)
    }

    @Test fun aForeignOrExpiredDeadlineShowsNoLine() {
        val start = compose.mainClock.currentTime
        show(session(base + start + 60_000).copy(generation = 4), RateLimitNotice(RateBudget.ROOM_SEND, true))
        assertEquals(0, lines().size)
    }

    /** PL refinement: the short form keeps the {wait} rules and the one-shot, and its spoken text is the full line. */
    @Test fun theShortFormShowsTryAgainInTheWaitButSpeaksTheFullLine() {
        val start = compose.mainClock.currentTime
        show(session(base + start + 5 * 60_000), RateLimitNotice(RateBudget.ROOM_SEND, true), short = true)
        assertEquals(true to context.getString(R.string.rate_wait_short, minutes(5)), shown)
        assertEquals(full5(), spoken())
        compose.mainClock.advanceTimeBy(5 * 60_000L + 32)
        assertEquals("Cleared at the deadline", 0, lines().size)
    }

    @Test fun theShortUnknownFormShowsTryAgainLaterButSpeaksTheFullLine() {
        show(session(null), RateLimitNotice(RateBudget.ROOM_SEND, false), short = true)
        assertEquals(true to context.getString(R.string.rate_wait_short_unknown), shown)
        assertEquals(context.getString(R.string.rate_wait_messages_unknown), spoken())
    }

    /** The line is silent text: no live region (Send carries every announcement). */
    @Test fun theLineHasNoLiveRegion() {
        show(session(null), RateLimitNotice(RateBudget.ROOM_SEND, false))
        assertFalse(line().config.contains(SemanticsProperties.LiveRegion))
    }

    // The composer slot laid out as the screens do: the timeline's block (a weighted timeline, then the persistent wait
    // line) above a composer of a given height, in a column spaced by 6dp. (Announcement counts: #344's
    // RateLineAnnouncementTest, kept there for #342's review budget.)
    private var columnHeight by mutableStateOf(600.dp)
    private var composerHeight by mutableStateOf(48.dp)
    private var window by mutableStateOf<Any>("portrait")
    private var fit: RateLineFit? = null
    private var notice by mutableStateOf<RateLimitNotice?>(null)
    private var deadline by mutableStateOf<Long?>(null)
    private var repeats by mutableIntStateOf(0)

    private fun slot(measured: Boolean = true, paused: Boolean = true) = host(paused) {
        val held = remember { RateLineFit() }.also { fit = it }
        Box {
            Column(Modifier.width(320.dp).height(columnHeight), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxWidth().then(if (measured) Modifier.rateFitTimeline(held, window) else Modifier))
                    RateWaitSlot(held, session(deadline), notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown,
                        window, 320.dp, 6.dp, Modifier.fillMaxWidth(), parity = repeats % 2 == 1)
                }
                Box(Modifier.fillMaxWidth().height(composerHeight))
            }
        }
    }

    private fun settle() { repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
    private fun known5() = base + compose.mainClock.currentTime + 5 * 60_000
    private fun drawn() = line().size.height > 2 * context.resources.displayMetrics.density // The hidden strip is 1dp.
    private fun lineDescription() = line().config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    /** TL condition 1: a late or missing measurement gives the short form, never a missing line and never the full one. */
    @Test fun withoutAMeasurementTheSlotShowsTheShortFormNotNothing() {
        deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
        slot(measured = false)
        settle()
        assertEquals(true, drawn())
        assertEquals(true, shown?.first)
    }

    @Test fun aRoomyTimelineGetsTheFullLineAndATightOneTheShortForm() {
        deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
        slot()
        settle()
        assertEquals(false to full5(), shown)
        assertEquals(RateLineForm.FULL, fit?.form)
        columnHeight = 120.dp; window = "tight"
        settle()
        assertEquals(true, drawn())
        assertEquals(true, shown?.first)
        assertEquals(RateLineForm.SHORT, fit?.form)
    }

    /** TL's amended condition 3: no room for the short form, nothing drawn (zero height, still the carrier); it returns. */
    @Test fun withNoRoomForTheShortFormTheLineHidesAndReturnsWhenTheWindowGrows() {
        columnHeight = 70.dp; window = "keyboard"
        deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
        slot()
        settle()
        assertFalse(drawn())
        assertEquals(RateLineForm.HIDDEN, fit?.form)
        assertEquals(true, fit?.detailsFirst)
        assertEquals("Still the carrier while hidden", full5(), lineDescription())
        columnHeight = 600.dp; window = "no keyboard"
        settle()
        assertEquals(true, drawn())
        assertEquals(RateLineForm.FULL, fit?.form)
    }

    /** TL condition: the composer growing with the draft (same window) never re-decides, so the form can't flip while typing. */
    @Test fun theDraftGrowingDoesNotFlipTheForm() {
        deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
        slot()
        settle()
        assertEquals(false, shown?.first)
        composerHeight = 560.dp // The timeline now has no row left, but the window is the same.
        settle()
        assertEquals(false, shown?.first)
    }

    /** Full, short and hidden are one node with identical semantics: only its size and visible text change. */
    @Test fun formSwapsKeepTheCarriersNodeAndSemantics() {
        deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
        slot()
        settle()
        val full = line()
        columnHeight = 120.dp; window = "tight"
        settle()
        val short = line()
        columnHeight = 70.dp; window = "keyboard"
        settle()
        val hidden = line()
        assertEquals(RateLineForm.HIDDEN, fit?.form)
        assertEquals("One node", setOf(full.id), setOf(full.id, short.id, hidden.id))
        val semantics = { node: androidx.compose.ui.semantics.SemanticsNode -> node.config.toList().map { it.key.name to it.value } }
        assertEquals(semantics(full), semantics(short))
        assertEquals(semantics(full), semantics(hidden))
        assertEquals(LiveRegionMode.Polite, full.config[SemanticsProperties.LiveRegion])
    }

    /** No notice: the carrier is there (zero height, polite live region) but says nothing and offers nothing. */
    @Test fun withNoNoticeTheCarrierIsEmpty() {
        slot()
        settle()
        assertFalse(drawn())
        assertNull(lineDescription())
        assertEquals(LiveRegionMode.Polite, line().config[SemanticsProperties.LiveRegion])
        assertFalse(line().config.contains(SemanticsProperties.Focused))
    }

    /** PL condition 1: Send keeps its label and reads the full line as its state; no live region (the line carries it). */
    @Test fun sendKeepsItsLabelAndReadsTheFullLineWithoutALiveRegion() {
        deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
        host {
            Box(Modifier.size(48.dp).semantics { contentDescription = "Send" }.then(Modifier.semantics { testTag = "send" })
                .rateWaitState(rateWaitSpoken(session(deadline), notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown)))
        }
        val node = compose.onNodeWithTag("send").fetchSemanticsNode().config
        assertEquals(listOf("Send"), node[SemanticsProperties.ContentDescription])
        assertEquals(full5(), node[SemanticsProperties.StateDescription])
        assertFalse(node.contains(SemanticsProperties.LiveRegion))
    }
}
