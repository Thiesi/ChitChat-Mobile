package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class SearchNavigationTest {
    private val session = SessionState(server = (ServerAddress.parse("https://xn--bcher-kva.example:8443") as AddressResult.Valid).address,
        phase = SessionPhase.AUTHENTICATED, generation = 12, user = SessionUser(7, "Viewer", listOf("member"), 3, false))
    @Test fun restoredScopeRoundTripIncludesOnlyOwnershipAndEscapesTheOrigin() {
        val route = checkNotNull(searchRoute(session))
        val fields = URI(route).rawQuery.split('&').associate { field ->
            val parts = field.split('=', limit = 2); parts[0] to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals(setOf("generation", "viewer", "version", "server"), fields.keys)
        assertTrue(matchesSearchScope(session, fields.getValue("generation").toLong(), fields.getValue("viewer").toLong(),
            fields.getValue("version").toInt(), fields["server"]))
    }
    @Test fun everyChangedOwnershipComponentRejectsARestoredSearchRoute() {
        val originalUser = checkNotNull(session.user)
        for (replacement in listOf(session.copy(generation = 13), session.copy(user = originalUser.copy(id = 8)),
            session.copy(user = originalUser.copy(sessionVersion = 4)),
            session.copy(server = (ServerAddress.parse("https://other.example.test") as AddressResult.Valid).address)))
            assertFalse(matchesSearchScope(replacement, 12, 7, 3, session.server!!.value))
    }
    @Test fun guestsSignedOutAndMissingOriginNeverProduceASearchRoute() {
        assertNull(searchRoute(session.copy(phase = SessionPhase.GUEST, user = session.user!!.copy(guest = true))))
        assertNull(searchRoute(session.copy(phase = SessionPhase.SIGNED_OUT, user = null)))
        assertNull(searchRoute(session.copy(server = null)))
        assertFalse(matchesSearchScope(session, -1, -1, -1, ""))
    }
    @Test fun validMemberRouteDoesNotCarryAUsernameOrAnyQuerySlot() {
        val route = checkNotNull(searchRoute(session))
        assertFalse(route.contains("Viewer")); assertFalse(route.contains("query=")); assertFalse(route.contains("message_id"))
    }
}
