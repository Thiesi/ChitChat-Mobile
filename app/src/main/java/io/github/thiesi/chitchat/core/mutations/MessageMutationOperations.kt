package io.github.thiesi.chitchat.core.mutations

import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.direct.validateDirectBody
import io.github.thiesi.chitchat.core.session.SessionState

/** Metadata hints, never authorization. Unknown hints must not enable an author control. */
class AuthorMessageActions(val canEdit: Boolean, val canDelete: Boolean)

enum class AuthorChangeKind { EDIT, DELETE }

/** One explicit author write. Exceptions/cancellation may follow a committed server mutation:
 * reconcile through existing history GETs before offering a new choice; never replay a POST.
 * Callers must additionally fence the original navigation entry and canonical row reference.
 */
internal interface MessageMutationOperations {
    suspend fun editRoom(expected: SessionState, original: RoomMessage, body: String): RoomMessage
    suspend fun deleteRoom(expected: SessionState, original: RoomMessage): RoomMessage
    suspend fun editDirect(expected: SessionState, original: DirectMessage, body: String): DirectMessage
    suspend fun deleteDirect(expected: SessionState, original: DirectMessage): DirectMessage
}

/** Edits are literal text, even in rooms: /me and /ping are NOT reparsed as send commands. */
internal fun validateEditedBody(body: String): String? = validateDirectBody(body)
