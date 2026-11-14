package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Material 3 scroll-edge cue for a scrolling dialog body: a hairline at the bottom edge while more content
 * follows, and at the top edge once it has scrolled. Place it before `verticalScroll(state)` so the lines stay on
 * the viewport edges. Pass the theme's `outline` (Lantern `fieldLine`); no new colour.
 */
internal fun Modifier.scrollEdgeDividers(state: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    if (state.canScrollBackward) drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
    if (state.canScrollForward) drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}
