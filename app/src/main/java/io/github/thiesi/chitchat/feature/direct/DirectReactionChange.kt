package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.session.SessionState

/** Original UI/session/peer and canonical instance; never persisted or a transferable capability. */
class DirectReactionRequest internal constructor(internal val expected: SessionState, val peerId: Long,
    internal val original: DirectMessage)

class DirectReactionDialog internal constructor(val request: DirectReactionRequest, val checked: Boolean = false)

/** ID-only unknown outcome, independent of SEND/READ/BLOCK/AUTHOR and never a retry instruction. */
class DirectReactionUnknown internal constructor(val peerId: Long, val messageId: Long, val emoji: String, val selected: Boolean)

internal class DirectReactionWrite(val request: DirectReactionRequest, val emoji: ReactionEmoji, val selected: Boolean)

internal fun applyDirectReactionResult(messages: List<DirectMessage>, original: DirectMessage, reactions: List<MessageReaction>) =
    messages.map { row ->
        if (row !== original) row else DirectMessage(row.id, row.sender, row.recipient, row.body, row.readAt,
            row.createdAt, row.outgoing, row.deleted, row.editedAt, row.canEdit, row.canDelete,
            row.replyTo, row.mentions, reactions, row.attachment)
    }
