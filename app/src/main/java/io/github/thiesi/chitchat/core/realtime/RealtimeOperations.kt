package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.session.SessionState
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Transport delivery is not application: the consumer owns reduction and cursor acknowledgment. */
sealed interface RealtimeSignal {
    data object Opened : RealtimeSignal
    class Event(val value: RealtimeEvent) : RealtimeSignal
    data object SessionInvalidated : RealtimeSignal
}

/** Never generate a string representation of private event payloads. */
class RealtimeEvent internal constructor(
    val id: Long,
    val type: String,
    val roomId: Long?,
    val actorUserId: Long?,
    val payload: JsonObject,
    val createdAt: Instant,
    internal val invalidBeforeSessionVersion: Int? = null,
    internal val receivedAtMillis: Long = System.nanoTime() / 1_000_000, // Local receipt metadata, not a server field.
) {
    val known: Boolean get() = type in KNOWN_TYPES

    // Administrative events invalidate versions older than the newly assigned version.
    // A fresh sign-in must not be kicked out again by a retained event for its old session.
    internal fun invalidatesSession(version: Int): Boolean = type == "forced_logout" &&
        (invalidBeforeSessionVersion == null || version < invalidBeforeSessionVersion)

    companion object {
        internal val KNOWN_TYPES = setOf("room_message", "message_deleted", "direct_message", "ping",
            "message_reaction_changed", "rooms_changed", "presence_changed", "typing", "room_broadcast",
            "global_broadcast", "forced_logout")
    }
}

interface RealtimeOperations {
    val session: StateFlow<SessionState>

    /** One finite server stream. No reconnect, cursor mutation or automatic POST replay here. */
    suspend fun stream(afterId: Long, receive: suspend (RealtimeSignal) -> Unit)
    suspend fun heartbeat(connectionId: String, roomId: Long?, interacted: Boolean): PresenceStatus
    suspend fun presence(roomId: Long): List<PresenceUser>
}
