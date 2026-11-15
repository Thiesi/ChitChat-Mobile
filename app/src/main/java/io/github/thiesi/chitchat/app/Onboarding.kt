package io.github.thiesi.chitchat.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.AuthFlow
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.AccessibilityFeedbackLink
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton

/*
 * Onboarding (onboarding-restyle.md, O2a): 1 Welcome, 2 Where do you chat? (ServerEditor), 3 Server found, then the
 * existing forms. Behaviour, copy and tags; the visual restyle (lantern, server chip, always-dark Welcome) is Fable's.
 */

/** Step 1. No server saved yet; Get started leads to step 2. Back exits the app (no handler here). */
@Composable
internal fun WelcomeScreen(onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.welcome_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge)
        PrimaryButton(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag("onboarding_welcome_start")) {
            Text(stringResource(R.string.onboarding_get_started))
        }
        PreviewDisclosure()
    }
}

/** P-55: "What works in this preview" stays reachable before sign-in (AGENTS: keep availability disclosures). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewDisclosure() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }, Modifier.heightIn(min = 48.dp).testTag("onboarding_preview")) {
        Text(stringResource(R.string.onboarding_preview))
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).testTag("onboarding_preview_sheet")) {
            Text(stringResource(R.string.preview_limit))
        }
    }
}

/** Step 2's notice for a failed bootstrap of a server saved during this onboarding (onboarding-restyle.md copy table). */
internal fun stepTwoNotice(failure: ApiFailure): Int = when {
    failure.kind == FailureKind.TLS -> R.string.onboarding_tls
    failure.kind == FailureKind.INCOMPATIBLE || failure.kind == FailureKind.REDIRECT -> R.string.onboarding_incompatible
    failure.status == 503 -> R.string.onboarding_busy
    else -> R.string.onboarding_not_answering
}

/**
 * Step 3, after a successful anonymous bootstrap (P-52). The name (when the server reports one) with the host always
 * visible, the choices, the privacy line and the P-221 footer. An outdated server shows O1's notice instead of the
 * choices. No guest entry until M7.
 */
@Composable
internal fun ServerFound(session: SessionState, server: String?, onChoose: (AuthFlow) -> Unit, onOtherServer: () -> Unit) {
    val host = server?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: server.orEmpty()
    val public = session.public
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("onboarding_server_card")) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = ChitChatTheme.colors.textMuted)
            Column {
                Text(session.installationName ?: host, style = MaterialTheme.typography.titleLarge)
                if (session.installationName != null) Text(host, style = MaterialTheme.typography.bodyMedium, color = ChitChatTheme.colors.textMuted)
            }
        }
        if (session.updateRequired) ServerUpdateNotice(session, server, onOtherServer)
        else {
            Text(stringResource(R.string.connected_to_server), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            // At most one level-2 notice: lockdown first, then providers-unavailable.
            if (public?.lockdown?.enabled == true) InlineError(public.lockdown.message?.takeIf(String::isNotBlank)
                ?.let { "${stringResource(R.string.maintenance_notice)} $it" } ?: stringResource(R.string.maintenance_notice))
            else if (public?.providers?.isNotEmpty() == true) Text(stringResource(R.string.providers_unavailable))
            if (public?.registrationEnabled == true && !public.lockdown.enabled)
                PrimaryButton(onClick = { onChoose(AuthFlow.REGISTER) }, modifier = Modifier.fillMaxWidth().testTag("onboarding_create")) {
                    Text(stringResource(R.string.onboarding_create_account))
                }
            else Text(stringResource(R.string.registration_disabled), Modifier.testTag("onboarding_signup_closed"))
            PrimaryButton(onClick = { onChoose(AuthFlow.SIGN_IN) }, modifier = Modifier.fillMaxWidth().testTag("onboarding_sign_in")) {
                Text(stringResource(R.string.onboarding_sign_in))
            }
            TextButton(onClick = { onChoose(AuthFlow.RESTORE) }, Modifier.heightIn(min = 48.dp).testTag("onboarding_restore")) {
                Text(stringResource(R.string.onboarding_restore))
            }
            TextButton(onClick = onOtherServer, Modifier.heightIn(min = 48.dp).testTag("onboarding_other_server")) {
                Text(stringResource(R.string.server_update_other_server))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = ChitChatTheme.colors.textMuted)
            Text(stringResource(R.string.onboarding_privacy_line), color = ChitChatTheme.colors.textMuted)
        }
        PreviewDisclosure()
        AccessibilityFeedbackLink(public?.accessibilityFeedback) // P-221: below every action.
    }
}
