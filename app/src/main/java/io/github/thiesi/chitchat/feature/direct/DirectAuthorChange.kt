package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.direct.DirectReply
import io.github.thiesi.chitchat.core.direct.DirectReplyTarget
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.session.SessionState

/** Transient original scope, never a saved-state/logged message or transferable authorization. */
class DirectAuthorRequest internal constructor(internal val expected: SessionState, val peerId: Long,
    internal var original: DirectMessage, val kind: AuthorChangeKind)

class DirectAuthorDialog internal constructor(val request: DirectAuthorRequest, val body: String,
    val validation: String? = null, val checked: Boolean = false)

/** ID-only per-peer uncertainty, independent of SEND/READ/BLOCK and never a replay instruction. */
class DirectAuthorUnknown internal constructor(val peerId: Long, val messageId: Long, val kind: AuthorChangeKind)

/** Retire stale visible quotes before a fallible GET, without enabling an unavailable quote. */
internal fun applyDirectAuthorResult(messages: List<DirectMessage>, result: DirectMessage): List<DirectMessage> {
    val parties = setOf(result.sender.id, result.recipient.id)
    return messages.map { row ->
        if (setOf(row.sender.id, row.recipient.id) != parties) row
        else if (row.id == result.id) result
        else {
            val quote = row.replyTo
            if (quote?.kind != "direct" || quote.messageId != result.id || !quote.available || quote.message == null) row
            else DirectMessage(row.id, row.sender, row.recipient, row.body, row.readAt, row.createdAt, row.outgoing,
                row.deleted, row.editedAt, row.canEdit, row.canDelete,
                DirectReply(quote.kind, quote.messageId, true, DirectReplyTarget(result.id, result.sender,
                    if (result.deleted) "" else result.body.orEmpty(), result.deleted)), row.mentions, row.reactions, row.attachment)
        }
    }
}
