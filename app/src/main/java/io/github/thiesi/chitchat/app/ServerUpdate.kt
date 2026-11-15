package io.github.thiesi.chitchat.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.MINIMUM_SERVER_VERSION
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.session.operatorNote
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.ui.PrimaryButton

/** `{address}`: always the raw host, never the display name (server-update.md copy rules). */
private fun hostOf(server: String?): String = server?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: server.orEmpty()

/**
 * At server setup (onboarding step 3): the server answered and parsed but is older than the minimum. Only Copy note
 * and Use a different server (P-181); no sign-in, account or guest actions.
 */
@Composable
internal fun ServerUpdateNotice(session: SessionState, server: String?, onDifferentServer: () -> Unit) {
    val host = hostOf(server)
    UpdateContent(session, host, title = stringResource(R.string.server_update_title),
        body = stringResource(R.string.server_update_setup_body, session.installationName ?: host)) {
        TextButton(onClick = onDifferentServer, Modifier.testTag("server_update_other")) { Text(stringResource(R.string.server_update_other_server)) }
    }
}

/**
 * On a signed-in session's revalidation: replaces the shell (no bar, no tabs). The session stays signed in; drafts and
 * pending work stay in memory. Check again is an explicit refresh; Sign out is the ordinary sign-out.
 */
@Composable
internal fun ServerUpdateScreen(session: SessionState, server: String?, onCheckAgain: () -> Unit, onSignOut: () -> Unit) {
    val host = hostOf(server)
    // It replaces the Scaffold, so it paints the shell's own surface and content colour (J1 on PR259: dark mode).
    Surface(Modifier.fillMaxSize().testTag("server_update_screen"), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
            UpdateContent(session, host, title = stringResource(R.string.server_update_session_title, session.installationName ?: host),
                displayedTitle = stringResource(R.string.server_update_session_title, session.installationName ?: breakableHost(host)),
                body = stringResource(R.string.server_update_session_body)) {
                LanternOutlinedButton(onClick = onCheckAgain, enabled = !session.busy, modifier = Modifier.testTag("server_update_check")) {
                    Text(stringResource(R.string.server_update_check_again))
                }
                TextButton(onClick = onSignOut, Modifier.testTag("server_update_sign_out")) { Text(stringResource(R.string.sign_out)) }
            }
        }
    }
    }
}

/**
 * The host with a zero-width space after each dot, so a long host breaks at its dots in the displayed title (J2 on
 * PR259). Display only: the title's semantics, the note and the clipboard carry the raw host.
 */
internal fun breakableHost(host: String): String = host.replace(".", ".\u200B")

@Composable
private fun UpdateContent(session: SessionState, host: String, title: String, body: String, displayedTitle: String = title,
    actions: @Composable () -> Unit) {
    val context = LocalContext.current
    var copied by rememberSaveable { mutableStateOf(false) }
    val clipLabel = stringResource(R.string.app_name)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Announced once when it appears; a heading for navigation (server-update.md: focus moves to the title).
        Text(displayedTitle, Modifier.testTag("server_update_title").clearAndSetSemantics {
            text = AnnotatedString(title); heading(); liveRegion = LiveRegionMode.Polite
        }, style = MaterialTheme.typography.headlineSmall)
        Text(body, Modifier.testTag("server_update_body"))
        Text(session.reportedVersion?.let { stringResource(R.string.server_update_details, it, MINIMUM_SERVER_VERSION) }
            ?: stringResource(R.string.server_update_details_unknown, MINIMUM_SERVER_VERSION),
            style = MaterialTheme.typography.bodySmall, color = ChitChatTheme.colors.textMuted)
        PrimaryButton(onClick = {
            val manager = context.getSystemService(ClipboardManager::class.java)
            copied = try { manager?.setPrimaryClip(ClipData.newPlainText(clipLabel,
                operatorNote(host, session.reportedVersion))) != null } catch (_: SecurityException) { false }
        }, modifier = Modifier.fillMaxWidth().testTag("server_update_copy")) { Text(stringResource(R.string.server_update_copy_note)) }
        if (copied) Text(stringResource(R.string.server_update_note_copied), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall)
        actions()
    }
}
