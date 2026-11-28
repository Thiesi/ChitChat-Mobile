package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.ChitChatTheme

/**
 * The fixed 20dp honey `warning` glyph, centred on the first line of [style] rather than its top (PL, #289 follow-up),
 * at any font scale. Decorative: the text beside it carries the meaning.
 */
@Composable
internal fun WarningGlyph(style: TextStyle) {
    val firstLine = with(LocalDensity.current) { style.lineHeight.toDp() }
    val size = 20.dp
    Icon(painterResource(R.drawable.ic_warning), contentDescription = null,
        Modifier.padding(top = ((firstLine - size) / 2).coerceAtLeast(0.dp)).size(size), tint = ChitChatTheme.colors.warning)
}

/**
 * #384: where a [WarningNotice]'s text starts below the notice's top: the column's vertical padding (8dp) and the line's
 * (4dp). Measured by the proof diagnostic (textOffsetInNotice 12 px at density 1, equal to the window cross-check).
 */
internal val WarningNoticeTextTop: Dp = 12.dp

/**
 * #384 (TL, PL): the lead an in-form notice published as its held keep, in px (the same value the FormDialog spec reads).
 * Test-observable only, for the held-lead guard: a custom key, not mapped to AccessibilityNodeInfo, so accessibility
 * services never see it; nothing in production reads it. Set only while a lead is published (never a placeholder).
 */
internal val HeldLead = androidx.compose.ui.semantics.SemanticsPropertyKey<Float>("HeldLead")

/** The glyph and a bold lead followed by its body, merged for TalkBack. */
@Composable
internal fun WarningLine(lead: String, body: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: androidx.compose.ui.graphics.Color = ChitChatTheme.colors.text, gap: Dp = 8.dp,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}) {
    Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
        WarningGlyph(style)
        Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }; if (body.isNotEmpty()) { append(" "); append(body) } },
            style = style, color = color, onTextLayout = onTextLayout)
    }
}

/**
 * A level-2 block (status-presentation ladder): `presenceWash`, the warning line, and its text actions right-aligned,
 * wrapping at large text. Announced politely once when it appears.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun WarningNotice(lead: String, body: String, modifier: Modifier = Modifier,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}, actions: @Composable () -> Unit = {}) {
    // #384: the line's text sits WarningNoticeTextTop below the notice's top (the column's 8dp and the line's 4dp below).
    Column(modifier.fillMaxWidth().background(ChitChatTheme.colors.presenceWash).padding(horizontal = 16.dp, vertical = 8.dp)) {
        WarningLine(lead, body, Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            onTextLayout = onTextLayout)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
}
