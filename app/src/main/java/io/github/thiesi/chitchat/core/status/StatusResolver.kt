package io.github.thiesi.chitchat.core.status

import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.RealtimePhase

/** Level 2 is a block under the top bar; level 1 is one muted caption line (status-presentation.md). */
enum class StatusLevel { CAPTION, BLOCK }

/** At most one of these shows on a signed-in screen, chosen by [resolveStatus]. Rows refer to the spec's table. */
sealed interface StatusNotice {
    val level: StatusLevel get() = StatusLevel.BLOCK

    /** Row 1. */ data object StorageWarning : StatusNotice
    /** Row 2. */ data object Offline : StatusNotice
    /** Row 3. */ data object SecureConnectionFailed : StatusNotice
    /** Row 4. */ data object Incompatible : StatusNotice
    /** Row 5. */ data object LiveUpdatesStopped : StatusNotice
    /** Row 6. */ data object RefreshRequired : StatusNotice
    /** Row 7: [wait] is null when the server gave no usable delay; never the session layer's fallback. */
    data class RateLimited(val wait: Wait?) : StatusNotice { override val level get() = StatusLevel.CAPTION }
    /** Row 8: reconnecting for more than [RECONNECT_GRACE_MILLIS]. */ data object Unreachable : StatusNotice
    /** Row 9: the first LIVE after rows 2–5 or 8, shown for about 2 s. */ data object BackOnline : StatusNotice
    /** Row 10: the screen's own uncertain state, specified by that screen. */
    data class Screen(val key: String) : StatusNotice
    /** Row 11. */ data object Syncing : StatusNotice { override val level get() = StatusLevel.CAPTION }
    /** Row 11. */ data object Stale : StatusNotice { override val level get() = StatusLevel.CAPTION }
}

const val RECONNECT_GRACE_MILLIS = 5_000L
const val BACK_ONLINE_MILLIS = 2_000L

/** Everything comes from existing state; clocks are passed in so tests control them. */
data class StatusInputs(
    val phase: RealtimePhase,
    val errorKind: FailureKind? = null,
    val storageWarning: Boolean = false,
    val refreshRequired: Boolean = false,
    val nowMillis: Long = 0,
    val retryAtMillis: Long = 0,
    /** True only when the 429 carried a positive integer `retry_after_seconds`. */
    val retryAfterKnown: Boolean = false,
    /** When the phase entered CONNECTING or RECONNECTING (monotonic), or null outside those phases. */
    val reconnectingSinceMillis: Long? = null,
    val monotonicNowMillis: Long = 0,
    /** The slot's recovery flag: the first LIVE after an offline, stream-error or unreachable notice. */
    val recovered: Boolean = false,
    val screen: StatusNotice.Screen? = null,
    val syncing: Boolean = false,
    val stale: Boolean = false,
)

/** The single notice for a screen, highest row first. LIVE alone shows nothing. */
fun resolveStatus(inputs: StatusInputs): StatusNotice? = with(inputs) {
    val reconnecting = phase == RealtimePhase.CONNECTING || phase == RealtimePhase.RECONNECTING
    when {
        storageWarning -> StatusNotice.StorageWarning
        phase == RealtimePhase.OFFLINE -> StatusNotice.Offline
        phase == RealtimePhase.ERROR && errorKind == FailureKind.TLS -> StatusNotice.SecureConnectionFailed
        phase == RealtimePhase.ERROR && (errorKind == FailureKind.INCOMPATIBLE || errorKind == FailureKind.REDIRECT) ->
            StatusNotice.Incompatible
        phase == RealtimePhase.ERROR -> StatusNotice.LiveUpdatesStopped
        refreshRequired -> StatusNotice.RefreshRequired
        nowMillis < retryAtMillis -> StatusNotice.RateLimited(if (retryAfterKnown) Wait.of(retryAtMillis - nowMillis) else null)
        reconnecting && reconnectingSinceMillis != null && monotonicNowMillis - reconnectingSinceMillis >= RECONNECT_GRACE_MILLIS ->
            StatusNotice.Unreachable
        phase == RealtimePhase.LIVE && recovered -> StatusNotice.BackOnline
        screen != null -> screen
        syncing -> StatusNotice.Syncing
        stale || phase == RealtimePhase.STOPPED -> StatusNotice.Stale
        else -> null
    }
}

/** Notices after which the next LIVE shows Back online. */
fun StatusNotice?.isDisconnection() = this == StatusNotice.Offline || this == StatusNotice.SecureConnectionFailed ||
    this == StatusNotice.Incompatible || this == StatusNotice.LiveUpdatesStopped || this == StatusNotice.Unreachable

/** A server-given wait, always rounded up so the app never says "now" before the server allows it. */
sealed interface Wait {
    data class Seconds(val seconds: Int) : Wait
    data object AboutAMinute : Wait
    data class AboutMinutes(val minutes: Int) : Wait
    data class AboutHours(val hours: Int) : Wait

    companion object {
        fun of(remainingMillis: Long): Wait {
            val seconds = ((remainingMillis + 999) / 1000).coerceAtLeast(1)
            return when {
                seconds < 60 -> Seconds(seconds.toInt())
                seconds < 120 -> AboutAMinute
                seconds < 3600 -> AboutMinutes(((seconds + 59) / 60).toInt())
                else -> AboutHours(((seconds + 3599) / 3600).toInt())
            }
        }
    }
}
