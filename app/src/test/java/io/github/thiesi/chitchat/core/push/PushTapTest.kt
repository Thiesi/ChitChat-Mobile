package io.github.thiesi.chitchat.core.push

import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.network.SessionUser
import org.junit.Assert.*
import org.junit.Test

class PushTapTest {
    private val server = (ServerAddress.parse("chat.example.org") as AddressResult.Valid).address
    private fun session(phase: SessionPhase, id: Long? = 7) =
        SessionState(server = server, phase = phase, user = id?.let { SessionUser(it, "Viewer", listOf("member"), 3, false) })

    @Test fun onlyTheSignedInAccountItWasPostedForMatches() {
        val tap = PushTap(server.value, 7, "/messages.php?with=9")
        assertTrue(tap.matches(session(SessionPhase.AUTHENTICATED)))
        assertFalse("Another account", tap.matches(session(SessionPhase.AUTHENTICATED, id = 8)))
        assertFalse("Another server", PushTap("https://other.example.org/", 7, null).matches(session(SessionPhase.AUTHENTICATED)))
        for (phase in SessionPhase.entries - SessionPhase.AUTHENTICATED)
            assertFalse("Not signed in: $phase", tap.matches(session(phase)))
        assertEquals("PushTap", tap.toString())
    }

    @Test fun onlyStartUpWaitsEverythingElseDrops() {
        assertFalse("Bootstrapping the saved server", pushTapSettled(session(SessionPhase.CONNECTING), true, true))
        assertFalse("Settings not loaded yet", pushTapSettled(session(SessionPhase.NO_SERVER), false, false))
        assertFalse("Server chosen, not selected yet", pushTapSettled(session(SessionPhase.NO_SERVER), true, true))
        assertTrue("No server at all", pushTapSettled(session(SessionPhase.NO_SERVER), true, false))
        for (phase in listOf(SessionPhase.ANONYMOUS, SessionPhase.PENDING_MFA, SessionPhase.GUEST,
                SessionPhase.INVALIDATED, SessionPhase.SIGNED_OUT, SessionPhase.AUTHENTICATED))
            assertTrue("Settled: $phase", pushTapSettled(session(phase), true, true))
    }

    @Test fun onePendingTapConsumedExactlyOnceAndANewerOneReplacesIt() {
        val pending = PendingPushTap()
        val first = PushTap(server.value, 7, null); val second = PushTap(server.value, 7, "/")
        pending.offer(null); assertNull(pending.state.value)
        pending.offer(first); pending.offer(second)
        assertFalse("Replaced", pending.take(first))
        assertTrue(pending.take(second)); assertFalse("Only once", pending.take(second))
        assertNull(pending.state.value)
    }
}
