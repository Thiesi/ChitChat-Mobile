package io.github.thiesi.chitchat.core.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.thiesi.chitchat.MainActivity
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tapped push: the server and account it was posted for, and the server's `link` as a hint. The
 * activity is exported, so any app can send this; it grants nothing. The link still goes through the
 * native parser and the guarded destination flow, with fresh authorization for the signed-in account.
 */
class PushTap(val origin: String, val userId: Long, val link: String?) {
    /** Only the signed-in account the push was for. Anything else, including sign-in first, drops it. */
    fun matches(state: SessionState) = state.phase == SessionPhase.AUTHENTICATED &&
        state.server?.value == origin && state.user?.id == userId

    override fun toString() = "PushTap"

    companion object {
        private const val ACTION = "io.github.thiesi.chitchat.action.OPEN_PUSH"
        private const val EXTRA_ORIGIN = "io.github.thiesi.chitchat.extra.PUSH_ORIGIN"
        private const val EXTRA_USER = "io.github.thiesi.chitchat.extra.PUSH_USER"
        private const val EXTRA_LINK = "io.github.thiesi.chitchat.extra.PUSH_LINK"
        private const val MAX_ORIGIN = 2048
        private const val MAX_LINK = 4096

        /**
         * Opens the running app in place (`SINGLE_TOP`, so drafts and other screen state survive) or
         * starts it. The random data URI keeps each notification's PendingIntent distinct. A summary has
         * no link and opens Activity.
         */
        fun intent(context: Context, alert: PushAlert, link: String? = alert.payload.link): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .setData(Uri.fromParts("chitchat-push", UUID.randomUUID().toString(), null))
            .putExtra(EXTRA_ORIGIN, alert.origin)
            .putExtra(EXTRA_USER, alert.userId)
            .putExtra(EXTRA_LINK, link)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Strict and quiet: anything malformed is no tap. A relaunch from Recents never replays one. */
        fun from(intent: Intent?): PushTap? = try {
            if (intent == null || intent.action != ACTION ||
                intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) null
            else {
                val origin = intent.getStringExtra(EXTRA_ORIGIN)?.takeIf { it.isNotEmpty() && it.length <= MAX_ORIGIN }
                val user = intent.getLongExtra(EXTRA_USER, -1L).takeIf { it > 0 }
                // An oversized link is no destination, but the tap still opens Activity.
                val link = intent.getStringExtra(EXTRA_LINK)?.takeIf { it.length <= MAX_LINK }
                if (origin == null || user == null) null else PushTap(origin, user, link)
            }
        } catch (_: RuntimeException) { null } // A hostile or broken extras bundle.
    }
}

/**
 * Whether a tap that doesn't match the session can be dropped now. Waiting only covers the start-up
 * moments before the saved server is chosen and bootstrapped; every other state, including sign-in,
 * another account, guest or a failed connection, drops it.
 */
fun pushTapSettled(session: SessionState, settingsLoaded: Boolean, serverChosen: Boolean): Boolean = when (session.phase) {
    SessionPhase.CONNECTING -> false
    SessionPhase.NO_SERVER -> settingsLoaded && !serverChosen
    else -> true
}

/**
 * At most one tap waiting for the UI, in memory and scoped to the activity: it's never saved, so it
 * can't come back after sign-in, process death or a relaunch. A newer tap replaces an older one.
 */
class PendingPushTap {
    private val pending = MutableStateFlow<PushTap?>(null)
    val state: StateFlow<PushTap?> = pending.asStateFlow()

    fun offer(tap: PushTap?) { if (tap != null) pending.value = tap }

    /** Consumes exactly this tap; false if it was already consumed or replaced. */
    fun take(tap: PushTap): Boolean = pending.compareAndSet(tap, null)
}
