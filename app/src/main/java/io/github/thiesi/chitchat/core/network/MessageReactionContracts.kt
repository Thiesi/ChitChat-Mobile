package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.reactions.selectedBy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal class MessageReactionRequest(@SerialName("message_id") val messageId: Long, val emoji: String)
@Serializable internal class MessageReactionReply(val reactions: List<MessageReaction>)

/** Preserve the complete returned list and reactor identities, deriving selection for this viewer.
 * Counts are users.size; the wire has no separate count or target/namespace acknowledgment.
 */
internal fun validatedReactionSummary(reactions: List<MessageReaction>, viewerId: Long): List<MessageReaction> {
    requireValidAttention(emptyList(), reactions)
    incompatibleUnless(reactions.size <= ReactionEmoji.entries.size && reactions.map { it.emoji }.distinct().size == reactions.size)
    return reactions.map { reaction ->
        incompatibleUnless(ReactionEmoji.fromValue(reaction.emoji) != null && reaction.users.isNotEmpty() &&
            reaction.users.map { it.id }.distinct().size == reaction.users.size &&
            reaction.users.all { !it.username.isNullOrEmpty() })
        MessageReaction(reaction.emoji, reaction.users, reaction.selectedBy(viewerId))
    }
}
