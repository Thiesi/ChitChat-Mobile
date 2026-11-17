package io.github.thiesi.chitchat.feature.account

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.RecoveryKeyStatus
import io.github.thiesi.chitchat.core.account.UnknownResolution
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.status.Wait
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.feature.auth.StepUpDialog
import io.github.thiesi.chitchat.feature.status.waitText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The key's date in the medium style, from the server's ISO or SQL-style timestamp; null when it can't be read. */
internal fun recoveryKeyDate(createdAt: String?): String? = createdAt?.let { raw ->
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    runCatching { OffsetDateTime.parse(raw).format(format) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw.replace(' ', 'T')).format(format) }.getOrNull()
        ?: runCatching { LocalDate.parse(raw.take(10)).format(format) }.getOrNull()
}

/** "**Not set up.** Without one, …" with the honey `warning` glyph (recovery-key.md, PL O4a2b). */
@Composable
private fun NotSetUpLine(installation: String, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(20.dp), tint = ChitChatTheme.colors.warning)
        val lead = stringResource(R.string.recovery_key_not_set_up_lead)
        val body = stringResource(R.string.recovery_key_not_set_up_body, installation)
        Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }; append(" "); append(body) },
            color = ChitChatTheme.colors.textMuted)
    }
}

/**
 * The Recovery key row at the top of Privacy and security (recovery-key.md, P-188): only for a password account whose
 * status is known. `null` (provider-only) and a missing field show nothing.
 */
@Composable
internal fun RecoveryKeyRow(session: SessionState, installation: String, onOpen: () -> Unit) {
    val status = session.recoveryKey as? RecoveryKeyStatus.Known ?: return
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen).padding(vertical = 8.dp).testTag("recovery_key_row"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.recovery_key_row), style = MaterialTheme.typography.titleMedium)
        if (status.exists) recoveryKeyDate(status.createdAt)?.let {
            Text(stringResource(R.string.recovery_key_created, it), color = ChitChatTheme.colors.textMuted)
        } else NotSetUpLine(installation, "recovery_key_row_not_set_up")
    }
}

/**
 * The explanation screen behind the row (PL O4a2b): with a key, its date, the lead, what Replace does and an outlined
 * Replace key behind a decision dialog; without one, the Not set up line, the lead and Create recovery key as the primary,
 * with no dialog. Leaving it (navigation or genuine background, not configuration change) cancels an in-flight request.
 */
@Composable
fun RecoveryKeyScreen(model: RecoveryKeyViewModel, session: SessionState, installation: String) {
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) model.leave() }
    DisposableEffect(model) { onDispose { if (activity?.isChangingConfigurations != true) model.leave() } }
    var confirmingReplace by rememberSaveable { mutableStateOf(false) }
    val status = session.recoveryKey as? RecoveryKeyStatus.Known
    val idle = state.stage == RecoveryKeyStage.IDLE
    val enabled = idle && session.phase == SessionPhase.AUTHENTICATED && !session.busy && !session.refreshRequired && state.shown == null
    Column(Modifier.testTag("recovery_key_screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (status?.exists == true) recoveryKeyDate(status.createdAt)?.let {
            Text(stringResource(R.string.recovery_key_created, it), Modifier.testTag("recovery_key_created"), color = ChitChatTheme.colors.textMuted)
        }
        if (status?.exists == false) NotSetUpLine(installation, "recovery_key_not_set_up")
        Text(stringResource(R.string.recovery_key_explanation, installation))
        if (status?.exists == true) Text(stringResource(R.string.recovery_key_replace_explanation))
        if (!idle) CircularProgressIndicator(Modifier.testTag("recovery_key_busy"))
        state.outcome?.let { Outcome(it, enabled, model::request) }
        when (status?.exists) {
            true -> LanternOutlinedButton(onClick = { confirmingReplace = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("recovery_key_replace")) { Text(stringResource(R.string.recovery_key_replace)) }
            false -> PrimaryButton(onClick = model::request, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("recovery_key_create")) { Text(stringResource(R.string.recovery_key_create)) }
            null -> Unit
        }
    }
    if (confirmingReplace) AlertDialog(onDismissRequest = { confirmingReplace = false },
        title = { Text(stringResource(R.string.recovery_key_replace_title)) },
        text = { Text(stringResource(R.string.recovery_key_replace_body)) },
        dismissButton = { TextButton(onClick = { confirmingReplace = false }, Modifier.testTag("recovery_key_replace_cancel")) {
            Text(stringResource(R.string.cancel))
        } },
        confirmButton = { TextButton(onClick = { confirmingReplace = false; model.request() }, Modifier.testTag("recovery_key_replace_confirm")) {
            Text(stringResource(R.string.recovery_key_replace_confirm), color = MaterialTheme.colorScheme.error)
        } })
    val methods = state.methods
    if (state.stage == RecoveryKeyStage.VERIFYING && methods != null) StepUpDialog(
        title = stringResource(R.string.step_up_title), methods = methods, busy = state.verificationBusy,
        errorMessage = state.error?.let { accountError(it) }, onVerify = model::verify, onCancel = model::cancelVerification)
}

/** The result table's lines (recovery-key.md). Create again is an explicit new request; nothing is resent on its own. */
@Composable
private fun Outcome(outcome: RecoveryKeyOutcome, enabled: Boolean, onCreateAgain: () -> Unit) {
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    Column(Modifier.testTag("recovery_key_outcome"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (outcome) {
            RecoveryKeyOutcome.PasswordAccountRequired -> Text(stringResource(R.string.recovery_key_password_required), polite)
            is RecoveryKeyOutcome.RateLimited -> Text(outcome.retryAfterSeconds?.let {
                stringResource(R.string.recovery_key_rate_limited, waitText(Wait.of(it * 1000)))
            } ?: stringResource(R.string.recovery_key_rate_limited_unknown), polite)
            is RecoveryKeyOutcome.Failed -> InlineError(accountError(outcome.failure))
            is RecoveryKeyOutcome.Unknown -> when (outcome.resolution) {
                UnknownResolution.CREATED_ELSEWHERE -> {
                    val lead = stringResource(R.string.recovery_key_created_elsewhere_lead)
                    val body = stringResource(R.string.recovery_key_created_elsewhere_body)
                    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }; append(" "); append(body) },
                        polite.testTag("recovery_key_created_elsewhere"))
                    CreateAgain(enabled, onCreateAgain)
                }
                UnknownResolution.UNCHANGED -> Text(stringResource(R.string.recovery_key_unchanged), polite.testTag("recovery_key_unchanged"))
                UnknownResolution.UNRESOLVED -> {
                    Text(stringResource(R.string.recovery_key_unresolved), polite.testTag("recovery_key_unresolved"))
                    CreateAgain(enabled, onCreateAgain)
                }
            }
        }
    }
}

@Composable
private fun CreateAgain(enabled: Boolean, onClick: () -> Unit) =
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("recovery_key_create_again")) {
        Text(stringResource(R.string.recovery_key_create_again))
    }

/**
 * "Your recovery key didn't reach this phone." on the Rooms root (PL O4a2b): a level-2 block under the status slot,
 * outside resolveStatus, on `presenceWash` with the honey `warning` glyph; Go there and Not now. Announced once.
 */
@Composable
internal fun RecoveryKeyNotice(onGoThere: () -> Unit, onNotNow: () -> Unit) {
    val colors = ChitChatTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.presenceWash).padding(horizontal = 16.dp, vertical = 8.dp).testTag("recovery_key_notice")) {
        Row(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(20.dp), tint = colors.warning)
            val lead = stringResource(R.string.recovery_key_undelivered_lead)
            val body = stringResource(R.string.recovery_key_undelivered_body)
            Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lead) }; append(" "); append(body) },
                color = colors.text)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNotNow, Modifier.heightIn(min = 48.dp).testTag("recovery_key_notice_not_now")) {
                Text(stringResource(R.string.recovery_key_not_now))
            }
            TextButton(onClick = onGoThere, Modifier.heightIn(min = 48.dp).testTag("recovery_key_notice_go_there")) {
                Text(stringResource(R.string.recovery_key_go_there))
            }
        }
    }
}
