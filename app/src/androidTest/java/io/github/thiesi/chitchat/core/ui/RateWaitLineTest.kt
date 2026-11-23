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
import io.github.thiesi.chitchat.testing.LiveAnnouncements
import io.github.thiesi.chitchat.testing.LiveFlushSentinel
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
 * once per notice and window, never from the draft, SHORT while no measurement exists. Send carries every announcement:
 * its label stays, its state description is the full line with a polite live region, one trailing space flips per
 * refused tap, and clearing is silent. The announcement counts use real accessibility events (UiAutomation).
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

    // The composer slot: a fixed-height column with a weighted timeline, the slot, and a composer of a given height.
    private var columnHeight by mutableStateOf(600.dp)
    private var composerHeight by mutableStateOf(48.dp)
    private var window by mutableStateOf<Any>("portrait")
    private var fit: RateLineFit? = null

    private fun slot(notice: RateLimitNotice, deadline: Long?, measured: Boolean = true) = host {
        val held = remember { RateLineFit() }.also { fit = it }
        Column(Modifier.width(320.dp).height(columnHeight), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().then(if (measured) Modifier.rateFitTimeline(held, window) else Modifier))
            RateWaitSlot(held, session(deadline), notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown,
                window, 320.dp, 6.dp, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().height(composerHeight))
        }
    }

    private fun settle() { repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
    private fun known5() = base + compose.mainClock.currentTime + 5 * 60_000

    /** TL condition 1: a late or missing measurement gives the short form, never a missing line and never the full one. */
    @Test fun withoutAMeasurementTheSlotShowsTheShortFormNotNothing() {
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), known5(), measured = false)
        settle()
        assertEquals(1, lines().size)
        assertEquals(true, shown?.first)
    }

    @Test fun aRoomyTimelineGetsTheFullLineAndATightOneTheShortForm() {
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), known5())
        settle()
        assertEquals(false to full5(), shown)
        assertEquals(RateLineForm.FULL, fit?.form)
        columnHeight = 120.dp; window = "tight"
        settle()
        assertEquals(1, lines().size)
        assertEquals(true, shown?.first)
        assertEquals(RateLineForm.SHORT, fit?.form)
    }

    /** TL's amended condition 3: where not even the short form fits at the composer's natural height, no line; it returns. */
    @Test fun withNoRoomForTheShortFormTheLineHidesAndReturnsWhenTheWindowGrows() {
        columnHeight = 70.dp; window = "keyboard"
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), known5())
        settle()
        assertEquals(0, lines().size)
        assertEquals(RateLineForm.HIDDEN, fit?.form)
        assertEquals(true, fit?.detailsFirst)
        columnHeight = 600.dp; window = "no keyboard"
        settle()
        assertEquals(1, lines().size)
        assertEquals(RateLineForm.FULL, fit?.form)
    }

    /** TL condition: the composer growing with the draft (same window) never re-decides, so the form can't flip while typing. */
    @Test fun theDraftGrowingDoesNotFlipTheForm() {
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), known5())
        settle()
        assertEquals(false, shown?.first)
        composerHeight = 560.dp // The timeline now has no row left, but the window is the same.
        settle()
        assertEquals(false, shown?.first)
    }

    /** A full/short swap (a new window) keeps the same node and the same semantics. */
    @Test fun aSwapKeepsTheLinesSemantics() {
        slot(RateLimitNotice(RateBudget.ROOM_SEND, true), known5())
        settle()
        assertEquals(false, shown?.first)
        val before = line()
        columnHeight = 120.dp; window = "keyboard"
        settle()
        assertEquals(true, shown?.first)
        val after = line()
        assertEquals("The same node", before.id, after.id)
        assertEquals(full5(), after.config[SemanticsProperties.ContentDescription].single())
        assertEquals("Identical semantics in both forms", before.config.toList().map { it.key.name to it.value },
            after.config.toList().map { it.key.name to it.value })
    }

    // Send, the announcement carrier: its label, state description and live region.
    private var notice by mutableStateOf<RateLimitNotice?>(null)
    private var deadline by mutableStateOf<Long?>(null)
    private var repeats by mutableIntStateOf(0)
    private var sending by mutableStateOf(false)
    private val sentinel = mutableIntStateOf(0)

    private fun send(paused: Boolean = true) = host(paused) {
        Column {
            Box(Modifier.size(48.dp).semantics { contentDescription = "Send" }.then(Modifier.semantics { testTag = "send" })
                .rateWaitState(rateWaitSpoken(session(deadline), notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown),
                    repeats % 2 == 1, sending))
            LiveFlushSentinel(sentinel.intValue)
        }
    }

    private fun sendNode() = compose.onNodeWithTag("send").fetchSemanticsNode().config

    /** PL condition 1: the label stays "Send"; the full line only in its state description, only while a notice is current. */
    @Test fun sendKeepsItsLabelAndCarriesTheFullLineOnlyWhileANoticeIsCurrent() {
        send()
        assertFalse(sendNode().contains(SemanticsProperties.StateDescription))
        assertFalse(sendNode().contains(SemanticsProperties.LiveRegion))
        deadline = known5(); notice = RateLimitNotice(RateBudget.ROOM_SEND, true); settle()
        assertEquals(listOf("Send"), sendNode()[SemanticsProperties.ContentDescription])
        assertEquals(full5(), sendNode()[SemanticsProperties.StateDescription])
        assertEquals(LiveRegionMode.Polite, sendNode()[SemanticsProperties.LiveRegion])
        repeats = 1; settle()
        assertEquals("One trailing space, present or absent", "${full5()} ", sendNode()[SemanticsProperties.StateDescription])
        repeats = 2; settle()
        assertEquals(full5(), sendNode()[SemanticsProperties.StateDescription])
        sending = true; settle()
        assertFalse("No live region while sending", sendNode().contains(SemanticsProperties.LiveRegion))
        sending = false; compose.mainClock.advanceTimeBy(5 * 60_000L + 32); settle()
        assertFalse("Cleared at the deadline", sendNode().contains(SemanticsProperties.StateDescription))
        assertFalse(sendNode().contains(SemanticsProperties.LiveRegion))
        assertEquals(listOf("Send"), sendNode()[SemanticsProperties.ContentDescription])
    }

    private fun announcements() = LiveAnnouncements { node -> node.contentDescription?.toString() == "Send" }

    /**
     * TL/PL: exactly one announcement when a notice appears and per refused tap; none on clearing (unknown wait dropped).
     * Every count is between two delivered sentinels (the positive control).
     */
    @Test fun sendAnnouncesOnceOnAppearanceAndPerRefusalAndClearsSilently() {
        announcements().use { heard ->
            send(paused = false)
            val start = heard.mark(compose, sentinel)
            compose.runOnIdle { notice = RateLimitNotice(RateBudget.ROOM_SEND, false) }
            val appeared = heard.mark(compose, sentinel)
            // A refused tap during a server attempt: no live region while sending, then the flip lands with the refusal.
            compose.runOnIdle { sending = true }
            val sendingMark = heard.mark(compose, sentinel)
            compose.runOnIdle { sending = false; repeats++ }
            val firstTap = heard.mark(compose, sentinel)
            compose.runOnIdle { repeats++ }
            val secondTap = heard.mark(compose, sentinel)
            compose.runOnIdle { notice = null } // The unknown wait dropped on the next edit, or a successful send.
            val cleared = heard.mark(compose, sentinel)
            heard.verify()
            assertEquals("Nothing before the notice: ${heard.spoken()}", 0, start)
            assertEquals("Once on appearance: ${heard.spoken()}", 1, appeared - start)
            assertEquals("Silent while sending: ${heard.spoken()}", 0, sendingMark - appeared)
            assertEquals("Once per refused tap: ${heard.spoken()}", 1, firstTap - sendingMark)
            assertEquals("Once per refused tap: ${heard.spoken()}", 1, secondTap - firstTap)
            assertEquals("Clearing is silent: ${heard.spoken()}", 0, cleared - secondTap)
        }
    }

    /** PL condition 3: the known wait's deadline clears Send's state silently (between two delivered sentinels). */
    @Test fun theDeadlineClearsSendSilently() {
        announcements().use { heard ->
            notice = RateLimitNotice(RateBudget.ROOM_SEND, true)
            deadline = base + compose.mainClock.currentTime + 2_000
            send(paused = false)
            val before = heard.mark(compose, sentinel)
            compose.mainClock.advanceTimeBy(2_500)
            compose.waitForIdle()
            val stateAfter = compose.onNodeWithTag("send").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
            val after = heard.mark(compose, sentinel)
            heard.verify()
            assertNull("Cleared at the deadline", stateAfter)
            assertEquals("The deadline is silent: ${heard.spoken()}", 0, after - before)
        }
    }
}
