package io.github.thiesi.chitchat.core.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** OLED surfaces separate by an edge rather than an invisible shadow. */
@Composable
internal fun lanternSurfaceBorder(): BorderStroke? = if (ChitChatTheme.colors === Lantern.amoledBlack)
    BorderStroke(Lantern.quietEdge,ChitChatTheme.colors.line) else null

@Composable
internal fun Modifier.lanternDialogEdge(): Modifier = lanternSurfaceBorder()?.let {
    border(it,MaterialTheme.shapes.extraLarge)
} ?: this
