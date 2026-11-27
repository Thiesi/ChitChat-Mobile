package io.github.thiesi.chitchat.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A3 (TL): the same-frame restore runs once per layout key and hands out-of-range targets to the fallback. */
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
        assertEquals(1318f, sameFrameDelta(target = 1318, value = 0, maxValue = 4019))
        assertEquals(-200f, sameFrameDelta(target = 100, value = 300, maxValue = 4019))
        assertEquals(4019f, sameFrameDelta(target = 4019, value = 0, maxValue = 4019))
    }

    @Test fun nothingToMoveIsNoDelta() {
        assertNull(sameFrameDelta(target = 1318, value = 1318, maxValue = 4019))
    }

    @Test fun theSameFrameCancelRunsOnlyAfterAnAppliedDelta() {
        val calls = mutableListOf<String>()
        applySameFrame(1318f, apply = { calls += "apply $it" }) { calls += "cancel" }
        assertEquals(listOf("apply 1318.0", "cancel"), calls)
        calls.clear()
        applySameFrame(null, apply = { calls += "apply $it" }) { calls += "cancel" }
        assertEquals("no delta: no move and no cancel", emptyList<String>(), calls)
    }

    @Test fun theFallbackScrollsEvenAtTheTargetSoItTakesTheScrollMutex() = kotlinx.coroutines.runBlocking {
        val scrolled = mutableListOf<Int>()
        fallbackScroll(target = 1432, maxValue = 2052) { scrolled += it }
        fallbackScroll(target = 2100, maxValue = 2052) { scrolled += it }
        fallbackScroll(target = -5, maxValue = 2052) { scrolled += it }
        assertEquals("always one scroll, clamped as A", listOf(1432, 2052, 0), scrolled)
    }

    @Test fun theBaselineIsFrozenPerKeyAtTheFirstRead() {
        val baseline = FrozenPerKey<String?>()
        val portrait = 357 to false
        var records = "previous layout: caret at 106"
        assertEquals("previous layout: caret at 106", baseline.get(portrait) { records })
        // An old-key record lands between the same-frame read and the fallback's read.
        records = "stray record after the same-frame scroll"
        assertEquals("every reader for the key sees the first read", "previous layout: caret at 106", baseline.get(portrait) { records })
        assertEquals("a new key reads afresh", "stray record after the same-frame scroll", baseline.get(592 to true) { records })
        records = "later"
        assertEquals("a change back to an earlier key is a new change", "later", baseline.get(portrait) { records })
    }

    @Test fun aRecordFromAnOldKeyLambdaIsAttributedToTheLayoutPass() {
        val now = 357 to false
        assertEquals("the layout pass's key and mode win over the lambda's", now to false,
            recordAttribution(layoutKey = now, layoutPinned = false, key = 592 to true, pinned = true))
        assertEquals("before the first pass the caller's are used", (592 to true) to true,
            recordAttribution(layoutKey = null, layoutPinned = false, key = 592 to true, pinned = true))
    }

    @Test fun aFrameTallerThanTheDialogsViewportIsRefused() {
        assertFalse("#376's stray: 3540 px against a 357 px viewport", acceptsFrame(frameHeight = 3540, viewport = 357))
        assertTrue("the outer scroller's frame is the viewport", acceptsFrame(frameHeight = 357, viewport = 357))
        assertTrue("within a pixel", acceptsFrame(frameHeight = 358, viewport = 357))
        assertTrue("the pinned body sits inside it", acceptsFrame(frameHeight = 189, viewport = 357))
        assertTrue("an unmeasured viewport accepts", acceptsFrame(frameHeight = 3540, viewport = 0))
    }

    @Test fun aNullFirstReadIsFrozenToo() {
        val baseline = FrozenPerKey<String?>()
        assertNull(baseline.get(1) { null })
        assertNull("a record arriving later doesn't become this key's baseline", baseline.get(1) { "late" })
    }

    @Test fun aTargetOutsideTheRangeIsLeftToTheFallbacksClamp() {
        assertNull(sameFrameDelta(target = 4020, value = 0, maxValue = 4019))
        assertNull(sameFrameDelta(target = -1, value = 10, maxValue = 4019))
    }
}
