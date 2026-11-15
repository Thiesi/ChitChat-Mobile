package io.github.thiesi.chitchat.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/** How often the current tab was reselected at its root (navigation shell); 0 outside a tab root. */
internal val LocalTabReselect = compositionLocalOf { 0 }

/**
 * Runs [scrollToTop] each time the tab is reselected at its root. The count seen at entry is ignored, so entering or
 * restoring a root never scrolls by itself; nothing is refreshed.
 */
@Composable
internal fun OnTabReselect(scrollToTop: suspend () -> Unit) {
    val count = LocalTabReselect.current
    val entry = remember { count }
    LaunchedEffect(count) { if (count != entry) scrollToTop() }
}
