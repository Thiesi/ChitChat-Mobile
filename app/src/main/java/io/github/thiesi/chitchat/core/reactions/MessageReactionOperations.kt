package io.github.thiesi.chitchat.core.reactions

import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.session.SessionState

/** Exact server vocabulary; the heart includes its required variation selector. */
internal enum class ReactionEmoji(val value: String) {
    THUMBS_UP("\uD83D\uDC4D"), HEART("\u2764\uFE0F"), LAUGH("\uD83D\uDE02"),
    SURPRISED("\uD83D\uDE2E"), SAD("\uD83D\uDE22"), CELEBRATE("\uD83C\uDF89");

    companion object {
        fun fromValue(value: String): ReactionEmoji? = entries.firstOrNull { it.value == value }
    }
}

/** Shared room events contain the actor's flag, not necessarily this viewer's selection. */
internal fun MessageReaction.selectedBy(viewerId: Long): Boolean = viewerId > 0 && users.any { it.id == viewerId }

/** One explicit desired choice, never a toggle, a send, or a read acknowledgment.
 * Callers own original entry/target/canonical guards and serialization with other writes.
 * Failure/cancellation can follow commit: explicitly GET/review current state before a fresh
 * choice, never replay the POST. Idempotent database rows do not make publication retry-safe.
 */
internal interface MessageReactionOperations {
    suspend fun setRoom(expected: SessionState, original: RoomMessage, emoji: ReactionEmoji,
        selected: Boolean): List<MessageReaction>
    suspend fun setDirect(expected: SessionState, original: DirectMessage, emoji: ReactionEmoji,
        selected: Boolean): List<MessageReaction>
}
