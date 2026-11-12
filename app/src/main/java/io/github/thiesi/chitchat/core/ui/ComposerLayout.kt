package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether the single-row field is too narrow (P-216): under 9 × the body text size, in pixels. */
internal fun composerStacks(fieldWidthPx: Int, bodyTextPx: Float): Boolean = fieldWidthPx < bodyTextPx * 9f

/**
 * The pinned composer block: Details · field · Attach · Send in one row, or, when that row would
 * leave the field under 9 × the body text size, the field alone on a full-width row with Details
 * at the start and Attach and Send at the end below it (transcript-restyle.md, P-216).
 *
 * The three slots are always the same composables, only placed differently, so the field keeps
 * one instance (focus, IME session, tags) in both arrangements. Keyboard traversal follows
 * placement order and screen readers follow geometry, so both read field, Details, Attach, Send
 * when stacked. The decision depends only on width and font scale, so it never flips while typing.
 */
@Composable
internal fun ComposerLayout(modifier: Modifier = Modifier, spacing: Dp = 8.dp,
    leading: @Composable RowScope.() -> Unit, field: @Composable () -> Unit, trailing: @Composable RowScope.() -> Unit) {
    val bodyTextPx = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toPx() }
    Layout(contents = listOf(
        { Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Bottom, content = leading) },
        field,
        { Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Bottom, content = trailing) },
    ), modifier) { (leadingSlot, fieldSlot, trailingSlot), constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = leadingSlot.map { it.measure(loose) }
        val end = trailingSlot.map { it.measure(loose) }
        val startWidth = start.maxOfOrNull { it.width } ?: 0
        val endWidth = end.maxOfOrNull { it.width } ?: 0
        val controlsHeight = maxOf(start.maxOfOrNull { it.height } ?: 0, end.maxOfOrNull { it.height } ?: 0)
        val width = constraints.maxWidth
        val rowFieldWidth = width - startWidth - endWidth - gap * listOf(startWidth, endWidth).count { it > 0 }
        val stacked = composerStacks(rowFieldWidth, bodyTextPx)
        if (!stacked) {
            val fields = fieldSlot.map { it.measure(Constraints(rowFieldWidth, rowFieldWidth, 0, constraints.maxHeight)) }
            val height = maxOf(controlsHeight, fields.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                start.forEach { it.placeRelative(0, height - it.height) }
                fields.forEach { it.placeRelative(startWidth + if (startWidth > 0) gap else 0, height - it.height) }
                end.forEach { it.placeRelative(width - it.width, height - it.height) }
            }
        } else {
            val fieldMax = (constraints.maxHeight - controlsHeight - gap).coerceAtLeast(0)
            val fields = fieldSlot.map { it.measure(Constraints(width, width, 0, fieldMax)) }
            val fieldHeight = fields.maxOfOrNull { it.height } ?: 0
            val height = (fieldHeight + gap + controlsHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                fields.forEach { it.placeRelative(0, 0) }
                start.forEach { it.placeRelative(0, height - it.height) }
                end.forEach { it.placeRelative(width - it.width, height - it.height) }
            }
        }
    }
}
