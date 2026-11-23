package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Rule
import org.junit.Test

/**
 * #323b1 (PL asserts): the wait line reads the stored deadline on the injected monotonic clock, under a minute reads
 * "less than a minute", minutes never tick, ONE composition-bound one-shot clears it at the deadline (nothing is sent:
 * the line has no action at all), and an unknown wait reads "Try again later." with no gate.
 *
 * #323b refinement (PL, TL): the spoken text is always the full line; the composer slot shows the full line only where
 * the timeline keeps a 48dp row, decides once per notice and window (never from the draft), shows the short form while
 * no measurement exists, and a full/short swap leaves the line's semantics unchanged, so it isn't announced again.
 */
class RateWaitLineTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = 1_000_000L
    private var shown: Pair<Boolean, String>? = null

    private fun session(deadline: Long?) = SessionState(generation = 3, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        rateWaits = deadline?.let { mapOf(RateBudget.ROOM_SEND to RateWait(it, 3, 7)) } ?: emptyMap())

    private fun host(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMonotonicClock provides { base + compose.mainClock.currentTime },
                LocalRateLineProbe provides { short, visible -> shown = short to visible }) {
                ChitChatTheme(dark = false) { content() }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun show(session: SessionState, notice: RateLimitNotice, short: Boolean = false) = host {
        RateWaitLine(session, notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown, short = short)
    }

    private fun lines() = compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes()
    private fun spoken() = compose.onNodeWithTag("rate_wait_line").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
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

    // The composer slot: a fixed-height column with a weighted timeline, the slot, and a composer of a given height.
    private var columnHeight by mutableStateOf(600.dp)
    private var composerHeight by mutableStateOf(48.dp)
    private var window by mutableStateOf<Any>("portrait")

    private fun slot(notice: RateLimitNotice, deadline: Long?, measured: Boolean = true) = host {
        val fit = androidx.compose.runtime.remember { RateLineFit() }
        Column(Modifier.width(320.dp).height(columnHeight), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().then(if (measured) Modifier.rateFitTimeline(fit, notice, window) else Modifier))
            RateWaitSlot(fit, session(deadline), notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown,
                window, 320.dp, 6.dp, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(composerHeight))
        }
    }

    private fun settle() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }

    /** TL condition 1: a late or missing measurement gives the short form, never a missing line and never the full one. */
    @Test fun withoutAMeasurementTheSlotShowsTheShortFormNotNothing() {
        val start = compose.mainClock.currentTime
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), base + start + 5 * 60_000, measured = false)
        settle()
        assertEquals(1, lines().size)
        assertEquals(true, shown?.first)
    }

    @Test fun aRoomyTimelineGetsTheFullLineAndATightOneTheShortForm() {
        val start = compose.mainClock.currentTime
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), base + start + 5 * 60_000)
        settle()
        assertEquals(false to full5(), shown)
        columnHeight = 120.dp; window = "tight"
        settle()
        assertEquals(1, lines().size)
        assertEquals(true, shown?.first)
    }

    /** TL condition: the composer growing with the draft (same window) never re-decides, so the form can't flip while typing. */
    @Test fun theDraftGrowingDoesNotFlipTheForm() {
        val start = compose.mainClock.currentTime
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), base + start + 5 * 60_000)
        settle()
        assertEquals(false, shown?.first)
        composerHeight = 560.dp // The timeline now has no row left, but the window is the same.
        settle()
        assertEquals(false, shown?.first)
    }

    /** TL condition 2: a full/short swap (a new window) keeps the same node and the same semantics, so no new announcement. */
    @Test fun aSwapKeepsTheLinesSemanticsSoItIsNotAnnouncedAgain() {
        val start = compose.mainClock.currentTime
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), base + start + 5 * 60_000)
        settle()
        assertEquals(false, shown?.first)
        val before = compose.onNodeWithTag("rate_wait_line").fetchSemanticsNode()
        columnHeight = 120.dp; window = "keyboard"
        settle()
        assertEquals(true, shown?.first)
        val after = compose.onNodeWithTag("rate_wait_line").fetchSemanticsNode()
        assertEquals("The same node", before.id, after.id)
        assertEquals(full5(), after.config[SemanticsProperties.ContentDescription].single())
        assertEquals(LiveRegionMode.Polite, after.config[SemanticsProperties.LiveRegion])
        assertEquals("Identical semantics in both forms", before.config.toList().map { it.key.name to it.value },
            after.config.toList().map { it.key.name to it.value })
    }

}
