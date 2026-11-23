package io.github.thiesi.chitchat.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModel
import io.github.thiesi.chitchat.ChitChatApplication
import io.github.thiesi.chitchat.MainActivity
import io.github.thiesi.chitchat.core.server.ServerLink
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Server prefill P1's UI state, kept across configuration changes and nowhere else (PL rulings A to G):
 * - [editorPrefill]: a link for step 2 (A, B, D, G), applied once when "Where do you chat?" is on screen;
 * - [formOffer]: E, the line on an open sign-in or create form;
 * - [signedInDialog]: F, "You're already signed in".
 */
class ServerLinkViewModel : ViewModel() {
    val editorPrefill = MutableStateFlow<ServerLink?>(null)
    val formOffer = MutableStateFlow<ServerLink?>(null)
    val signedInDialog = MutableStateFlow<ServerLink?>(null)
}

/** The server link a VIEW intent carries (P1), or null. */
internal fun serverLinkFrom(intent: Intent?): ServerLink? =
    intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(ServerLink::from)

/**
 * The only exported handler for `chitchat://connect` (TL): no UI, no history, not in recents. It hands the link to the
 * process's pending holder and starts MainActivity with the push tap's own flags, so a running MainActivity gets
 * onNewIntent, a cold start creates one in the app's own task, and the link never starts a second MainActivity inside the
 * caller's task. MainActivity's launch mode and its own filters are unchanged.
 */
class LinkEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) (application as ChitChatApplication).serverLinks.offer(serverLinkFrom(intent))
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
