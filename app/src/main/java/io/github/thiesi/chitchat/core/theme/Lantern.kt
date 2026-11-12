package io.github.thiesi.chitchat.core.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Complete Lantern token set; OLED changes only the four dark grounds. */
@Immutable
internal data class LanternPalette(
    val ground: Color, val surface: Color, val raised: Color, val line: Color,
    val fieldLine: Color, val text: Color, val textMuted: Color,
    val signature: Color, val onSignature: Color, val signatureText: Color,
    val outgoing: Color, val onOutgoing: Color, val presence: Color, val presenceWash: Color,
    val steward: Color, val onSteward: Color, val error: Color, val onError: Color,
    val unsureLine: Color, val fallbackOnPrimary: Color, val scrim: Color,
    val inverseSurface: Color, val inverseOnSurface: Color, val inverseAction: Color, val identityHues: List<Color>,
) {
    val boundary get() = fieldLine
    val muted get() = textMuted
    val actionText get() = signatureText
    /** Warning-level glyphs and notices (status ladder); an alias, not a separate token. */
    val warning get() = unsureLine
}

internal typealias ChitChatColors = LanternPalette
internal val RoomTileShape = RoundedCornerShape(33)
internal val PersonShape = CircleShape
internal val BubbleShape = RoundedCornerShape(20.dp)

/** Literal presentation colors and identity shapes live only in this package. */
internal object Lantern {
    val ink = Color(0xFF14111C)
    private val paper = Color(0xFFFBF7F1)
    private val white = Color.White
    private val signature = Color(0xFFF06A3B)
    private val honey = Color(0xFFFFB547)
    private val hues = listOf(0xFFF7A072,0xFFF5C451,0xFFA8D672,0xFF7FD1C1,
        0xFF8DB8F5,0xFFB7A3F5,0xFFF29BC4,0xFFD9B08C).map { Color(it) }
    val light = LanternPalette(
        ground=paper, surface=white, raised=Color(0xFFF3ECE2), line=Color(0xFFE6DCCF),
        fieldLine=Color(0xFF8C8299), text=ink, textMuted=Color(0xFF5E566B),
        signature=signature, onSignature=ink, signatureText=Color(0xFFB5401A),
        outgoing=Color(0xFFFFD9C4), onOutgoing=ink, presence=honey, presenceWash=Color(0xFFFFF0D2),
        steward=Color(0xFF3D52C4), onSteward=white, error=Color(0xFFC4314B), onError=white,
        unsureLine=Color(0xFF8F5B00), fallbackOnPrimary=white, scrim=ink,
        inverseSurface=ink, inverseOnSurface=paper, inverseAction=signature, identityHues=hues,
    )
    val dark = LanternPalette(
        ground=ink, surface=Color(0xFF1C1826), raised=Color(0xFF272233), line=Color(0xFF3A3347),
        fieldLine=Color(0xFF7D7490), text=Color(0xFFF2EDF7), textMuted=Color(0xFFA79FB4),
        signature=signature, onSignature=ink, signatureText=signature,
        outgoing=Color(0xFF8A3A1E), onOutgoing=Color(0xFFF2EDF7), presence=honey, presenceWash=Color(0xFF4A3A1C),
        steward=Color(0xFFA9B6FF), onSteward=ink, error=Color(0xFFFF8A9B), onError=ink,
        unsureLine=honey, fallbackOnPrimary=ink, scrim=Color.Black,
        inverseSurface=paper, inverseOnSurface=ink, inverseAction=Color(0xFFB5401A), identityHues=hues,
    )
    val amoledBlack = dark.copy(ground=Color.Black, surface=Color(0xFF110E17),
        raised=Color(0xFF1F1B29), line=Color(0xFF2E2938))
    val person = PersonShape
    val room = RoomTileShape
    val pill = CircleShape
    /** Composer field corners: min(height / 2, 24dp), so a tall field never turns into a circle (P-216). */
    val field = RoundedCornerShape(24.dp) // Compose clamps each corner to half the shorter side.
    val target = 48.dp
    val badge = 24.dp
    val gap = 8.dp
    val inset = 12.dp
    val quietEdge = 1.dp
    val selectedEdge = 2.dp
    fun identity(id: Long): Color = hues[identityHueIndex(id)]
}

internal fun identityHueIndex(id: Long): Int = Math.floorMod(id xor (id ushr 32),8L).toInt()
internal fun identityHue(id: Long): Color = Lantern.identity(id)
internal fun lanternPalette(dark: Boolean,pureBlack: Boolean): LanternPalette =
    if (!dark) Lantern.light else if (pureBlack) Lantern.amoledBlack else Lantern.dark
