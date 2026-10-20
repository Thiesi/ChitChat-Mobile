package io.github.thiesi.chitchat.app

import io.github.thiesi.chitchat.core.conversation.*
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

/** Only for unrelated navigation fixtures; never installed in the production container. */
internal class EmptyConversationOperations(override val session: StateFlow<SessionState>) : ConversationOperations {
    override suspend fun context(roomId: Long): ConversationContext = error("Unconfigured fixture")
    override suspend fun history(roomId: Long, beforeId: Long?, limit: Int) = MessagePage(emptyList(), null, false)
    override suspend fun pings(roomId: Long) = emptyList<RoomPing>()
    override suspend fun send(roomId: Long, body: String): SendResult = error("Unconfigured fixture")
    override suspend fun read(roomId: Long, messageId: Long): Long = error("Unconfigured fixture")
}
