package io.github.thiesi.chitchat.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import io.github.thiesi.chitchat.R
import androidx.compose.ui.unit.em
import org.junit.Assert.*
import org.junit.Test

class LanternFoundationTest {
    private fun contrast(a: Color,b: Color): Float = (maxOf(a.luminance(),b.luminance())+0.05f)/(minOf(a.luminance(),b.luminance())+0.05f)
    private fun pair(name: String,foreground: Color,background: Color,floor: Float=4.5f) {
        assertTrue("$name: ${contrast(foreground,background)} < $floor",contrast(foreground,background)>=floor)
    }

    @Test fun everyMappedTextAndComponentPairMeetsItsActualContrastFloor() {
        for(p in listOf(Lantern.light,Lantern.dark,Lantern.amoledBlack)) {
            val c=lanternColorScheme(p)
            for(background in listOf(p.ground,p.surface,p.raised)) {
                pair("text",p.text,background);pair("muted",p.textMuted,background)
                pair("action",p.signatureText,background);pair("steward",p.steward,background)
                pair("field/control",p.fieldLine,background,3f)
            }
            pair("primary fallback",c.onPrimary,c.primary);pair("primary container",c.onPrimaryContainer,c.primaryContainer)
            pair("secondary",c.onSecondary,c.secondary);pair("secondary container",c.onSecondaryContainer,c.secondaryContainer)
            pair("tertiary",c.onTertiary,c.tertiary);pair("tertiary container",c.onTertiaryContainer,c.tertiaryContainer)
            pair("error",c.onError,c.error);pair("error container",c.onErrorContainer,c.errorContainer)
            pair("inverse",c.inverseOnSurface,c.inverseSurface);pair("inverse action",c.inversePrimary,c.inverseSurface)
            pair("filled primary/count/Send",p.onSignature,p.signature)
            pair("outgoing",p.onOutgoing,p.outgoing);pair("notice boundary",p.unsureLine,p.outgoing,3f)
            pair("notice ground boundary",p.unsureLine,p.ground,3f)
            // Details attention markers and the more glyph are non-text graphics (A-02).
            pair("attention dot",p.signatureText,p.ground,3f);pair("uncertain marker",p.warning,p.ground,3f)
            pair("failed marker",p.error,p.ground,3f);pair("more glyph",p.textMuted,p.ground,3f)
            for(hue in p.identityHues) pair("identity",Lantern.ink,hue)
        }
    }

    @Test fun materialSurfaceAndActionRolesUseTheApprovedTokensWithoutTonalTinting() {
        for(p in listOf(Lantern.light,Lantern.dark,Lantern.amoledBlack)) {
            val c=lanternColorScheme(p)
            assertEquals(p.signatureText,c.primary);assertEquals(p.fallbackOnPrimary,c.onPrimary)
            assertEquals(p.outgoing,c.primaryContainer);assertEquals(p.onOutgoing,c.onPrimaryContainer)
            assertEquals(p.steward,c.secondary);assertEquals(p.onSteward,c.onSecondary)
            assertEquals(p.presence,c.tertiary);assertEquals(p.onSignature,c.onTertiary)
            assertEquals(p.presenceWash,c.tertiaryContainer);assertEquals(p.text,c.onTertiaryContainer)
            assertEquals(p.ground,c.background);assertEquals(p.ground,c.surface)
            for(surface in listOf(c.surfaceContainerLowest,c.surfaceContainerLow,c.surfaceContainer)) assertEquals(p.surface,surface)
            for(surface in listOf(c.surfaceContainerHigh,c.surfaceContainerHighest,c.surfaceVariant)) assertEquals(p.raised,surface)
            assertEquals(p.text,c.onSurface);assertEquals(p.textMuted,c.onSurfaceVariant)
            assertEquals(p.fieldLine,c.outline);assertEquals(p.line,c.outlineVariant)
            assertEquals(p.error,c.error);assertEquals(p.onError,c.onError);assertEquals(Color.Transparent,c.surfaceTint)
        }
    }

    @Test fun pureBlackChangesExactlyTheFourApprovedDarkGrounds() {
        val p=Lantern.amoledBlack
        assertEquals(Color.Black,p.ground)
        assertEquals(Lantern.dark,p.copy(ground=Lantern.dark.ground,surface=Lantern.dark.surface,
            raised=Lantern.dark.raised,line=Lantern.dark.line))
        assertSame(Lantern.light,lanternPalette(false,true));assertSame(Lantern.dark,lanternPalette(true,false))
        assertSame(p,lanternPalette(true,true))
    }

    @Test fun immutableIdHuesStayStableAcrossSignedLongsAndUseAllEightColors() {
        assertEquals(8,(1L..128L).map(::identityHue).toSet().size)
        for(id in listOf(Long.MIN_VALUE,-1L,0L,1L,7L,Long.MAX_VALUE)) {
            assertEquals(identityHue(id),Lantern.identity(id))
            assertTrue(identityHueIndex(id) in 0..7)
        }
        assertEquals(Color(0xFFF5C451),identityHue(1))
        assertEquals(Color(0xFFF7A072),identityHue(8))
        assertEquals(Color(0xFFF7A072),identityHue(Long.MAX_VALUE))
    }

    @Test fun displayFaceNeverLeaksIntoMessageBodyOrOtherSystemRoles() {
        val before=Typography();val after=lanternTypography()
        val fonts=(after.titleLarge.fontFamily as FontListFontFamily).fonts
        assertEquals(listOf(FontWeight.Bold,FontWeight.ExtraBold),fonts.map { it.weight })
        assertEquals(listOf(R.font.bricolage_grotesque_bold,R.font.bricolage_grotesque_extra_bold),
            fonts.map { (it as ResourceFont).resId })
        assertEquals(before.bodyLarge,after.bodyLarge);assertEquals(before.bodyMedium,after.bodyMedium)
        assertEquals(before.bodySmall,after.bodySmall);assertEquals(before.titleMedium,after.titleMedium)
        assertEquals(before.titleSmall,after.titleSmall);assertEquals(before.labelLarge,after.labelLarge)
        assertEquals(before.labelMedium,after.labelMedium);assertEquals(before.labelSmall,after.labelSmall)
        for(style in listOf(after.displayLarge,after.displayMedium,after.displaySmall)) {
            assertEquals(FontWeight.ExtraBold,style.fontWeight);assertEquals((-0.01).em,style.letterSpacing)
        }
        for(style in listOf(after.headlineLarge,after.headlineMedium,after.headlineSmall,after.titleLarge)) {
            assertEquals(FontWeight.Bold,style.fontWeight);assertEquals((-0.01).em,style.letterSpacing)
        }
        assertEquals(before.displayLarge.fontSize,after.displayLarge.fontSize)
        assertEquals(before.headlineSmall.fontSize,after.headlineSmall.fontSize)
    }
}
