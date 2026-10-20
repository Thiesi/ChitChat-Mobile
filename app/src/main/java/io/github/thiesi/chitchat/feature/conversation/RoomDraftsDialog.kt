package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState

/** A shared host above the directory/conversation, so inaccessible rooms cannot strand drafts. */
@Composable
fun RoomDraftsDialog(model: ConversationViewModel, session: SessionState, onOpenRoom: (Long) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val saved by model.drafts.collectAsStateWithLifecycle()
    val currentSession by model.session.collectAsStateWithLifecycle()
    if (!state.draftsOpen || session.phase != SessionPhase.AUTHENTICATED) return
    val original = session
    val visible = saved.values.filter { it.matches(original) && it.matches(currentSession) }
        .sortedWith(compareBy<RoomDraft> { it.roomName.orEmpty().lowercase() }.thenBy { it.roomId })
    AlertDialog(onDismissRequest = { model.hideDrafts(original) }, modifier = Modifier.testTag("room_drafts_dialog"),
        title = { Text(stringResource(R.string.room_drafts_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.room_drafts_scope))
            if (visible.isEmpty()) Text(stringResource(R.string.room_drafts_empty))
            visible.forEach { draft ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(draft.roomName ?: stringResource(R.string.room_draft_room, draft.roomId),
                        Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    if (draft.uncertain) Text(stringResource(R.string.room_draft_unknown))
                    Text(draft.body.take(100), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            if (draft.matches(original) && draft.matches(model.session.value)) {
                                model.hideDrafts(original); onOpenRoom(draft.roomId)
                            }
                        }, enabled = !state.sending, modifier = Modifier.weight(1f).testTag("room_draft_open:${draft.roomId}")) {
                            Text(stringResource(R.string.room_draft_open))
                        }
                        TextButton(onClick = { model.discardDraft(draft) }, enabled = !state.sending,
                            modifier = Modifier.weight(1f).testTag("room_draft_discard:${draft.roomId}")) {
                            Text(stringResource(R.string.room_draft_discard))
                        }
                    }
                    HorizontalDivider()
                }
            }
        } }, confirmButton = { TextButton(onClick = { model.hideDrafts(original) }) { Text(stringResource(R.string.rooms_close)) } })
}
