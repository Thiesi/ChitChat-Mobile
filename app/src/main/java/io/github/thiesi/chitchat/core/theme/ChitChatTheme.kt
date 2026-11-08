package io.github.thiesi.chitchat.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.thiesi.chitchat.R

private val LocalChitChatColors = staticCompositionLocalOf<ChitChatColors> { Lantern.light }

internal object ChitChatTheme {
    val colors: ChitChatColors @Composable get() = LocalChitChatColors.current
}

/** Pure presentation: the caller supplies effective appearance; no dynamic color. */
@Composable
internal fun ChitChatTheme(dark: Boolean,pureBlack: Boolean=false,content: @Composable () -> Unit) {
    val palette=lanternPalette(dark,pureBlack)
    val scheme=remember(palette) { lanternColorScheme(palette) }
    val typography=remember { lanternTypography() }
    CompositionLocalProvider(LocalChitChatColors provides palette) {
        MaterialTheme(colorScheme=scheme,typography=typography,shapes=lanternShapes,content=content)
    }
}

internal fun lanternColorScheme(p: LanternPalette): ColorScheme = lightColorScheme(
    primary=p.signatureText,onPrimary=p.fallbackOnPrimary,
    primaryContainer=p.outgoing,onPrimaryContainer=p.onOutgoing,
    secondary=p.steward,onSecondary=p.onSteward,secondaryContainer=p.raised,onSecondaryContainer=p.text,
    tertiary=p.presence,onTertiary=p.onSignature,tertiaryContainer=p.presenceWash,onTertiaryContainer=p.text,
    background=p.ground,onBackground=p.text,surface=p.ground,onSurface=p.text,
    surfaceVariant=p.raised,onSurfaceVariant=p.textMuted,
    surfaceContainerLowest=p.surface,surfaceContainerLow=p.surface,surfaceContainer=p.surface,
    surfaceContainerHigh=p.raised,surfaceContainerHighest=p.raised,surfaceBright=p.surface,surfaceDim=p.ground,
    // Tonal elevation must not turn an exact Lantern surface into a tinted one.
    surfaceTint=Color.Transparent,outline=p.fieldLine,outlineVariant=p.line,
    error=p.error,onError=p.onError,errorContainer=p.raised,onErrorContainer=p.error,scrim=p.scrim,
    inverseSurface=p.inverseSurface,inverseOnSurface=p.inverseOnSurface,inversePrimary=p.inverseAction,
)

private val lanternShapes=Shapes(
    extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),
    large=RoundedCornerShape(22.dp),extraLarge=RoundedCornerShape(28.dp),
)

internal fun lanternTypography(): Typography {
    val display=FontFamily(
        Font(R.font.bricolage_grotesque_bold,weight=FontWeight.Bold),
        Font(R.font.bricolage_grotesque_extra_bold,weight=FontWeight.ExtraBold))
    val defaults=Typography()
    fun TextStyle.heading(weight: FontWeight)=copy(fontFamily=display,fontWeight=weight,letterSpacing=(-0.01).em)
    return defaults.copy(displayLarge=defaults.displayLarge.heading(FontWeight.ExtraBold),
        displayMedium=defaults.displayMedium.heading(FontWeight.ExtraBold),displaySmall=defaults.displaySmall.heading(FontWeight.ExtraBold),
        headlineLarge=defaults.headlineLarge.heading(FontWeight.Bold),headlineMedium=defaults.headlineMedium.heading(FontWeight.Bold),
        headlineSmall=defaults.headlineSmall.heading(FontWeight.Bold),titleLarge=defaults.titleLarge.heading(FontWeight.Bold))
}
