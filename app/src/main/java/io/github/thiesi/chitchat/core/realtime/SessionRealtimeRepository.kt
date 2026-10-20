package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** No independent credentials, reconnect loop or cursor. The foreground coordinator owns those. */
internal class SessionRealtimeRepository(private val sessions: SessionRepository) : RealtimeOperations {
    override val session = sessions.state

    override suspend fun stream(afterId: Long, receive: suspend (RealtimeSignal) -> Unit) {
        val access = sessions.openAccess()
        sessions.authorized(access) {
            events(afterId).collect { signal ->
                sessions.ensureCurrent(access)
                if (signal == RealtimeSignal.SessionInvalidated ||
                    signal is RealtimeSignal.Event && signal.value.invalidatesSession(access.sessionVersion)) {
                    sessions.invalidateFromRealtime(access)
                    throw CancellationException("Session invalidated")
                }
                receive(signal)
                sessions.ensureCurrent(access)
            }
        }
    }

    override suspend fun heartbeat(connectionId: String, roomId: Long?, interacted: Boolean): PresenceStatus {
        val access = sessions.openAccess()
        return sessions.authorized(access) { presenceHeartbeat(connectionId, roomId, interacted, access.csrf) }
    }

    override suspend fun presence(roomId: Long): List<PresenceUser> {
        val access = sessions.openAccess()
        return sessions.authorized(access) { roomPresence(roomId) }
    }
}
