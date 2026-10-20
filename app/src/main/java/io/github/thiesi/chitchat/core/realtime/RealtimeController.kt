package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.network.ApiFailure
import java.time.Instant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class RealtimePhase { STOPPED, CONNECTING, LIVE, RECONNECTING, OFFLINE, ERROR }
enum class PresencePhase { NONE, CONNECTING, ACTIVE, WARNING, EXPIRED, ERROR }

/** A banner, not a chat message or a notification/read cursor. */
class BroadcastNotice(val id: Long, val roomId: Long?, val text: String, val createdAt: Instant)

data class RealtimeState(
    val phase: RealtimePhase = RealtimePhase.STOPPED,
    val generation: Long = 0,
    val cursor: Long = 0,
    val reconcileVersion: Long = 0,
    val error: ApiFailure? = null,
    val selectedRoomId: Long? = null,
    val presencePhase: PresencePhase = PresencePhase.NONE,
    val presence: PresenceStatus? = null,
    val users: List<PresenceUser> = emptyList(),
    val presenceStale: Boolean = true,
    val presenceError: ApiFailure? = null,
    val broadcasts: List<BroadcastNotice> = emptyList(),
) {
    override fun toString() = "RealtimeState"
}

/** Foreground transport hints never replace canonical HTTP reads or acknowledge messages. */
interface RealtimeController {
    val state: StateFlow<RealtimeState>
    val events: SharedFlow<RealtimeEvent>
    fun selectRoom(roomId: Long?, eligible: Boolean, interacted: Boolean = false)
    fun activity(roomId: Long)
    fun reenter(roomId: Long)
    fun retry()
    fun dismissBroadcast(id: Long)
}
