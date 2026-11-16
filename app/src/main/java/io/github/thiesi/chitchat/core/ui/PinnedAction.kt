package io.github.thiesi.chitchat.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * A screen's pinned bottom action (#275): a form's primary action that stays visible above the keyboard, outside the
 * scroller (the composer contract's pattern). A screen host provides the slot and draws its content below the
 * scroller. Without a host, the action renders in place.
 */
class PinnedActionSlot {
    internal var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    internal var owner: Any? = null
    /** The host's height in px, so a tall block can fall back to the scroller on very short windows. */
    var availableHeight by mutableIntStateOf(0)
        internal set
    /** The scroller's viewport in window coordinates, to tell whether in-flow content is fully on screen. */
    var viewport by mutableStateOf(Rect.Zero)
        internal set
}

val LocalPinnedActions = staticCompositionLocalOf<PinnedActionSlot?> { null }

/** Places [content] in the host's pinned area while composed, or in place when there's no host. */
@Composable
fun PinnedAction(content: @Composable () -> Unit) {
    val slot = LocalPinnedActions.current
    if (slot == null) { content(); return }
    val owner = remember { Any() }
    val latest by rememberUpdatedState(content)
    DisposableEffect(slot) {
        slot.owner = owner
        slot.content = { latest() }
        onDispose { if (slot.owner === owner) { slot.content = null; slot.owner = null } }
    }
}
