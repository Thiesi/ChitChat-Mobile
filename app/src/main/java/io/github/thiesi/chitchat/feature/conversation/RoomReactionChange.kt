package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.session.SessionState

/** Original session and canonical instance, held only in the feature's in-memory operation. */
class RoomReactionRequest internal constructor(internal val expected: SessionState, internal val original: RoomMessage)

class RoomReactionDialog internal constructor(val request: RoomReactionRequest, val checked: Boolean = false)

/** A possibly committed choice, never a replay command or SEND/READ consent. No body/reactor names. */
class RoomReactionUnknown internal constructor(
    val roomId: Long, val messageId: Long, val emoji: String, val selected: Boolean,
)

internal class RoomReactionWrite(val request: RoomReactionRequest, val emoji: ReactionEmoji, val selected: Boolean)

/** Apply a confirmed summary only to the original canonical instance, preserving all other fields. */
internal fun applyRoomReactionResult(messages: List<RoomMessage>, original: RoomMessage, reactions: List<MessageReaction>) =
    messages.map { row ->
        if (row !== original) row else RoomMessage(row.id, row.roomId, row.senderId, row.username, row.type,
            row.body, row.deleted, row.createdAt, row.attachment, row.replyTo, row.mentions, reactions,
            row.editedAt, row.deletionKind, row.authorActions)
    }
