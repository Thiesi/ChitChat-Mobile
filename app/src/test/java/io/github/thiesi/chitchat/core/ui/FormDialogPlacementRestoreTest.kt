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

    @Test fun anotherFieldOrContentThatCantScroll() {
        assertEquals("another focused field", false, bodyPlaceValid(place, Any(), pinned = true, maxValue = 3252, viewportHeight = 288))
        assertEquals("no scroll range: nothing can move, any target clamps to 0", true,
            bodyPlaceValid(place, field, pinned = true, maxValue = 0, viewportHeight = 288))
    }
}
