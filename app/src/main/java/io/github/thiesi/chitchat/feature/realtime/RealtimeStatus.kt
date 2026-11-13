package io.github.thiesi.chitchat.feature.realtime

import io.github.thiesi.chitchat.core.theme.lanternDialogEdge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A server or room announcement: a message from the people who run the server, not status and not a chat
 * row (status-presentation.md "Announcements", P-104). At most one shows, the first that matches.
 */
@Composable
fun AnnouncementCard(state: RealtimeState, dismiss: (Long) -> Unit, modifier: Modifier = Modifier, roomId: Long? = state.selectedRoomId) {
    val notice = state.broadcasts.firstOrNull { it.roomId == null || it.roomId == roomId } ?: return
    val colors = ChitChatTheme.colors
    var expanded by remember(state.generation, notice.id) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(IntrinsicSize.Min)
        .background(colors.surface).testTag("announcement_card")) {
        // The only side edge in Lantern: it marks "from the people who run this server".
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.steward))
        Column(Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(if (notice.roomId == null) R.string.realtime_global_notice else R.string.realtime_room_notice),
                style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            Text(notice.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.realtime_view_notice)) }
                TextButton(onClick = { dismiss(notice.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.realtime_dismiss_notice)) }
            }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.realtime_notice_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(notice.createdAt))
                Text(notice.text)
            }
        }, confirmButton = { TextButton(onClick = { dismiss(notice.id) }) { Text(stringResource(R.string.realtime_dismiss_notice)) } },
        dismissButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.back)) } }, modifier = Modifier.lanternDialogEdge())
}
