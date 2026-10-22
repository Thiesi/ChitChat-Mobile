package io.github.thiesi.chitchat.feature.conversation

import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.conversation.MessageReply
import io.github.thiesi.chitchat.core.conversation.ReplyMessage
import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.session.SessionState

enum class AuthorChangeKind { EDIT, DELETE }

/** Stable modal origin. Neither this nor its private text belongs in saved state or logs. */
class RoomAuthorRequest internal constructor(
    internal val expected: SessionState,
    internal var original: RoomMessage,
    val kind: AuthorChangeKind,
)

class RoomAuthorDialog internal constructor(
    val request: RoomAuthorRequest, val body: String, val validation: String? = null, val checked: Boolean = false,
)

/** ID-only, account-scoped uncertainty; not an uncertain SEND and never a retry command. */
class RoomAuthorUnknown internal constructor(val roomId: Long, val messageId: Long, val kind: AuthorChangeKind)

/** A confirmed change must retire stale quoted text even if the subsequent canonical GET fails. */
internal fun applyRoomAuthorResult(messages: List<RoomMessage>, result: RoomMessage) = messages.map { row ->
    if (row.roomId != result.roomId) row
    else if (row.id == result.id) result
    else {
        val quote = row.replyTo
        if (quote?.kind != "room" || quote.messageId != result.id || !quote.available || quote.message == null) row
        else RoomMessage(row.id, row.roomId, row.senderId, row.username, row.type, row.body, row.deleted, row.createdAt,
            row.attachment, MessageReply(quote.kind, quote.messageId, true, ReplyMessage(result.id,
                result.senderId?.let { MessagePerson(it, result.username) }, result.type, result.body.takeUnless { result.deleted }, result.deleted)),
            row.mentions, row.reactions, row.editedAt, row.deletionKind, row.authorActions)
    }
}
