package io.github.thiesi.chitchat.core.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Approved Lantern tokens. Adoption of existing screens remains separately sequenced. */
internal object Lantern {
    val ink = Color(0xFF14111C)
    val person = CircleShape
    val room = RoundedCornerShape(33)
    val pill = CircleShape
    val target = 48.dp
    val badge = 24.dp
    val gap = 8.dp
    val inset = 12.dp
    val quietEdge = 1.dp
    val selectedEdge = 2.dp
    val light = Palette(Color(0xFFFBF7F1), Color(0xFFF3ECE2), ink, Color(0xFF5E566B),
        Color(0xFF8C8299), Color(0xFFB5401A))
    val dark = Palette(ink, Color(0xFF272233), Color(0xFFF2EDF7), Color(0xFFA79FB4),
        Color(0xFF7D7490), Color(0xFFF06A3B))
    private val hues = listOf(0xFFF7A072,0xFFF5C451,0xFFA8D672,0xFF7FD1C1,
        0xFF8DB8F5,0xFFB7A3F5,0xFFF29BC4,0xFFD9B08C).map { Color(it) }
    fun identity(id: Long): Color = hues[Math.floorMod(id xor (id ushr 32), hues.size.toLong()).toInt()]
    class Palette(val ground: Color, val raised: Color, val text: Color, val muted: Color,
        val boundary: Color, val actionText: Color)
}
