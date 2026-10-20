package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import org.junit.Assert.*
import org.junit.Test

class DirectNavigationTest {
    private val server = (ServerAddress.parse("https://chat.example.test:8443") as AddressResult.Valid).address
    private val session = SessionState(server = server, generation = 2, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Viewer", listOf("member"), 3, false))
    @Test fun restoredScopeNeverTransfersToAnotherServerUserGenerationVersionOrPhase() {
        assertTrue(matchesDirectScope(session, 2, 7, 3, server.value))
        assertFalse(matchesDirectScope(session, 3, 7, 3, server.value))
        assertFalse(matchesDirectScope(session, 2, 8, 3, server.value))
        assertFalse(matchesDirectScope(session, 2, 7, 4, server.value))
        assertFalse(matchesDirectScope(session, 2, 7, 3, "https://elsewhere.example/"))
        assertFalse(matchesDirectScope(session.copy(phase = SessionPhase.SIGNED_OUT), 2, 7, 3, server.value))
    }
    @Test fun generatedRouteEncodesOnlyScopeAndRejectsInvalidOrSelfPeers() {
        assertEquals("direct/8?generation=2&viewer=7&version=3&server=https%3A%2F%2Fchat.example.test%3A8443%2F", directRoute(session, 8))
        assertTrue(directRoute(session)!!.startsWith("direct?generation="))
        assertNull(directRoute(session, 7)); assertNull(directRoute(session, -1))
        assertNull(directRoute(session.copy(phase = SessionPhase.GUEST)))
        assertNull(directRoute(session.copy(server = null)))
    }
}
