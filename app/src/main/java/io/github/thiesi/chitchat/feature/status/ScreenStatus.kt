package io.github.thiesi.chitchat.feature.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Rows 10–11 for the screen on show: what the slot under the top bar reads instead of each screen's own lines. */
data class ScreenStatus(val syncing: Boolean = false, val stale: Boolean = false)

/** Holds the current screen's status. Only the latest reporter counts, and a leaving screen clears only its own. */
class ScreenStatusHolder {
    private var owner: Any? = null
    var current by mutableStateOf(ScreenStatus())
        private set

    fun report(token: Any, status: ScreenStatus) { owner = token; current = status }
    fun clear(token: Any) { if (owner === token) { owner = null; current = ScreenStatus() } }
}

val LocalScreenStatus = compositionLocalOf<ScreenStatusHolder?> { null }

/** A screen reports its syncing and stale state to the slot; nothing is shown in the screen itself. */
@Composable
fun ReportScreenStatus(syncing: Boolean, stale: Boolean) {
    val holder = LocalScreenStatus.current ?: return
    val token = remember { Any() }
    SideEffect { holder.report(token, ScreenStatus(syncing, stale)) }
    DisposableEffect(holder) { onDispose { holder.clear(token) } }
}
