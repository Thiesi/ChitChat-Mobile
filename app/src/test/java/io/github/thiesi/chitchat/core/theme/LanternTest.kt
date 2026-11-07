package io.github.thiesi.chitchat.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class LanternTest {
    private fun contrast(a: Color,b: Color): Float {
        val x=a.luminance();val y=b.luminance()
        return (maxOf(x,y)+0.05f)/(minOf(x,y)+0.05f)
    }
    private fun palette(p: Lantern.Palette) {
        for (background in listOf(p.ground,p.raised)) {
            for (foreground in listOf(p.text,p.muted,p.actionText))
                assertTrue("Readable text against its actual ground",contrast(foreground,background)>=4.5f)
            assertTrue("Visible choice boundary",contrast(p.boundary,background)>=3f)
        }
    }
    @Test fun lightCompletionTextAndControlBoundariesMeetContrastFloors()=palette(Lantern.light)
    @Test fun darkCompletionTextAndControlBoundariesMeetContrastFloors()=palette(Lantern.dark)
    @Test fun everyIdentityHueRetainsReadableInkForItsDecorativeInitial() {
        for (id in 1L..32L) assertTrue(contrast(Lantern.ink,Lantern.identity(id))>=4.5f)
    }
}
