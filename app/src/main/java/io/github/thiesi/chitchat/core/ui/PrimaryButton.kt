package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.Lantern

/** The one filled primary action; callers retain callbacks, enabled state and text. */
@Composable
internal fun PrimaryButton(onClick: () -> Unit,modifier: Modifier=Modifier,enabled: Boolean=true,
    content: @Composable RowScope.() -> Unit) {
    val colors=ChitChatTheme.colors
    Button(onClick=onClick,enabled=enabled,modifier=modifier.defaultMinSize(minHeight=52.dp),shape=Lantern.pill,
        colors=ButtonDefaults.buttonColors(containerColor=colors.signature,contentColor=colors.onSignature,
            disabledContainerColor=colors.raised,disabledContentColor=colors.textMuted),content=content)
}
