package io.github.thiesi.chitchat.feature.auth

import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.ResetState
import io.github.thiesi.chitchat.core.session.ResetSupport
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import org.junit.Assert.*
import org.junit.Test

/**
 * Step 4a's discovery row across the interleavings of #298, driven exactly as the screen's keyed effect drives it:
 * observe, then ask if it should. The "repository" here keeps the real guards (it drops an ask while busy, not anonymous
 * or already checking) and counts GETs; a GET's answer arrives when the test says.
 */
class ResetDiscoveryVisitTest {
    private val home = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private var state = SessionState(home, 1, SessionPhase.ANONYMOUS)
    private var visit = ResetDiscoveryVisit()
    private var gets = 0
    private var inFlight: Long? = null

    /** One run of the screen's effect (it reruns whenever generation, server, phase, busy or support change). */
    private fun effect() {
        visit = visit.observe(state)
        if (visit.shouldAsk(state)) { visit = visit.asked(state); discover() }
        visit = visit.observe(state)
    }
    private fun discover() {
        if (state.phase != SessionPhase.ANONYMOUS || state.busy || state.reset.support == ResetSupport.CHECKING) return
        gets++; inFlight = state.generation
        set(state.copy(reset = ResetState(support = ResetSupport.CHECKING)))
    }
    private fun answer(support: ResetSupport) {
        val asked = inFlight!!; inFlight = null
        // Fenced to its runtime, like the repository's finally.
        if (state.generation == asked && state.reset.support == ResetSupport.CHECKING) set(state.copy(reset = ResetState(support = support)))
    }
    private fun set(next: SessionState) { state = next; effect() }
    private fun support() = state.reset.support

    @Test fun busyAtEntryShowsTheReservedRowAndAsksExactlyOnceWhenItClears() {
        set(state.copy(busy = true))
        assertEquals("Nothing asked while busy", 0, gets)
        assertEquals("The reserved row: unknown, never the static line", ResetSupport.UNKNOWN, support())
        set(state.copy(busy = false))
        assertEquals(1, gets)
        assertEquals(ResetSupport.CHECKING, support())
        answer(ResetSupport.SUPPORTED)
        repeat(3) { set(state.copy(busy = true)); set(state.copy(busy = false)) }
        assertEquals("A confirmed SUPPORTED is never asked again", 1, gets)
    }

    @Test fun anAskDroppedByARaceIsAskedAgainWhenTheSessionSettles() {
        // The screen saw not-busy, but the repository saw busy (it flipped in between): the ask is dropped.
        visit = visit.observe(state)
        assertTrue(visit.shouldAsk(state))
        visit = visit.asked(state)
        state = state.copy(busy = true); discover(); effect()
        assertEquals("Dropped", 0, gets)
        set(state.copy(busy = false))
        assertEquals("Asked again once settled", 1, gets)
    }

    @Test fun aFailureOrA404IsNeverAskedAgainInThisRuntime() {
        effect()
        assertEquals(1, gets)
        answer(ResetSupport.UNSUPPORTED)
        repeat(3) { set(state.copy(busy = true)); set(state.copy(busy = false)) }
        set(state.copy(phase = SessionPhase.CONNECTING)); set(state.copy(phase = SessionPhase.ANONYMOUS))
        assertEquals("No retry of a failed GET", 1, gets)
    }

    @Test fun aSynchronousAnswerCompletesTheAskWithoutALoop() {
        // A fixture can answer inside the call: the visit never sees CHECKING.
        visit = visit.observe(state)
        visit = visit.asked(state)
        gets++; state = state.copy(reset = ResetState(support = ResetSupport.UNSUPPORTED)); effect()
        repeat(3) { set(state.copy(busy = true)); set(state.copy(busy = false)) }
        assertEquals(1, gets)
    }

    @Test fun aNewRuntimeIsAskedOnceAfterItSettles() {
        effect(); answer(ResetSupport.SUPPORTED)
        // A new runtime (generation) starts unknown and busy; on the real root it is also a new visit (page key).
        set(SessionState(home, 2, SessionPhase.ANONYMOUS, busy = true))
        assertEquals(1, gets)
        set(state.copy(busy = false))
        assertEquals("Exactly one ask of the new runtime", 2, gets)
        answer(ResetSupport.UNSUPPORTED)
        repeat(3) { set(state.copy(busy = true)); set(state.copy(busy = false)) }
        assertEquals(2, gets)
    }

    @Test fun theOldRuntimesAnswerIsNeverShownInTheNewOne() {
        effect()
        assertEquals(1, gets)
        set(SessionState(home, 2, SessionPhase.ANONYMOUS))
        assertEquals("The new runtime asks", 2, gets)
        // The old answer arrives for generation 1: fenced, nothing written.
        inFlight = 1; answer(ResetSupport.SUPPORTED)
        assertEquals(ResetSupport.CHECKING, state.reset.support)
    }

    @Test fun aNewVisitAsksAgainOnceAfterAnEarlierFailure() {
        state = state.copy(reset = ResetState(support = ResetSupport.UNSUPPORTED))
        effect()
        assertEquals("This visit's one ask", 1, gets)
        answer(ResetSupport.SUPPORTED)
        repeat(3) { set(state.copy(busy = true)); set(state.copy(busy = false)) }
        assertEquals(1, gets)
    }

    @Test fun theVisitSurvivesSaving() {
        effect(); answer(ResetSupport.SUPPORTED)
        assertNotEquals(ResetDiscoveryVisit(), visit)
        assertEquals(visit, ResetDiscoveryVisit.restore(ResetDiscoveryVisit.save(visit)))
        assertEquals(ResetDiscoveryVisit(), ResetDiscoveryVisit.restore(ResetDiscoveryVisit.save(ResetDiscoveryVisit())))
    }
}
