package io.github.thiesi.chitchat.core.ui

import io.github.thiesi.chitchat.core.theme.lanternDialogEdge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.reactions.selectedBy

@Composable
internal fun reactionLabel(emoji: ReactionEmoji): String = stringResource(when (emoji) {
    ReactionEmoji.THUMBS_UP -> R.string.reaction_thumbs_up
    ReactionEmoji.HEART -> R.string.reaction_heart
    ReactionEmoji.LAUGH -> R.string.reaction_laugh
    ReactionEmoji.SURPRISED -> R.string.reaction_surprised
    ReactionEmoji.SAD -> R.string.reaction_sad
    ReactionEmoji.CELEBRATE -> R.string.reaction_celebrate
})

/** Presentation only: the original feature request and ViewModel own all authorization and work. */
@Composable
internal fun MessageReactionPicker(requestKey: Any, messageId: Long, groups: List<MessageReaction>?,
    deleted: Boolean, viewer: Long, busy: Boolean, checked: Boolean, changed: Boolean,
    unknownMessageId: Long?, dialogTag: String, choicesTag: String,
    canCheck: Boolean, canReview: Boolean, error: String?,
    canChoose: (ReactionEmoji, Boolean) -> Boolean, onChoose: (ReactionEmoji, Boolean) -> Unit,
    onCheck: () -> Unit, onReview: () -> Unit, onDismiss: () -> Unit) {
    var people by remember(requestKey) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.testTag(dialogTag).lanternDialogEdge(),
        title = { Text(stringResource(R.string.room_reactions_message, messageId.toString())) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag(choicesTag),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "status") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        unknownMessageId != null -> Text(stringResource(R.string.reaction_unconfirmed, unknownMessageId.toString()))
                        groups == null -> Text(stringResource(R.string.message_actions_unavailable))
                        checked || changed -> Text(stringResource(R.string.reaction_review_needed))
                        else -> Text(stringResource(R.string.reaction_choice_explanation))
                    }
                    if (deleted) Text(stringResource(R.string.reaction_deleted_removal))
                    if (busy) Text(stringResource(R.string.reaction_changing))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (unknownMessageId != null) TextButton(onClick = onCheck, enabled = canCheck && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reaction_check_history")) {
                        Text(stringResource(R.string.reaction_check_history))
                    }
                    else if (checked || changed) TextButton(onClick = onReview, enabled = canReview && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reaction_review")) {
                        Text(stringResource(R.string.reaction_review))
                    }
                } }
                if (people) {
                    groups?.forEachIndexed { index, group ->
                        item(key = "group:$index") {
                            Text(stringResource(R.string.reaction_count, group.emoji, group.users.size), style = MaterialTheme.typography.titleSmall)
                        }
                        items(group.users, key = { "person:$index:${it.id}" }) { person ->
                            Text(person.username ?: stringResource(R.string.conversation_unknown_user))
                        }
                    }
                } else {
                    items(ReactionEmoji.entries, key = { it.name }) { emoji ->
                        val group = groups?.firstOrNull { it.emoji == emoji.value }
                        val selected = group?.selectedBy(viewer) == true
                        val label = reactionLabel(emoji)
                        Column {
                            TextButton(onClick = { onChoose(emoji, !selected) }, enabled = groups != null && !busy &&
                                unknownMessageId == null && !checked && !changed && canChoose(emoji, !selected),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reaction_choice:${emoji.name}")
                                    .semantics { this.selected = selected }) {
                                Text(stringResource(if (selected) R.string.reaction_remove else R.string.reaction_add,
                                    emoji.value, label, group?.users?.size ?: 0))
                            }
                        }
                    }
                    if (!groups.isNullOrEmpty()) item(key = "people") {
                        TextButton(onClick = { people = true }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reaction_people")) {
                            Text(stringResource(R.string.reaction_people))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (people) people = false else onDismiss() }, enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("reaction_close")) {
            Text(stringResource(if (people) R.string.reaction_back else R.string.rooms_close))
        } })
}
