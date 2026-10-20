package io.github.thiesi.chitchat.feature.realtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A connection is not proof that room history is current or that a message was read. */
@Composable
fun RealtimeStatus(state: RealtimeState, retry: () -> Unit, dismiss: (Long) -> Unit) {
    val label = when (state.phase) {
        RealtimePhase.STOPPED -> R.string.realtime_stopped
        RealtimePhase.CONNECTING -> R.string.realtime_connecting
        RealtimePhase.LIVE -> R.string.realtime_live
        RealtimePhase.RECONNECTING -> R.string.realtime_reconnecting
        RealtimePhase.OFFLINE -> R.string.realtime_offline
        RealtimePhase.ERROR -> when (state.error?.kind) {
            FailureKind.TLS -> R.string.realtime_tls_error
            FailureKind.INCOMPATIBLE, FailureKind.REDIRECT -> R.string.realtime_incompatible
            else -> R.string.realtime_error
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(label), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
            if (state.phase == RealtimePhase.ERROR) TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
        }
        val notice = state.broadcasts.firstOrNull { it.roomId == null || it.roomId == state.selectedRoomId }
        if (notice != null) {
            var expanded by remember(state.generation, notice.id) { mutableStateOf(false) }
            Text(stringResource(if (notice.roomId == null) R.string.realtime_global_notice else R.string.realtime_room_notice),
                style = MaterialTheme.typography.labelMedium)
            Text(notice.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row {
                TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.realtime_view_notice)) }
                TextButton(onClick = { dismiss(notice.id) }) { Text(stringResource(R.string.realtime_dismiss_notice)) }
            }
            if (expanded) AlertDialog(onDismissRequest = { expanded = false },
                title = { Text(stringResource(R.string.realtime_notice_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(notice.createdAt))
                        Text(notice.text)
                    }
                }, confirmButton = { TextButton(onClick = { dismiss(notice.id) }) { Text(stringResource(R.string.realtime_dismiss_notice)) } },
                dismissButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.back)) } })
        }
    }
}
