package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import org.junit.Assert.*
import org.junit.Test

class SavedSessionTest {
    private val server = (ServerAddress.parse("chat.example.org") as AddressResult.Valid).address
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L
    @Test fun retentionIsBoundedByIdleAndAbsoluteTimeWithoutExtendingServerValidity() {
        assertTrue(SavedSession(server.value, 7, listOf("CUSTOM=fixture"), now - day, now).usableFor(server, now))
        assertFalse(SavedSession(server.value, 7, listOf("CUSTOM=fixture"), now - 30 * day, now).usableFor(server, now))
        assertFalse(SavedSession(server.value, 7, listOf("CUSTOM=fixture"), now - 10 * day, now - 7 * day).usableFor(server, now))
    }
    @Test fun wrongOriginUnknownSchemaEmptyCookiesAndClockRollbackAreRejected() {
        assertFalse(SavedSession("https://other.example.org/", 7, listOf("CUSTOM=fixture"), now, now).usableFor(server, now))
        assertFalse(SavedSession(server.value, 7, listOf("CUSTOM=fixture"), now, now, 2).usableFor(server, now))
        assertFalse(SavedSession(server.value, 7, emptyList(), now, now).usableFor(server, now))
        assertFalse(SavedSession(server.value, 7, listOf("CUSTOM=fixture"), now, now + 1).usableFor(server, now))
    }
}
