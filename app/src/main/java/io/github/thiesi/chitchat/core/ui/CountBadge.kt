package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Badge
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thiesi.chitchat.core.theme.ChitChatTheme

/** Counts and attention use persimmon, not the destructive-error role. */
@Composable
internal fun CountBadge(modifier: Modifier=Modifier,mention: Boolean=false,content: (@Composable RowScope.() -> Unit)?=null) {
    val colors=ChitChatTheme.colors
    Badge(modifier=modifier,containerColor=if (mention) colors.presence else colors.signature,
        contentColor=colors.onSignature,content=content)
}
