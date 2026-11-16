package io.github.thiesi.chitchat.core.settings

import org.junit.Assert.*
import org.junit.Test

/** The local guidelines acceptance store's minimal entries (P-170): server origin, account ID and version only. */
class GuidelinesAcceptanceTest {
    private val origin = "https://chat.example.test/"
    private val other = "https://other.example.test/"

    @Test fun anEntryHoldsOnlyTheOriginAccountAndVersion() {
        val entry = encodeAcceptance(GuidelinesAcceptance(origin, 7, 1))
        assertEquals("$origin 7 1", entry)
        assertEquals(GuidelinesAcceptance(origin, 7, 1), decodeAcceptance(entry!!))
    }

    @Test fun malformedEntriesAreDroppedNeverGuessed() {
        for (bad in listOf("", "http://chat.example.test/ 7 1", "$origin 0 1", "$origin 7 0", "$origin seven 1", "$origin 7 1 extra", "$origin 7"))
            assertNull(bad, decodeAcceptance(bad))
        assertNull(encodeAcceptance(GuidelinesAcceptance("not a url", 7, 1)))
    }

    @Test fun coverageIsPerServerAndAccountAndAtLeastTheCurrentVersion() {
        val accepted = setOf(GuidelinesAcceptance(origin, 7, APP_GUIDELINES_VERSION))
        assertTrue(accepted.covers(origin, 7))
        assertFalse("Another account on the same server", accepted.covers(origin, 8))
        assertFalse("The same account ID on another server", accepted.covers(other, 7))
        assertFalse("A newer guidelines version asks again", accepted.covers(origin, 7, APP_GUIDELINES_VERSION + 1))
    }

    @Test fun recordingReplacesOnlyThatServerAndAccountsOlderEntry() {
        val start = setOf("$origin 7 1", "$origin 8 1", "$other 7 1")
        val next = withAcceptance(start, GuidelinesAcceptance(origin, 7, 2))
        assertEquals(setOf("$origin 7 2", "$origin 8 1", "$other 7 1"), next)
    }

    /** Sign-out keeps the records; forgetting the server removes every record for it, and only for it. */
    @Test fun forgettingAServerRemovesOnlyItsRecords() {
        val start = setOf("$origin 7 1", "$origin 8 1", "$other 7 1", "garbage")
        assertEquals(setOf("$other 7 1"), withoutServer(start, origin))
    }
}
