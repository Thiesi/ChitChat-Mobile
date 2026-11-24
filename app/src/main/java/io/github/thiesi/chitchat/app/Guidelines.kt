package io.github.thiesi.chitchat.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.feature.policies.GuidelinesViewModel
import io.github.thiesi.chitchat.feature.policies.PolicyDocumentViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Before you chat" (play-readiness.md §1, mockups `Guidelines` and `GuidelinesServer`): full screen between sign-in
 * and the shell, no bar. Back leaves the app (no handler here), except that it closes a server document opened from
 * this screen. **I agree** is the single primary action; Sign out is the ordinary sign-out.
 */
@Composable
internal fun GuidelinesScreen(session: SessionState, server: String?, model: GuidelinesViewModel,
    documents: PolicyDocumentViewModel?, onSignOut: () -> Unit) {
    val ui by model.state.collectAsStateWithLifecycle()
    var document by rememberSaveable { mutableStateOf<PolicyDocumentName?>(null) }
    val host = server?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty()
    val installation = session.installationName ?: breakableHost(host)
    val policies = session.public?.policies
    val mine = ui.takeIf { it.account == session.user?.id && it.generation == session.generation }
    Surface(Modifier.fillMaxSize().testTag("guidelines_screen"), color = MaterialTheme.colorScheme.background) {
        val open = document?.takeIf { documents != null && (if (it == PolicyDocumentName.RULES) policies?.rules else policies?.privacy) != null }
        if (open != null && documents != null) {
            BackHandler { document = null }
            DocumentPane(documents, open, session, server, "guidelines_document_back") { document = null }
            return@Surface
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.guidelines_title), Modifier.testTag("guidelines_title").semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.guidelines_lead)) }
                    append(' '); append(stringResource(R.string.guidelines_rules))
                }, Modifier.testTag("guidelines_body"))
                Text(stringResource(R.string.guidelines_reporting))
                val rules = policies?.rules
                if (rules != null) {
                    Text(stringResource(R.string.policy_rules_title, installation), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.labelLarge)
                    Surface(color = ChitChatTheme.colors.raised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            DocumentRow(stringResource(R.string.guidelines_read_rules, installation), updatedCaption(rules.updatedAt),
                                "guidelines_rules_row", enabled = documents != null) { document = PolicyDocumentName.RULES }
                            if (policies.privacy != null) DocumentRow(stringResource(R.string.policy_privacy_title, installation), null,
                                "guidelines_privacy_row", enabled = documents != null) { document = PolicyDocumentName.PRIVACY }
                        }
                    }
                }
                Text(stringResource(if (rules != null) R.string.guidelines_operator_rules else R.string.guidelines_operator, installation),
                    Modifier.testTag("guidelines_operator"))
                // The app's own privacy policy (PL): a separate text link after the operator line, with or without the
                // server's documents (those belong to the server).
                AppPrivacyTextLink(stringResource(R.string.app_privacy_policy), "guidelines_app_privacy")
                val serverDue = rules != null && session.rules?.acceptanceRequired == true
                if (mine?.outdated == true) InlineError(stringResource(R.string.guidelines_rules_outdated, installation))
                mine?.failure?.let { InlineError(guidelinesFailure(it)) }
                if (serverDue) Text(stringResource(R.string.guidelines_consent, installation), Modifier.testTag("guidelines_consent"),
                    color = ChitChatTheme.colors.textMuted)
                val saving = mine?.saving == true
                PrimaryButton(onClick = { model.agree(session) }, enabled = !saving && !session.busy,
                    modifier = Modifier.fillMaxWidth().testTag("guidelines_agree")) {
                    Text(stringResource(if (saving) R.string.guidelines_saving else R.string.guidelines_agree))
                }
                TextButton(onClick = onSignOut, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("guidelines_sign_out")) {
                    Text(stringResource(R.string.sign_out))
                }
            }
        }
    }
}

@Composable
internal fun DocumentRow(label: String, caption: String?, tag: String, enabled: Boolean, onClick: () -> Unit) {
    // The row's text is its label; the icons are decorative (PL, O3b2).
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).testTag(tag)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_document), contentDescription = null, Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ChitChatTheme.colors.textMuted) }
        }
        Icon(painterResource(R.drawable.ic_back), contentDescription = null, Modifier.size(20.dp).scale(scaleX = -1f, scaleY = 1f))
    }
}

/** "Updated {date}" in the device's medium date format, as on the document screen; nothing when the date is unreadable. */
@Composable
private fun updatedCaption(updatedAt: String?): String? = updatedAt
    ?.let { runCatching { OffsetDateTime.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrNull() }
    ?.let { stringResource(R.string.policy_updated, it) }

/** The existing status ladder for a refused or unconfirmed accept, and a failed local save. The screen stays. */
@Composable
internal fun guidelinesFailure(failure: ApiFailure): String = stringResource(when {
    failure.kind == FailureKind.STORAGE -> R.string.settings_error
    failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.TIMEOUT -> R.string.network_error
    failure.kind == FailureKind.EXPIRED || failure.status == 401 -> R.string.session_expired
    failure.kind == FailureKind.INCOMPATIBLE -> R.string.incompatible_server
    failure.status == 429 -> R.string.rate_limited
    failure.status == 503 || (failure.status ?: 0) >= 500 -> R.string.server_unavailable
    failure.code == "csrf_failed" -> R.string.session_refresh_needed
    else -> R.string.request_rejected
})

/**
 * A server document over the screen it was opened from, full screen (system Back closes it too). The screen below
 * stays composed, so a form keeps what was typed without saving it anywhere. It paints the shell's background and
 * content colour (the #259 lesson) and is announced by its bar title.
 */
@Composable
internal fun PolicyDocumentDialog(documents: PolicyDocumentViewModel, name: PolicyDocumentName, session: SessionState,
    server: String?, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("policy_document_dialog"), color = MaterialTheme.colorScheme.background) {
            DocumentPane(documents, name, session, server, "policy_document_dialog_back", onClose)
        }
    }
}

/** The O3a document screen's bar (icon Back, then "Rules" or "Privacy policy", as in the shell) over the document. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DocumentPane(documents: PolicyDocumentViewModel, name: PolicyDocumentName, session: SessionState, server: String?,
    backTag: String, onBack: () -> Unit) {
    val title = stringResource(if (name == PolicyDocumentName.RULES) R.string.policy_rules_bar else R.string.policy_privacy_bar)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        androidx.compose.material3.TopAppBar(title = { BarTitle(if (name == PolicyDocumentName.RULES) R.string.policy_rules_bar
            else R.string.policy_privacy_bar) }, navigationIcon = {
            IconButton(onClick = onBack, Modifier.testTag(backTag)) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
            }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            PolicyRoute(documents, name, session, server)
        }
    }
}
