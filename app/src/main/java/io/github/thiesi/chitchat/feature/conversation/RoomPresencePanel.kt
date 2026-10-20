package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.realtime.PresencePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import kotlinx.coroutines.delay

@Composable
internal fun RoomPresencePanel(state: RealtimeState?, eligible: Boolean, canEnter: Boolean, enter: () -> Unit) {
    val phase = state?.presencePhase ?: PresencePhase.NONE
    val status = state?.presence
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val received = remember(status) { state?.presenceReceivedAtMillis?.takeIf { it > 0 } ?: System.currentTimeMillis() }
    var remaining by remember(status) { mutableIntStateOf(status?.warningSeconds ?: 0) }
    var usersOpen by remember { mutableStateOf(false) }
    LaunchedEffect(status, lifecycle) {
        if (status?.warningSeconds != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                remaining = (status.warningSeconds - ((System.currentTimeMillis() - received).coerceAtLeast(0) / 1000).toInt()).coerceAtLeast(0)
                if (remaining > 0) delay(1000)
            } while (remaining > 0)
        }
    }
    LaunchedEffect(phase, state?.presenceStale) {
        if (phase !in setOf(PresencePhase.ACTIVE, PresencePhase.WARNING) || state?.presenceStale != false) usersOpen = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(when {
            !eligible -> R.string.presence_reading_only
            phase == PresencePhase.EXPIRED -> R.string.presence_expired
            phase == PresencePhase.ERROR -> R.string.presence_failed
            phase == PresencePhase.CONNECTING -> R.string.presence_entering
            phase == PresencePhase.ACTIVE -> R.string.presence_active
            phase == PresencePhase.WARNING -> R.string.presence_warning
            else -> R.string.presence_inactive
        }), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (eligible && phase == PresencePhase.WARNING) Text(pluralStringResource(R.plurals.presence_remaining, remaining, remaining))
        if (eligible && phase == PresencePhase.WARNING) {
            TextButton(onClick = enter, enabled = canEnter) {
                Text(stringResource(R.string.presence_stay))
            }
        }
        if (phase in setOf(PresencePhase.ACTIVE, PresencePhase.WARNING) && state != null) {
            if (state.presenceStale) Text(stringResource(R.string.presence_list_stale))
            else TextButton(onClick = { usersOpen = true }) {
                Text(pluralStringResource(R.plurals.presence_people, state.users.size, state.users.size))
            }
        }
    }
    if (usersOpen && state != null && !state.presenceStale) AlertDialog(onDismissRequest = { usersOpen = false },
        title = { Text(stringResource(R.string.presence_people_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.users.isEmpty()) item { Text(stringResource(R.string.presence_nobody)) }
                items(state.users, key = { it.id }) { user ->
                    Text(if (user.guest) stringResource(R.string.presence_guest, user.username) else user.username)
                }
            }
        }, confirmButton = { TextButton(onClick = { usersOpen = false }) { Text(stringResource(R.string.rooms_close)) } })
}
