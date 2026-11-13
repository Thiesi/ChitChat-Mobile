package io.github.thiesi.chitchat.feature.status

import org.junit.Assert.*
import org.junit.Test

class ScreenStatusHolderTest {
    @Test fun theLatestScreenSpeaksAndALeavingScreenClearsOnlyItsOwnStatus() {
        val holder = ScreenStatusHolder()
        val rooms = Any(); val thread = Any()
        assertEquals(ScreenStatus(), holder.current)
        holder.report(rooms, ScreenStatus(syncing = true))
        assertTrue(holder.current.syncing)
        holder.report(thread, ScreenStatus(stale = true))
        assertEquals(ScreenStatus(stale = true), holder.current)
        holder.clear(rooms)
        assertEquals("An old screen's disposal can't clear its successor", ScreenStatus(stale = true), holder.current)
        holder.clear(thread)
        assertEquals(ScreenStatus(), holder.current)
    }
}
