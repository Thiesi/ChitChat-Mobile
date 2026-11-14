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
    private fun palette(p: LanternPalette) {
        for (background in listOf(p.ground,p.raised)) {
            for (foreground in listOf(p.text,p.muted,p.actionText))
                assertTrue("Readable text against its actual ground",contrast(foreground,background)>=4.5f)
            assertTrue("Visible choice boundary",contrast(p.boundary,background)>=3f)
        }
    }
    @Test fun lightCompletionTextAndControlBoundariesMeetContrastFloors()=palette(Lantern.light)
    @Test fun darkCompletionTextAndControlBoundariesMeetContrastFloors()=palette(Lantern.dark)
    /** Search match highlighting (#283): the row's text on presenceWash keeps the 4.5:1 floor in every palette. */
    @Test fun searchMatchTextOnPresenceWashMeetsTheTextFloor() {
        for (p in listOf(Lantern.light, Lantern.dark, Lantern.amoledBlack))
            assertTrue("Text on presenceWash", contrast(p.text, p.presenceWash) >= 4.5f)
    }
    @Test fun everyIdentityHueRetainsReadableInkForItsDecorativeInitial() {
        for (id in 1L..32L) assertTrue(contrast(Lantern.ink,Lantern.identity(id))>=4.5f)
    }
}
