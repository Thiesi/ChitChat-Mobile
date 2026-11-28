package io.github.thiesi.chitchat.core.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #378: the placement-pass restore uses the field's place in the body column's own content only while it is this pass's
 * place: the same field, and the content not re-measured. Numbers from diag run 46031148507's text-first portrait case.
 */
class FormDialogPlacementRestoreTest {
    private val field = Any()
    // The body content 3540 px tall, its top 142 px into the outer content, 160 px of outer content below it.
    private val place = BodyPlace(field, Rect(0f, 1324f, 272f, 3180f), Rect(16f, 3126f, 18f, 3164f), null,
        bodyTop = 142f, bodyHeight = 3540, belowBody = 160f)

    @Test fun pinnedTheInnerScrollersContentIsTheBody() {
        // Pinned: the inner scroller's content is the body, maxValue + viewport its height (3252 + 288).
        assertEquals(true, bodyPlaceValid(place, field, pinned = true, maxValue = 3252, viewportHeight = 288))
        assertEquals("the body re-measured (text re-wrapped)", false,
            bodyPlaceValid(place, field, pinned = true, maxValue = 3300, viewportHeight = 288))
    }

    @Test fun oneColumnTheOuterContentIsTheBodysTopTheBodyAndWhatsBelow() {
        // One column: 142 + 3540 + 160 = 3842 = maxValue 3485 + viewport 357, across the swap from pinned.
        assertEquals(true, bodyPlaceValid(place, field, pinned = false, maxValue = 3485, viewportHeight = 357))
        assertEquals("within a px", true, bodyPlaceValid(place, field, pinned = false, maxValue = 3486, viewportHeight = 357))
        assertEquals("the outer content re-measured", false,
            bodyPlaceValid(place, field, pinned = false, maxValue = 3500, viewportHeight = 357))
    }

    @Test fun aHeldPassConsumesNothingAndTheReleasePassClaimsAndRestores() {
        // Finding 3 (API36 notice re-tap): the settle pass is held (the notice holds the view); it must not use up the
        // change, so the pass where the hold releases still claims it and restores in that pass.
        val sameFrame = FormDialogSameFrameRestore()
        val key = Any()
        assertEquals("held: nothing claimed", false, claimUnlessHeld(true) { sameFrame.claim(key) })
        assertEquals("released: the change is still there to claim", true, claimUnlessHeld(false) { sameFrame.claim(key) })
        assertEquals("claimed once", false, claimUnlessHeld(false) { sameFrame.claim(key) })
    }

    @Test fun theHoldIsTheComposedNoticeAndTheLiveState() {
        // #378 F1 (TL): the composed notice starts the hold; the live state ends it at once.
        var liveReads = 0
        assertEquals(true, holdNow(true) { liveReads++; true })
        assertEquals("released live, the recomposed parameter still true", false, holdNow(true) { liveReads++; false })
        assertEquals("no composed hold: no hold", false, holdNow(false) { liveReads++; true })
        assertEquals("no composed hold: the live state isn't read (nor observed)", 2, liveReads)
    }

    @Test fun theSwapAfterTheReleaseClaimsAndRestoresInItsOwnPass() {
        // #378 F1: diag run 46066588478's order on API36's notice re-tap: the VM released at fieldFocused, the swap to one
        // column came before the recomposed hold flipped (308 ms). The swap's placement reads the live hold, released:
        // it claims and restores in that pass, while the composed parameter is still true.
        val sameFrame = FormDialogSameFrameRestore()
        val pinnedKey = Any(); val columnKey = Any()
        var vmHeld = true
        val composed = true
        assertEquals("before the tap: held", false, claimUnlessHeld(holdNow(composed) { vmHeld }) { sameFrame.claim(pinnedKey) })
        vmHeld = false // fieldFocused, synchronously in the tap's focus dispatch
        assertEquals("the swap's placement claims", true, claimUnlessHeld(holdNow(composed) { vmHeld }) { sameFrame.claim(columnKey) })
        // The restore of that pass: the one-column outer at 0 goes to the target in this pass (logged 0 -> 1662).
        assertEquals(1662f, sameFrameDelta(1662, 0, 2359))
    }

    @Test fun aDeferredStepForASupersededLayoutDoesNothing() {
        // API35 reversed (diag run 46066588478): the step launched at the pinned key 440 resumed after the layout pass had
        // moved on to one column (357): not current, dropped.
        val pinnedKey = Any(); val columnKey = Any()
        assertEquals(false, deferredStepCurrent(columnKey, false, pinnedKey, true))
        assertEquals("the same key, the other mode", false, deferredStepCurrent(pinnedKey, false, pinnedKey, true))
        assertEquals("before the first layout pass", false, deferredStepCurrent(null, false, pinnedKey, true))
        assertEquals(true, deferredStepCurrent(pinnedKey, true, pinnedKey, true))
    }

    @Test fun anotherFieldOrContentThatCantScroll() {
        assertEquals("another focused field", false, bodyPlaceValid(place, Any(), pinned = true, maxValue = 3252, viewportHeight = 288))
        assertEquals("no scroll range: nothing can move, any target clamps to 0", true,
            bodyPlaceValid(place, field, pinned = true, maxValue = 0, viewportHeight = 288))
    }
}
