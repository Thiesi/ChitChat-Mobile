package io.github.thiesi.chitchat.feature.conversation

import androidx.compose.runtime.Composable
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.ui.MessageReactionPicker

/** Room authorization, canonical requests and work remain in the room actor. */
@Composable
internal fun RoomReactionPicker(dialog: RoomReactionDialog, current: RoomMessage?, viewer: Long, busy: Boolean,
    unknown: RoomReactionUnknown?, canCheck: Boolean, canReview: Boolean, error: String?,
    canChoose: (ReactionEmoji, Boolean) -> Boolean, onChoose: (ReactionEmoji, Boolean) -> Unit,
    onCheck: () -> Unit, onReview: () -> Unit, onDismiss: () -> Unit) {
    MessageReactionPicker(dialog.request, dialog.request.original.id, current?.reactions, current?.deleted == true,
        viewer, busy, dialog.checked, current != null && current !== dialog.request.original,
        unknown?.messageId, "room_reactions_dialog", "room_reaction_choices", canCheck, canReview, error,
        canChoose, onChoose, onCheck, onReview, onDismiss)
}
