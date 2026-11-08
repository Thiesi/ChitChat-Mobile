package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Badge
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.thiesi.chitchat.core.theme.ChitChatTheme

/** Counts use persimmon, not the destructive-error role. */
@Composable
internal fun CountBadge(modifier: Modifier=Modifier,mention: Boolean=false,content: (@Composable RowScope.() -> Unit)?=null) {
    val colors=ChitChatTheme.colors
    Badge(modifier=modifier,containerColor=if (mention) colors.presence else colors.signature,
        contentColor=colors.onSignature,content=content)
}

/** An unconfirmed outcome takes priority over a simultaneous error. */
@Composable
internal fun AttentionBadge(modifier: Modifier=Modifier, uncertain: Boolean=false, failed: Boolean=false) {
    val colors=ChitChatTheme.colors
    Badge(modifier=modifier,containerColor=when {
        uncertain -> colors.unsureLine
        failed -> colors.error
        else -> colors.signature
    })
}
