package io.github.thiesi.chitchat.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
 */
class RateWaitLineTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = 1_000_000L

    private fun session(deadline: Long?) = SessionState(generation = 3, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        rateWaits = deadline?.let { mapOf(RateBudget.ROOM_SEND to RateWait(it, 3, 7)) } ?: emptyMap())

    private fun show(session: SessionState, notice: RateLimitNotice) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMonotonicClock provides { base + compose.mainClock.currentTime }) {
                ChitChatTheme(dark = false) {
                    RateWaitLine(session, notice, R.string.rate_wait_messages, R.string.rate_wait_messages_unknown)
                }
            }
        }
    }

    @Test fun aKnownWaitShowsUntilItsDeadlineAndOneOneShotClearsIt() {
        val start = compose.mainClock.currentTime
        show(session(base + start + 5 * 60_000), RateLimitNotice(RateBudget.ROOM_SEND, true))
        val minutes = context.getString(R.string.rate_wait_messages,
            context.resources.getQuantityString(R.plurals.wait_about_minutes, 5, 5))
        compose.onNodeWithTag("rate_wait_line").assertTextEquals(minutes)
        compose.mainClock.advanceTimeBy(4 * 60_000L)
        compose.onNodeWithTag("rate_wait_line").assertTextEquals(minutes) // Minutes never tick.
        compose.mainClock.advanceTimeBy(60_000L + 32)
        assertEquals("Cleared at the deadline", 0, compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes().size)
    }

    @Test fun underAMinuteReadsLessThanAMinute() {
        val start = compose.mainClock.currentTime
        show(session(base + start + 20_000), RateLimitNotice(RateBudget.ROOM_SEND, true))
        compose.onNodeWithTag("rate_wait_line").assertTextEquals(
            context.getString(R.string.rate_wait_messages, context.getString(R.string.rate_wait_under_minute)))
    }

    @Test fun anUnknownWaitReadsTryAgainLater() {
        show(session(null), RateLimitNotice(RateBudget.ROOM_SEND, false))
        compose.onNodeWithTag("rate_wait_line").assertTextEquals(context.getString(R.string.rate_wait_messages_unknown))
    }

    @Test fun aForeignOrExpiredDeadlineShowsNoLine() {
        val start = compose.mainClock.currentTime
        val foreign = session(base + start + 60_000).copy(generation = 4)
        show(foreign, RateLimitNotice(RateBudget.ROOM_SEND, true))
        assertEquals(0, compose.onAllNodesWithTag("rate_wait_line").fetchSemanticsNodes().size)
    }
}
