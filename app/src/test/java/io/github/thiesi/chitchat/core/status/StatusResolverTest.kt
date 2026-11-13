package io.github.thiesi.chitchat.core.status

import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.RealtimePhase.*
import org.junit.Assert.*
import org.junit.Test

class StatusResolverTest {
    private fun resolve(phase: RealtimePhase, block: StatusInputs.() -> StatusInputs = { this }) = resolveStatus(StatusInputs(phase).block())

    @Test fun liveAloneShowsNothing() {
        assertNull(resolve(LIVE))
    }

    @Test fun everyRowInPrecedenceOrder() {
        val all: StatusInputs.() -> StatusInputs = {
            copy(storageWarning = true, refreshRequired = true, nowMillis = 0, retryAtMillis = 10_000, retryAfterKnown = true,
                screen = StatusNotice.Screen("rooms_uncertain"), syncing = true, stale = true)
        }
        assertEquals(StatusNotice.StorageWarning, resolve(OFFLINE, all))
        assertEquals(StatusNotice.Offline, resolve(OFFLINE) { all().copy(storageWarning = false) })
        assertEquals(StatusNotice.SecureConnectionFailed, resolve(ERROR) { all().copy(storageWarning = false, errorKind = FailureKind.TLS) })
        assertEquals(StatusNotice.Incompatible, resolve(ERROR) { all().copy(storageWarning = false, errorKind = FailureKind.INCOMPATIBLE) })
        assertEquals(StatusNotice.Incompatible, resolve(ERROR) { all().copy(storageWarning = false, errorKind = FailureKind.REDIRECT) })
        assertEquals(StatusNotice.LiveUpdatesStopped, resolve(ERROR) { all().copy(storageWarning = false, errorKind = FailureKind.NETWORK) })
        assertEquals(StatusNotice.RefreshRequired, resolve(LIVE) { all().copy(storageWarning = false) })
        assertEquals(StatusNotice.RateLimited(Wait.Seconds(10)), resolve(LIVE) { all().copy(storageWarning = false, refreshRequired = false) })
        assertEquals(StatusNotice.Screen("rooms_uncertain"), resolve(LIVE) { all().copy(storageWarning = false, refreshRequired = false, retryAtMillis = 0) })
        assertEquals(StatusNotice.Syncing, resolve(LIVE) { copy(syncing = true, stale = true) })
        assertEquals(StatusNotice.Stale, resolve(LIVE) { copy(stale = true) })
    }

    @Test fun reconnectingShowsOnlyAfterFiveSeconds() {
        val since: StatusInputs.(Long) -> StatusInputs = { now -> copy(reconnectingSinceMillis = 1_000, monotonicNowMillis = now) }
        for (phase in listOf(CONNECTING, RECONNECTING)) {
            assertNull(resolve(phase) { since(5_999) })
            assertEquals(StatusNotice.Unreachable, resolve(phase) { since(6_000) })
        }
        assertNull("No start time, nothing to count", resolve(RECONNECTING))
        assertEquals("Rate limits outrank reconnecting", StatusNotice.RateLimited(null),
            resolve(RECONNECTING) { since(9_000).copy(retryAtMillis = 30_000) })
    }

    @Test fun backOnlineOnlyOnLiveAfterADisconnection() {
        assertEquals(StatusNotice.BackOnline, resolve(LIVE) { copy(recovered = true) })
        assertNotEquals(StatusNotice.BackOnline, resolve(RECONNECTING) { copy(recovered = true) })
        for (notice in listOf(StatusNotice.Offline, StatusNotice.SecureConnectionFailed, StatusNotice.Incompatible,
                StatusNotice.LiveUpdatesStopped, StatusNotice.Unreachable)) assertTrue(notice.isDisconnection())
        for (notice in listOf(null, StatusNotice.RefreshRequired, StatusNotice.Stale, StatusNotice.RateLimited(null)))
            assertFalse(notice.isDisconnection())
    }

    @Test fun stoppedIsStaleAndTheScreenCanStillSpeak() {
        assertEquals(StatusNotice.Stale, resolve(STOPPED))
        assertEquals(StatusNotice.Screen("x"), resolve(STOPPED) { copy(screen = StatusNotice.Screen("x")) })
    }

    @Test fun anUnknownDelayNeverShowsANumber() {
        assertEquals(StatusNotice.RateLimited(null), resolve(LIVE) { copy(nowMillis = 0, retryAtMillis = 30_000, retryAfterKnown = false) })
        assertNull("Gone once the time is up", resolve(LIVE) { copy(nowMillis = 30_000, retryAtMillis = 30_000, retryAfterKnown = true) })
    }

    @Test fun waitsAlwaysRoundUp() {
        assertEquals(Wait.Seconds(1), Wait.of(1))
        assertEquals(Wait.Seconds(12), Wait.of(11_001))
        assertEquals(Wait.Seconds(59), Wait.of(59_000))
        assertEquals(Wait.AboutAMinute, Wait.of(59_001))
        assertEquals(Wait.AboutAMinute, Wait.of(119_000))
        assertEquals(Wait.AboutMinutes(2), Wait.of(119_001))
        assertEquals(Wait.AboutMinutes(3), Wait.of(120_001))
        assertEquals(Wait.AboutMinutes(60), Wait.of(3_599_000))
        assertEquals(Wait.AboutHours(1), Wait.of(3_600_000))
        assertEquals(Wait.AboutHours(2), Wait.of(3_600_001))
    }
}
