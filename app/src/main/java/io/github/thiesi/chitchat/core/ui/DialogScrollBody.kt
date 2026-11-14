package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * P-220's dialog text slot: only this body scrolls (the title and buttons stay), with the 1dp `outline` scroll-edge
 * dividers while there is more below or once it has scrolled. Every line and action inside stays reachable by
 * scrolling and by TalkBack. Use it as an AlertDialog's `text`.
 */
@Composable
internal fun DialogScrollBody(modifier: Modifier = Modifier, verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(modifier.scrollEdgeDividers(scroll, MaterialTheme.colorScheme.outline).verticalScroll(scroll),
        verticalArrangement = verticalArrangement, content = content)
}
