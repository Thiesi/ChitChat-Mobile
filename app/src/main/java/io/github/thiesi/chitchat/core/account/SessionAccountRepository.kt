package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.session.SessionRepository

internal class SessionAccountRepository(private val sessions: SessionRepository) : AccountOperations {
    override val session = sessions.state
    override suspend fun dismissRecoveryKeyNotice(expected: io.github.thiesi.chitchat.core.session.SessionState) =
        sessions.clearRecoveryKeyUndelivered(expected)
    override suspend fun open(): AccountAccess {
        val access = sessions.openAccess()
        return object : AccountAccess {
            override fun ensureCurrent() = sessions.ensureCurrent(access)
            override suspend fun export() = sessions.authorized(access) { personalExport(access.csrf, access.userId) }
            override suspend fun verificationMethods() = sessions.authorized(access) { verificationMethods() }
            override suspend fun verifyPassword(password: String) = sessions.authorized(access) { verifyPassword(password, access.csrf) }
            override suspend fun createRecoveryKey(): CreatedRecoveryKey {
                val created = sessions.authorized(access) { createRecoveryKey(access.csrf) }
                sessions.recoveryKeyCreated(access, created.createdAt)
                return created
            }
            override suspend fun recoveryKeyAfterRefresh() = try { sessions.recoveryKeyAfterRefresh(access) }
                catch (_: io.github.thiesi.chitchat.core.network.ApiFailure) { null }
            override suspend fun verifyRecovery(code: String): Int {
                val remaining = sessions.authorized(access) { verifyRecovery(code.trim(), access.csrf) }
                sessions.recoveryConsumed(access, remaining)
                return remaining
            }
        }
    }
}
