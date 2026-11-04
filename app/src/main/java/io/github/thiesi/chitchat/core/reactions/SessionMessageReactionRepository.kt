package io.github.thiesi.chitchat.core.reactions

import io.github.thiesi.chitchat.core.conversation.MessageReaction
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.direct.validatedDirectPrivacy
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

/** Existing original-session capability and transport; no independent jar, refresh, or replay. */
internal class SessionMessageReactionRepository(private val sessions: SessionRepository) : MessageReactionOperations {
    override suspend fun setRoom(expected: SessionState, original: RoomMessage, emoji: ReactionEmoji,
        selected: Boolean): List<MessageReaction> {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { setRoomReaction(original, emoji, selected, access.csrf, access.userId) }
    }

    override suspend fun setDirect(expected: SessionState, original: DirectMessage, emoji: ReactionEmoji,
        selected: Boolean): List<MessageReaction> {
        val access = sessions.openAccess(expected)
        validatedDirectPrivacy(sessions.state.value.privacy)
        return sessions.authorized(access) { setDirectReaction(original, emoji, selected, access.csrf, access.userId) }
    }
}
