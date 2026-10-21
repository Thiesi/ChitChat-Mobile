package io.github.thiesi.chitchat.core.conversation

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ConversationPolicyTest {
    @Test fun textAndActionsFollowPhpTrimmingAndUnicodeCharacterLimits() {
        assertEquals("empty_message", validateMessageBody(" \t\n\u0000"))
        assertEquals("empty_message", validateMessageBody("/me \n"))
        assertNull(validateMessageBody("/me " + "😀".repeat(4000)))
        assertEquals("message_too_long", validateMessageBody("😀".repeat(4001)))
        assertNull(validateMessageBody("\u00a0")) // PHP trim does not remove a non-breaking space.
        for (value in listOf("/dm Alice hello", "/ME hello", "/meow", "/pinged"))
            assertEquals("unknown_command", validateMessageBody(value))
    }
    @Test fun pingsHaveTheirOwnCommandAndFiveHundredCharacterLimit() {
        assertEquals("invalid_ping_command", validateMessageBody("/ping"))
        assertNull(validateMessageBody("/ping Alice"))
        assertNull(validateMessageBody("/ping Alice " + "😀".repeat(500)))
        assertEquals("ping_too_long", validateMessageBody("/ping Alice " + "😀".repeat(501)))
        assertEquals("unknown_command", validateMessageBody("/ping\tAlice"))
    }
    @Test fun composerAndTransportClassifyPrivatePingsWithTheSamePhpWhitespaceRules() {
        for (text in listOf("/ping", "/ping Alice", " \t\n/ping Alice \r")) assertTrue(isPrivatePingCommand(text))
        for (text in listOf("/pinged Alice", "/PING Alice", "/ping\tAlice", "\u00a0/ping Alice", "Hello /ping Alice"))
            assertFalse(isPrivatePingCommand(text))
    }
    @Test fun timestampParserAcceptsActualPdoAndDateAtomShapesWithoutDeviceTimezoneGuessing() {
        val expected = Instant.parse("2026-07-17T08:00:00Z")
        for (text in listOf("2026-07-17 08:00:00+00", "2026-07-17T08:00:00+00:00", "2026-07-17T10:00:00+02:00"))
            assertEquals(expected, serverInstant(text))
        assertNotNull(serverInstant("2026-07-17 08:00:00.123456+00"))
        assertNull(serverInstant("not a timestamp")); assertNull(serverInstant("2026-07-17 08:00:00"))
    }
}
