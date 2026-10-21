package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.*
import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    private val server = (ServerAddress.parse("https://chat.example.test:8443") as AddressResult.Valid).address
    private val session = SessionState(server = server, generation = 2, phase = SessionPhase.AUTHENTICATED,
        user = SessionUser(7, "Private fixture name", listOf("member"), 3, false))
    @Test fun restoredEntryCannotTransferToAnotherServerAccountGenerationVersionOrGuest() {
        assertTrue(matchesNotificationScope(session, 2, 7, 3, server.value))
        assertFalse(matchesNotificationScope(session, 3, 7, 3, server.value))
        assertFalse(matchesNotificationScope(session, 2, 8, 3, server.value))
        assertFalse(matchesNotificationScope(session, 2, 7, 4, server.value))
        assertFalse(matchesNotificationScope(session, 2, 7, 3, "https://other.example.test/"))
        assertFalse(matchesNotificationScope(session.copy(phase = SessionPhase.GUEST), 2, 7, 3, server.value))
    }
    @Test fun navigationRetainsOnlyEncodedNonSecretScopeAndRejectsAnonymousOrMissingServer() {
        assertEquals("notifications?generation=2&viewer=7&version=3&server=https%3A%2F%2Fchat.example.test%3A8443%2F", notificationRoute(session))
        assertNull(notificationRoute(session.copy(phase = SessionPhase.ANONYMOUS)))
        assertNull(notificationRoute(session.copy(server = null)))
        assertNull(notificationRoute(session.copy(user = session.user!!.let { SessionUser(it.id, it.username, it.roles, it.sessionVersion, true) })))
    }
}
