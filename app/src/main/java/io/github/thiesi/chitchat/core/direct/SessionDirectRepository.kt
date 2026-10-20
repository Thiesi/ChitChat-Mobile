package io.github.thiesi.chitchat.core.direct

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.session.SessionAccess
import io.github.thiesi.chitchat.core.session.SessionRepository

/** Client protocol prerequisite. No native DM screen is claimed by this repository alone. */
internal class SessionDirectRepository(private val sessions: SessionRepository) : DirectOperations {
    override val session = sessions.state

    override suspend fun inbox(): DirectInbox {
        val access = sessions.refreshedAccess()
        val privacy = privacy()
        val display = session.value.chatPolicy?.display ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
        return sessions.authorized(access) { DirectInbox(directConversations(access.userId), privacy, display) }
    }
    override suspend fun context(peerId: Long): DirectContext {
        val access = sessions.refreshedAccess()
        val privacy = privacy()
        val policy = session.value.chatPolicy ?: throw ApiFailure(FailureKind.INCOMPATIBLE)
        return sessions.authorized(access) {
            val (peer, relationship) = directStatus(peerId, access.userId)
            DirectContext(peer, relationship, privacy, policy.display, policy.mutedEverywhere)
        }
    }
    override suspend fun search(prefix: String, limit: Int) = using { directUsers(prefix, limit, it.userId) }
    override suspend fun history(peerId: Long, beforeId: Long?, limit: Int) = using { directHistory(peerId, beforeId, limit, it.userId) }
    override suspend fun window(peerId: Long, lower: Long, upper: Long?) = using { access ->
        readCanonicalDirectWindow(peerId, access.userId, lower, upper) { before, limit ->
            sessions.ensureCurrent(access)
            directHistory(peerId, before, limit, access.userId).also { sessions.ensureCurrent(access) }
        }
    }
    override suspend fun send(peerId: Long, body: String, replyId: Long?) = using { directSend(peerId, body, replyId, it.csrf, it.userId) }
    override suspend fun read(peerId: Long) = using { directRead(peerId, it.csrf, it.userId) }
    override suspend fun setBlocked(peerId: Long, blocked: Boolean) = using { directBlock(peerId, blocked, it.csrf, it.userId) }

    private suspend fun <T> using(operation: suspend SessionHttp.(SessionAccess) -> T): T {
        val access = sessions.openAccess()
        privacy() // Missing/unsupported disclosure disables DMs, never substitutes an E2EE/privacy promise.
        return sessions.authorized(access) { operation(access) }
    }
    private fun privacy() = validatedDirectPrivacy(session.value.privacy)
}
