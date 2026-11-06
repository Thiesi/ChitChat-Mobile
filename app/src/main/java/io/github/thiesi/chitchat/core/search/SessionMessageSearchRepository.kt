package io.github.thiesi.chitchat.core.search

import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionState

internal class SessionMessageSearchRepository(private val sessions: SessionRepository) : MessageSearchOperations {
    override val session = sessions.state

    override suspend fun search(expected: SessionState, request: MessageSearchRequest): MessageSearchPage {
        val access = sessions.openAccess(expected)
        return sessions.authorized(access) { searchMessages(request, access.userId, access.csrf) }
    }
}
