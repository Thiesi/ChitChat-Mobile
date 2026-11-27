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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density

/**
 * A screen's pinned bottom action (#275): a form's primary action that stays visible above the keyboard, outside the
 * scroller (the composer contract's pattern). A screen host provides the slot and draws its content below the
 * scroller. Without a host, the action renders in place.
 */
class PinnedActionSlot {
    internal var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    internal var owner: Any? = null
    /**
     * The action is absent while the keyboard shows (Create, #275). The host leaves its row out in the same frame the
     * keyboard's inset starts, reading the IME itself: clearing [content] from the action's own disposal lands a frame
     * late, and for that frame the keyboard's inset and the row both took the viewport (#351 fix B diag run 45969824617:
     * 226px at the keyboard's first frame, about 3px with animations off), which the screen's keep-in-view acted on.
     */
    internal var hideWhileImeVisible by mutableStateOf(false)
    /** The host's height in px, so a tall block can fall back to the scroller on very short windows. */
    var availableHeight by mutableIntStateOf(0)
        internal set
    /** The scroller's viewport in window coordinates, to tell whether in-flow content is fully on screen. */
    var viewport by mutableStateOf(Rect.Zero)
        internal set
}

val LocalPinnedActions = staticCompositionLocalOf<PinnedActionSlot?> { null }

/**
 * Takes no space while [collapsed] (read in layout, so state it reads relayouts the same pass) is true, and the content is
 * neither measured nor placed: it draws nothing, takes no input and leaves the semantics tree (TL, #351 fix B: a placed
 * 0x0 node could leave a zero-size Create button for accessibility). Used by the screen host for an action absent while
 * the keyboard shows (see [PinnedActionSlot.hideWhileImeVisible]).
 */
fun Modifier.collapsedWhile(collapsed: Density.() -> Boolean): Modifier = layout { measurable, constraints ->
    if (collapsed()) return@layout layout(0, 0) { }
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Places [content] in the host's pinned area while composed, or in place when there's no host. With
 * [hideWhileImeVisible], the host leaves the row out while the keyboard shows (the caller is absent then too).
 */
@Composable
fun PinnedAction(hideWhileImeVisible: Boolean = false, content: @Composable () -> Unit) {
    val slot = LocalPinnedActions.current
    if (slot == null) { content(); return }
    val owner = remember { Any() }
    val latest by rememberUpdatedState(content)
    DisposableEffect(slot) {
        slot.owner = owner
        slot.content = { latest() }
        onDispose { if (slot.owner === owner) { slot.content = null; slot.owner = null; slot.hideWhileImeVisible = false } }
    }
    DisposableEffect(slot, hideWhileImeVisible) {
        if (slot.owner === owner) slot.hideWhileImeVisible = hideWhileImeVisible
        onDispose { }
    }
}
