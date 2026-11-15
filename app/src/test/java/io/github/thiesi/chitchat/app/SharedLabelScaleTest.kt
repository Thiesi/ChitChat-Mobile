package io.github.thiesi.chitchat.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** J1 (PR251): tab labels share one size; full while the widest fits, else the largest that fits, floored at 18sp. */
class SharedLabelScaleTest {
    @Test fun wideEnoughKeepsTheFullSize() = assertEquals(1f, sharedLabelScale(widestAtFull = 79f, available = 80f, floorScale = 0.75f), 0f)

    @Test fun narrowerSharesTheLargestSizeThatFits() =
        assertEquals(70f / 87f, sharedLabelScale(widestAtFull = 87f, available = 70f, floorScale = 0.75f), 1e-6f)

    @Test fun belowTheFloorStopsAtTheFloorAndWraps() =
        assertEquals(0.75f, sharedLabelScale(widestAtFull = 87f, available = 40f, floorScale = 0.75f), 0f)

    @Test fun atOrBelowOneAndAHalfTimesNothingShrinks() =
        assertEquals(1f, sharedLabelScale(widestAtFull = 87f, available = 40f, floorScale = 1.5f), 0f)
}
