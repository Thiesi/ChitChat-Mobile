package io.github.thiesi.chitchat.core.realtime

import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Send when back online (status S4a rooms, S4b direct messages): suspends until a waiting draft's one
 * automatic attempt may go ahead, never on bare LIVE. That needs, in order:
 * - the connection is `LIVE`;
 * - a session revalidation strictly newer than this reconnect has completed (`bootstrapSequence`). At most
 *   one refresh is asked for per reconnect, through the existing coalescing session refresh;
 * - the conversation's own post-reconnect reconcile has finished ([reconciled]).
 *
 * After a failed revalidation, a known 429 deadline is waited out, then it revalidates once more. Without
 * a known deadline this reconnect is spent and only the next one tries again; nothing polls. Returns true
 * when the caller may re-read its own guards and send its frozen content once, or false when waiting
 * ended. The caller's job ends with the conversation leaving the screen, so no timer outlives it.
 */
internal suspend fun awaitSendWhenBackOnline(
    session: StateFlow<SessionState>,
    realtime: RealtimeController,
    revalidate: suspend () -> Unit,
    owner: (SessionState) -> Any?,
    expected: Any,
    stillWaiting: () -> Boolean,
    reconciled: Flow<Boolean>,
): Boolean {
    var snapshot = session.value.bootstrapSequence
    while (true) {
        realtime.state.first { it.phase == RealtimePhase.LIVE }
        if (!stillWaiting()) return false
        if (session.value.bootstrapSequence <= snapshot) revalidate()
        val (state, connection) = combine(session, realtime.state) { s, c -> s to c }.first { (s, c) ->
            c.phase != RealtimePhase.LIVE || owner(s) != expected || !s.busy
        }
        if (!stillWaiting() || owner(state) != expected) return false
        if (connection.phase != RealtimePhase.LIVE) { snapshot = state.bootstrapSequence; continue }
        if (state.bootstrapSequence <= snapshot || state.refreshRequired) {
            val error = state.error
            val wait = state.retryAtMillis - System.currentTimeMillis()
            if (error?.status == 429 && (error.retryAfterSeconds ?: 0L) > 0L && wait > 0) { delay(wait); continue }
            realtime.state.first { it.phase != RealtimePhase.LIVE }
            snapshot = session.value.bootstrapSequence
            continue
        }
        val back = combine(reconciled, realtime.state) { r, c -> r to c }.first { (r, c) -> c.phase != RealtimePhase.LIVE || r }
        if (!stillWaiting()) return false
        if (!back.first) { snapshot = session.value.bootstrapSequence; continue }
        return true
    }
}
