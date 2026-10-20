package io.github.thiesi.chitchat.core.notifications

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

/** Existing account capability/transport only; native guest access is a later consumer slice. */
internal class SessionNotificationRepository(private val sessions: SessionRepository) : NotificationOperations {
    override val session = sessions.state
    override suspend fun page(expected: SessionState, beforeId: Long?, limit: Int): NotificationPage {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { notifications(beforeId, limit) }
    }
    override suspend fun markRead(expected: SessionState, ids: List<Long>): NotificationReadResult {
        val requested = ids.toList() // Freeze the choice before waiting for session/vault access.
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { readNotifications(requested, access.csrf) }
    }
    override suspend fun markAllRead(expected: SessionState): NotificationReadResult {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { readAllNotifications(access.csrf) }
    }
}
