package io.github.thiesi.chitchat.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ExternalLinkChoice

/**
 * The app's own privacy policy (P-174, O3b2's "left out" note; TL, PL 2026-11-24): a fixed page, never a server value.
 * It opens only through the explicit [ExternalLinkChoice] naming its host, like the legal notice. The label is always
 * "The app's privacy policy" (never "ChitChat's": an installation can be called ChitChat), "Privacy policy" on Welcome.
 */
internal const val APP_PRIVACY_URL = "https://ChitChat.NetWorkXXIII.de/app/privacy"

/** About and privacy (PL): a row with the open-external icon, after the app's own items and before the server's. */
@Composable
internal fun AppPrivacyRow(modifier: Modifier = Modifier) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.app_privacy_policy)
    TextButton(onClick = { choosing = true }, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("app_privacy_row")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false))
            Icon(painterResource(R.drawable.ic_open_external), contentDescription = null)
        }
    }
    if (choosing) ExternalLinkChoice(APP_PRIVACY_URL, title, "app_privacy") { choosing = false }
}

/**
 * "Before you chat" and the Welcome footer (PL): a plain text link (link colour, underlined, Body, no icon) with a 48dp
 * target. [label] is the shown text; the choice is titled the same.
 */
@Composable
internal fun AppPrivacyTextLink(label: String, tag: String, modifier: Modifier = Modifier) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { choosing = true }.testTag(tag),
        contentAlignment = Alignment.CenterStart) {
        Text(label, color = ChitChatTheme.colors.steward, textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodyLarge)
    }
    if (choosing) ExternalLinkChoice(APP_PRIVACY_URL, label, "app_privacy") { choosing = false }
}
