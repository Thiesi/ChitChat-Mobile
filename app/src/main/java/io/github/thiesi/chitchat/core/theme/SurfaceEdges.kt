package io.github.thiesi.chitchat.core.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Only enabled border/text roles change; Material owns geometry and disabled styling. */
@Composable
internal fun LanternOutlinedButton(onClick: () -> Unit, modifier: Modifier=Modifier, enabled: Boolean=true,
    content: @Composable RowScope.() -> Unit) {
    val colors=ChitChatTheme.colors
    OutlinedButton(onClick=onClick,modifier=modifier,enabled=enabled,
        colors=ButtonDefaults.outlinedButtonColors(contentColor=colors.text),
        border=if(enabled) BorderStroke(1.dp,colors.fieldLine) else ButtonDefaults.outlinedButtonBorder(false),
        content=content)
}

/** OLED surfaces separate by an edge rather than an invisible shadow. */
@Composable
internal fun lanternSurfaceBorder(): BorderStroke? = if (ChitChatTheme.colors === Lantern.amoledBlack)
    BorderStroke(Lantern.quietEdge,ChitChatTheme.colors.line) else null

@Composable
internal fun Modifier.lanternDialogEdge(): Modifier = lanternSurfaceBorder()?.let {
    border(it,MaterialTheme.shapes.extraLarge)
} ?: this
