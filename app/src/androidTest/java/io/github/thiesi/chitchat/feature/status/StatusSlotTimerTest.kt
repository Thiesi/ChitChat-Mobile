package io.github.thiesi.chitchat.feature.status

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Every cancellation path of the slot's three timers (row 8, Back online, the wait gate): background and
 * return, phase flaps, configuration change, deadline change and leaving composition. Virtual clock only.
 */
class StatusSlotTimerTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private val signedIn = SessionState(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address,
        phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        installationName = "The Common Room")

    private var session by mutableStateOf(signedIn)
    private var realtime by mutableStateOf(RealtimeState(phase = RealtimePhase.LIVE, generation = 1))
    private var shown by mutableStateOf(true)
    private var now = 0L
    private lateinit var owner: TestOwner

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun start() { compose.mainClock.autoAdvance = false }

    @androidx.compose.runtime.Composable
    private fun SlotContent() {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            ChitChatTheme(dark = false) { Surface { if (shown) StatusSlot(session, realtime, {}, {}, {}, nowMillis = { now }) } }
        }
    }

    private fun show() {
        owner = TestOwner().also { it.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { SlotContent() }
    }

    private fun restorable(): StateRestorationTester {
        owner = TestOwner().also { it.registry.currentState = Lifecycle.State.RESUMED }
        return StateRestorationTester(compose).also { it.setContent { SlotContent() } }
    }

    private fun stop() = compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
    private fun resume() = compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    private fun advance(millis: Long) = compose.mainClock.advanceTimeBy(millis)
    private fun unreachable() = compose.onNodeWithText(text(R.string.status_unreachable_title, "The Common Room"), substring = true)
    private fun backOnline() = compose.onNodeWithText(text(R.string.status_back_online), substring = true)
    private fun slot() = compose.onNodeWithTag("status_slot")

    private fun recoverToBackOnline() {
        realtime = realtime.copy(phase = RealtimePhase.OFFLINE); advance(100)
        realtime = realtime.copy(phase = RealtimePhase.LIVE); advance(500)
        backOnline().assertExists()
    }

    // Row 8 ---------------------------------------------------------------------------------------

    @Test fun row8BackgroundDropsTheCountAndReturnStartsAgain() {
        show()
        realtime = realtime.copy(phase = RealtimePhase.RECONNECTING); advance(3_000)
        stop(); advance(10_000); resume()
        advance(4_000); unreachable().assertDoesNotExist()
        advance(1_100); unreachable().assertExists()
    }

    @Test fun row8KeepsCountingAcrossConnectingAndReconnectingButLeavingResets() {
        show()
        realtime = realtime.copy(phase = RealtimePhase.CONNECTING); advance(3_000)
        realtime = realtime.copy(phase = RealtimePhase.RECONNECTING); advance(2_100)
        unreachable().assertExists()
        realtime = realtime.copy(phase = RealtimePhase.LIVE); advance(100)
        realtime = realtime.copy(phase = RealtimePhase.RECONNECTING); advance(4_000)
        unreachable().assertDoesNotExist()
    }

    @Test fun row8SurvivesAConfigurationChange() {
        val restoration = restorable()
        realtime = realtime.copy(phase = RealtimePhase.RECONNECTING); advance(3_000)
        restoration.emulateSavedInstanceStateRestore()
        advance(1_000); unreachable().assertDoesNotExist()
        advance(1_100); unreachable().assertExists()
    }

    // Back online ---------------------------------------------------------------------------------

    @Test fun backOnlineEndsOnBackgroundAndNeverReturnsStuck() {
        show(); recoverToBackOnline()
        stop(); advance(100); resume(); advance(100)
        backOnline().assertDoesNotExist()
        advance(5_000); slot().assertDoesNotExist()
    }

    @Test fun aRecoveryWhileAwayIsNotAnnouncedOnReturn() {
        show()
        realtime = realtime.copy(phase = RealtimePhase.OFFLINE); advance(100)
        stop(); realtime = realtime.copy(phase = RealtimePhase.LIVE); advance(100); resume(); advance(100)
        backOnline().assertDoesNotExist()
    }

    @Test fun backOnlineAfterAConfigurationChangeIsNeverStuck() {
        val restoration = restorable()
        recoverToBackOnline()
        restoration.emulateSavedInstanceStateRestore()
        advance(3_000); slot().assertDoesNotExist()
    }

    // Wait gate -----------------------------------------------------------------------------------

    @Test fun aDeadlineChangeDuringTheCountdownFollowsTheNewDeadline() {
        val rate = ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 12)
        show()
        session = signedIn.copy(retryAtMillis = 12_000, error = rate); advance(100)
        compose.onNodeWithContentDescription(text(R.string.status_rate_limited_under_minute)).assertExists()
        now = 5_000; advance(1_000)
        session = session.copy(retryAtMillis = 30_000); advance(100)
        now = 12_000; advance(1_000); slot().assertExists()
        now = 30_000; advance(1_100); slot().assertDoesNotExist()
    }

    @Test fun aSecondsCountdownIsOneStableAnnouncementNotOnePerTick() {
        show()
        session = signedIn.copy(retryAtMillis = 12_000, error = ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 12))
        advance(100)
        val spoken = text(R.string.status_rate_limited_under_minute)
        compose.onNodeWithContentDescription(spoken).assertExists()
        val first = compose.onNodeWithTag("status_slot").fetchSemanticsNode().id
        now = 5_000; advance(1_100)
        compose.onNodeWithContentDescription(spoken).assertExists()
        assertEquals("The same node, so no new announcement", first, compose.onNodeWithTag("status_slot").fetchSemanticsNode().id)
    }

    @Test fun aWaitThatEndsWhileAwayIsGoneOnReturn() {
        show()
        session = signedIn.copy(retryAtMillis = 12_000, error = ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 12))
        advance(100); slot().assertExists()
        stop(); now = 20_000; advance(15_000); resume(); advance(100)
        slot().assertDoesNotExist()
    }

    // Leaving composition -------------------------------------------------------------------------

    @Test fun leavingCompositionDuringAnyWindowLeavesNothingBehind() {
        show()
        realtime = realtime.copy(phase = RealtimePhase.RECONNECTING); advance(3_000)
        shown = false; advance(100)
        realtime = realtime.copy(phase = RealtimePhase.LIVE); shown = true; advance(100)
        slot().assertDoesNotExist()
        recoverToBackOnline()
        shown = false; advance(100); shown = true; advance(100)
        backOnline().assertDoesNotExist()
        advance(3_000); slot().assertDoesNotExist()
    }
}
