package io.github.thiesi.chitchat.core.ui

import androidx.lifecycle.Lifecycle

/** STARTED synchronization is not proof that a focused, unobscured transcript is being read. */
internal fun canAcknowledgeVisibleRead(state: Lifecycle.State, focused: Boolean, unobscured: Boolean = true) =
    state.isAtLeast(Lifecycle.State.RESUMED) && focused && unobscured

/** A tall newest row can be clipped above the viewport while its real bottom is visible. */
internal fun isReadEdgeVisible(offset: Int, size: Int, start: Int, end: Int): Boolean =
    end > start && size > 0 && offset < end && offset.toLong() + size > start && offset.toLong() + size <= end
