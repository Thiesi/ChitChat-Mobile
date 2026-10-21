package io.github.thiesi.chitchat.testing

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class NativeTapTest {
    @Test fun clippedCenterIsTranslatedFromTheActualWindowToScreen() {
        assertEquals(Offset(160f, 250f), nativeTapPoint(Rect(50f, 40f, 70f, 60f),
            Offset(10f, 20f), Offset(110f, 220f)))
        assertEquals(Offset(60f, 50f), nativeTapPoint(Rect(50f, 40f, 70f, 60f),
            Offset(10f, 20f), Offset(10f, 20f)))
    }
    @Test fun invalidGeometryOrNonDefaultDisplayIsRejectedBeforeInjection() {
        for (bounds in listOf(Rect.Zero, Rect(1f, 1f, 1f, 4f), Rect(Float.NaN, 0f, 2f, 4f)))
            assertThrows(IllegalArgumentException::class.java) { nativeTapPoint(bounds, Offset.Zero, Offset.Zero) }
        assertThrows(IllegalArgumentException::class.java) {
            nativeTapPoint(Rect(0f, 0f, 2f, 4f), Offset.Zero, Offset(Float.POSITIVE_INFINITY, 0f))
        }
        for (display in listOf(null, -1, 1)) assertThrows(IllegalArgumentException::class.java) {
            nativeTapPoint(Rect(0f, 0f, 2f, 4f), Offset.Zero, Offset.Zero, display)
        }
    }
    @Test fun oneFingerDownAndUpKeepCoordinatesSourceAndRealClockValues() {
        val events = mutableListOf<Event>()
        var clock = 10L
        injectNativeTap(Offset(42f, 84f), { clock++ }) { events += Event(it); true }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events.map { it.action })
        assertTrue(events.all { it.x == 42f && it.y == 84f && it.source == InputDevice.SOURCE_TOUCHSCREEN &&
            it.count == 1 && it.tool == MotionEvent.TOOL_TYPE_FINGER && it.down == 10L })
        assertEquals(listOf(11L, 12L), events.map { it.time })
    }
    @Test fun rejectedDownIsNotRetriedAndCancellationCannotTurnItGreen() {
        val actions = mutableListOf<Int>()
        val failure = assertThrows(IllegalStateException::class.java) {
            injectNativeTap(Offset.Zero, { 1L }) { actions += it.action; it.action != MotionEvent.ACTION_DOWN }
        }
        assertTrue(failure.message!!.contains("action=0"))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), actions)
    }
    @Test fun rejectedUpCancelsTheHeldPointerButNeverRepeatsTheTap() {
        val actions = mutableListOf<Int>()
        assertThrows(IllegalStateException::class.java) {
            injectNativeTap(Offset.Zero, { 1L }) { actions += it.action; it.action != MotionEvent.ACTION_UP }
        }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL), actions)
    }
    @Test fun injectionExceptionSurvivesFailedCancellationWithoutReplacement() {
        val original = IllegalStateException("fixture injection failure")
        var calls = 0
        val caught = assertThrows(IllegalStateException::class.java) {
            injectNativeTap(Offset.Zero, { 1L }) { calls++; if (calls == 1) throw original else false }
        }
        assertSame(original, caught); assertEquals(2, calls); assertEquals(1, caught.suppressed.size)
    }
    @Test fun invalidPointCannotReachTheEventInjector() {
        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            injectNativeTap(Offset(Float.NaN, 0f), { error("Clock must not be reached") }) { calls++; true }
        }
        assertEquals(0, calls)
    }
    private class Event(event: MotionEvent) {
        val action = event.action; val x = event.x; val y = event.y; val source = event.source
        val count = event.pointerCount; val tool = event.getToolType(0)
        val down = event.downTime; val time = event.eventTime
    }
}
