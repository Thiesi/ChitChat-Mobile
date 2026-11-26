package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A3 (TL): the same-frame restore runs once per layout key and hands busy or out-of-range cases to the fallback. */
class FormDialogSameFrameRestoreTest {
    @Test fun eachKeyIsClaimedOnceAndLaterCallbacksForItDoNothing() {
        val restore = FormDialogSameFrameRestore()
        val before = 357 to true
        val after = 280 to false
        assertTrue(restore.claim(before))
        assertTrue(restore.claim(after))
        assertFalse("a scroll fires the callback again for the same key", restore.claim(after))
        assertFalse(restore.claim(280 to false))
        assertTrue("a change back to an earlier layout is a new change", restore.claim(before))
    }

    @Test fun aTargetInsideTheRangeMovesByTheDelta() {
        assertEquals(1318f, sameFrameDelta(target = 1318, value = 0, maxValue = 4019, inProgress = false))
        assertEquals(-200f, sameFrameDelta(target = 100, value = 300, maxValue = 4019, inProgress = false))
        assertEquals(4019f, sameFrameDelta(target = 4019, value = 0, maxValue = 4019, inProgress = false))
    }

    @Test fun nothingToMoveIsNoDelta() {
        assertNull(sameFrameDelta(target = 1318, value = 1318, maxValue = 4019, inProgress = false))
    }

    @Test fun aScrollInProgressIsLeftToTheFallback() {
        assertNull(sameFrameDelta(target = 1318, value = 0, maxValue = 4019, inProgress = true))
    }

    @Test fun aTargetOutsideTheRangeIsLeftToTheFallbacksClamp() {
        assertNull(sameFrameDelta(target = 4020, value = 0, maxValue = 4019, inProgress = false))
        assertNull(sameFrameDelta(target = -1, value = 10, maxValue = 4019, inProgress = false))
    }
}
