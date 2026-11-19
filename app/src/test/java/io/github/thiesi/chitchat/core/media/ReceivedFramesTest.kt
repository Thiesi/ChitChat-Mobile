package io.github.thiesi.chitchat.core.media

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import io.github.thiesi.chitchat.core.ui.middleElided
import io.github.thiesi.chitchat.core.ui.onScreenIds
import org.junit.Assert.*
import org.junit.Test

/** Received files F3b: frame geometry, the card's middle elision and the viewport rule (the panes are F3a's). */
class ReceivedFramesTest {
    private fun size(width: Int?, height: Int?, content: Float = 288f) = attachmentFrameSize(width, height, content)

    @Test fun aNormalPhotoTakesItsRatioWithinTheWidthCap() {
        val frame = size(4000, 3000)
        assertEquals(280f, frame.widthDp); assertEquals(210f, frame.heightDp); assertFalse(frame.contain)
    }

    @Test fun theContentWidthCapsTheFrame() {
        val frame = size(4000, 3000, content = 200f)
        assertEquals(200f, frame.widthDp); assertEquals(150f, frame.heightDp)
    }

    @Test fun aSmallImageIsNeverUpscaled() {
        val frame = size(160, 120)
        assertEquals(160f, frame.widthDp); assertEquals(120f, frame.heightDp); assertFalse(frame.contain)
    }

    @Test fun aPanoramaKeepsTheWidthCapAndIsContainFittedAtTheMinimumHeight() {
        val frame = size(6000, 1000)
        assertEquals(280f, frame.widthDp); assertEquals(96f, frame.heightDp); assertTrue(frame.contain)
    }

    @Test fun aTallImageStopsAt320AndShrinksItsWidthToKeepTheRatio() {
        val frame = size(1000, 2000)
        assertEquals(160f, frame.widthDp); assertEquals(320f, frame.heightDp); assertFalse(frame.contain)
    }

    @Test fun aVeryTallImageNeverGoesBelow120WideAndIsContainFitted() {
        val frame = size(1000, 6000)
        assertEquals(120f, frame.widthDp); assertEquals(320f, frame.heightDp); assertTrue(frame.contain)
    }

    @Test fun withoutDimensionsItIsTheFixed4By3Frame() {
        for ((w, h) in listOf(null to null, 0 to 300, 400 to -1, 400 to null)) {
            val frame = size(w, h)
            assertEquals(240f, frame.widthDp); assertEquals(180f, frame.heightDp); assertTrue(frame.contain)
        }
        val narrow = size(null, null, content = 200f)
        assertEquals(200f, narrow.widthDp); assertEquals(150f, narrow.heightDp)
    }

    @Test fun aLongNameKeepsItsStartAndExtension() {
        val name = "quarterly-report-for-the-steering-committee-final-version-approved.pdf"
        val shown = middleElided(name) { it.length <= 30 }
        assertTrue(shown, shown.length <= 30 && shown.startsWith("quarterly") && shown.endsWith("ved.pdf") && "…" in shown)
        assertEquals("short.pdf", middleElided("short.pdf") { true })
    }

    private class Item(override val index: Int, override val key: Any, override val offset: Int, override val size: Int) : LazyListItemInfo
    private class Layout(override val visibleItemsInfo: List<LazyListItemInfo>, override val viewportStartOffset: Int,
        override val viewportEndOffset: Int) : LazyListLayoutInfo {
        override val totalItemsCount get() = visibleItemsInfo.size
    }

    @Test fun onlyRowsThatOverlapTheViewportCount() {
        val layout = Layout(listOf(
            Item(0, "message:1", -300, 200), // Laid out beyond the start for focus search: not on screen.
            Item(1, "message:2", -50, 100), // Partly on screen.
            Item(2, "message:3", 200, 100),
            Item(3, "history_end", 500, 10),
            Item(4, "message:4", 640, 100), // Starts exactly at the end: not on screen.
            Item(5, "message:5", 900, 100), // Beyond the end.
        ), viewportStartOffset = 0, viewportEndOffset = 640)
        assertEquals(setOf(2L, 3L), onScreenIds(layout, "message:"))
        assertEquals(emptySet<Long>(), onScreenIds(layout, "direct:"))
    }
}
