package io.github.thiesi.chitchat.feature.account

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.ClosureResult
import io.github.thiesi.chitchat.core.account.ClosureState
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.DialogScrollBody
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PinnedAction
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.core.ui.WarningNotice
import io.github.thiesi.chitchat.feature.auth.StepUpDialog
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** Privacy and security's last row (PL, D2): error-coloured text, 48dp, no icon, with 24dp extra space above. */
@Composable
fun DeleteAccountRow(onOpen: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.delete_account_row), Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen)
        .padding(vertical = 12.dp).testTag("delete_account_row"), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium)
}

/** "{label}" inside [text], in italics: the onboarding button's own label, so the two always match (PL, D2). */
internal fun withLabel(text: String, label: String): AnnotatedString = buildAnnotatedString {
    val at = text.indexOf(label)
    if (at < 0) { append(text); return@buildAnnotatedString }
    append(text.substring(0, at))
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(label) }
    append(text.substring(at + label.length))
}

/** A SemiBold block lead and its body (PL, D2). */
@Composable
private fun Block(lead: String, body: AnnotatedString, tag: String) {
    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(lead) }; append(" "); append(body) },
        Modifier.testTag(tag))
}

/** The server's deadline as the localized medium date and short time in the device's time zone, kept on one line (PL, D2). */
internal fun closureDeadline(finalizesAt: String, zone: ZoneId = ZoneId.systemDefault()): String? = runCatching {
    OffsetDateTime.parse(finalizesAt).atZoneSameInstant(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrNull()?.noBreak()

/** The hedged "about" date: the device clock plus 14 days, date only (§2; only on the signed-out line). */
internal fun approximateDeadline(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
    now.plus(14, ChronoUnit.DAYS).atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).noBreak()

private fun String.noBreak() = replace(' ', ' ').replace(' ', ' ')

/**
 * The explanation screen (play-readiness.md §2, D2): §2's copy, Export my data first, and Continue (error text) and
 * Cancel. Continue opens the decision dialog; only its Delete account sends. A live Unknown replaces Continue and Cancel
 * with Check as the single primary; "wasn't deleted" is a muted line under the title.
 */
@Composable
fun DeleteAccountScreen(model: AccountDeletionViewModel, session: SessionState, installation: String,
    onExportFirst: () -> Unit, onCancel: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) model.leave() }
    DisposableEffect(model) { onDispose { if (activity?.isChangingConfigurations != true) model.leave() } }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val restore = stringResource(R.string.onboarding_restore)
    val closure = session.closure
    val unknown = closure is ClosureState.Unknown || state.checking != null
    val idle = state.stage == DeletionStage.IDLE && state.checking == null
    val enabled = idle && session.phase == SessionPhase.AUTHENTICATED && !session.busy && !session.refreshRequired &&
        !session.updateRequired
    Column(Modifier.testTag("delete_account_screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.delete_account_title, installation), Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall)
        if (closure is ClosureState.NotClosed) Text(stringResource(R.string.delete_account_not_deleted),
            Modifier.testTag("delete_account_not_deleted").semantics { liveRegion = LiveRegionMode.Polite },
            color = ChitChatTheme.colors.textMuted)
        if (unknown) UnknownNotice(state.checkFailed)
        val emphasis = stringResource(R.string.delete_account_scope_emphasis, installation)
        Text(buildAnnotatedString {
            val text = stringResource(R.string.delete_account_scope, emphasis)
            val at = text.indexOf(emphasis)
            append(text.substring(0, at.coerceAtLeast(0)))
            if (at >= 0) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(emphasis) }; append(text.substring(at + emphasis.length)) }
        }, Modifier.testTag("delete_account_scope"))
        Block(stringResource(R.string.delete_account_now_lead), AnnotatedString(stringResource(R.string.delete_account_now)), "delete_account_now")
        Block(stringResource(R.string.delete_account_cooling_lead), withLabel(stringResource(R.string.delete_account_cooling, restore), restore),
            "delete_account_cooling")
        Block(stringResource(R.string.delete_account_after_lead), AnnotatedString(stringResource(R.string.delete_account_after)), "delete_account_after")
        Block(stringResource(R.string.delete_account_stays_lead), AnnotatedString(stringResource(R.string.delete_account_stays)), "delete_account_stays")
        state.refusal?.let { Refusal(it) }
        if (!idle) CircularProgressIndicator(Modifier.testTag("delete_account_busy"))
        TextButton(onClick = onExportFirst, enabled = idle, modifier = Modifier.heightIn(min = 48.dp).testTag("delete_account_export_first")) {
            Text(stringResource(R.string.delete_account_export_first))
        }
        if (!unknown) {
            TextButton(onClick = { confirming = true }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("delete_account_continue")) {
                Text(stringResource(R.string.delete_account_continue), color = if (enabled) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("delete_account_cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
    // Check is the single primary while Unknown shows (PL); pinned like the other forms' primaries.
    if (unknown) PinnedAction {
        PrimaryButton(onClick = model::check, enabled = state.checking == null && !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("delete_account_check")) { Text(stringResource(R.string.delete_account_check)) }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.delete_account_dialog_title)) },
        // P-220: the body scrolls, so Cancel and Delete account stay reachable at large text on a short window.
        text = { DialogScrollBody(Modifier.testTag("delete_account_dialog_body")) {
            Text(stringResource(R.string.delete_account_dialog_body))
        } },
        dismissButton = { TextButton(onClick = { confirming = false }, Modifier.testTag("delete_account_dialog_cancel")) {
            Text(stringResource(R.string.cancel))
        } },
        confirmButton = { TextButton(onClick = { confirming = false; model.delete() }, Modifier.testTag("delete_account_dialog_confirm")) {
            Text(stringResource(R.string.delete_account_confirm), color = MaterialTheme.colorScheme.error)
        } },
        modifier = Modifier.testTag("delete_account_dialog"))
    val methods = state.methods
    if (state.stage == DeletionStage.VERIFYING && methods != null) StepUpDialog(
        title = stringResource(R.string.step_up_title_delete_account), methods = methods, busy = state.verificationBusy,
        errorMessage = state.error?.let { accountError(it) }, onVerify = model::verify, onCancel = model::cancelVerification)
}

/** The level-2 notice for an Unknown deletion (PL): no inline action; Check is the screen's primary. */
@Composable
private fun UnknownNotice(checkFailed: Boolean) {
    WarningNotice(stringResource(R.string.delete_account_unknown_lead), stringResource(R.string.delete_account_unknown_body),
        Modifier.testTag("delete_account_unknown"))
    if (checkFailed) Text(stringResource(R.string.delete_account_check_failed),
        Modifier.testTag("delete_account_check_failed").semantics { liveRegion = LiveRegionMode.Polite },
        color = ChitChatTheme.colors.textMuted)
}

/** A definite refusal on a live session (PL wording). */
@Composable
private fun Refusal(result: ClosureResult) {
    val text = when (result) {
        ClosureResult.LastSuperAdmin -> stringResource(R.string.delete_account_last_super_admin)
        ClosureResult.NotActive -> stringResource(R.string.delete_account_not_active)
        is ClosureResult.RateLimited -> stringResource(R.string.delete_account_rate_limited)
        is ClosureResult.Failed -> accountError(result.failure)
        else -> return
    }
    androidx.compose.foundation.layout.Box(Modifier.testTag("delete_account_refusal")) { InlineError(text) }
}

/** The refusal caption on the ordinary ended-session page (PL): "wasn't deleted", then the reason. */
@Composable
fun EndedRefusalCaption(closure: ClosureState?) {
    val result = (closure as? ClosureState.EndedWithRefusal)?.result ?: return
    val text = when (result) {
        ClosureResult.LastSuperAdmin -> stringResource(R.string.delete_account_ended_last_super_admin)
        ClosureResult.NotActive -> stringResource(R.string.delete_account_ended_not_active)
        is ClosureResult.RateLimited -> stringResource(R.string.delete_account_ended_rate_limited)
        is ClosureResult.Failed -> stringResource(R.string.delete_account_not_deleted) + " " + accountError(result.failure)
        else -> return
    }
    Text(text, Modifier.testTag("delete_account_ended_refusal").semantics { liveRegion = LiveRegionMode.Polite },
        color = ChitChatTheme.colors.textMuted)
}

/** Whether [closure] takes over the signed-out page (D2): every outcome that lands after the session ended. */
fun closureTakesOver(session: SessionState, closure: ClosureState?): Boolean = session.phase != SessionPhase.AUTHENTICATED &&
    (closure is ClosureState.Waiting || closure is ClosureState.Unknown || closure is ClosureState.Scheduled ||
        closure is ClosureState.LikelyClosed)

/**
 * The full-screen outcomes after the session ended (PL, D2; signed out, no shell), each announced as a pane:
 * - Waiting: the server's forced_logout came first; the result replaces this screen when it lands. No actions.
 * - Unknown: the same notice as on the explanation screen, with Check as the single pinned primary.
 * - Scheduled: only after a confirmed success, with the server's deadline. Done goes to step 3.
 * - LikelyClosed: only after an actual anonymous or 401 session read; the hedged text, with Done to step 3.
 */
@Composable
fun ClosureOutcomeScreen(closure: ClosureState, installation: String, checking: Boolean, checkFailed: Boolean,
    onCheck: () -> Unit, onDone: () -> Unit) {
    val restore = stringResource(R.string.onboarding_restore)
    val title = when (closure) {
        is ClosureState.Waiting -> stringResource(R.string.delete_account_waiting_title)
        is ClosureState.Scheduled -> stringResource(R.string.delete_account_scheduled_title)
        is ClosureState.LikelyClosed -> stringResource(R.string.delete_account_signed_out_title)
        else -> stringResource(R.string.delete_account_title, installation)
    }
    // Back: Waiting leaves the app; Unknown, Scheduled and LikelyClosed go to step 3 for this server (PL).
    if (closure !is ClosureState.Waiting) androidx.activity.compose.BackHandler(onBack = onDone)
    Column(Modifier.testTag("closure_outcome").semantics { paneTitle = title }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.semantics { heading() }.testTag("closure_outcome_title"), style = MaterialTheme.typography.headlineSmall)
        when (closure) {
            is ClosureState.Waiting -> {
                Text(stringResource(R.string.delete_account_waiting_body), color = ChitChatTheme.colors.textMuted)
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).testTag("closure_waiting"))
            }
            is ClosureState.Scheduled -> {
                val deadline = closureDeadline(closure.finalizesAt) ?: closure.finalizesAt
                Text(stringResource(R.string.delete_account_scheduled_body, deadline), Modifier.testTag("closure_scheduled_body"))
                Text(withLabel(stringResource(R.string.delete_account_restore_line, restore), restore), Modifier.testTag("closure_restore_line"))
            }
            is ClosureState.LikelyClosed -> Text(withLabel(stringResource(R.string.delete_account_signed_out_body,
                approximateDeadline(), restore), restore), Modifier.testTag("closure_signed_out_body"))
            else -> UnknownNotice(checkFailed)
        }
    }
    when (closure) {
        is ClosureState.Scheduled, is ClosureState.LikelyClosed -> PinnedAction {
            PrimaryButton(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("closure_done")) {
                Text(stringResource(R.string.delete_account_done))
            }
        }
        is ClosureState.Unknown -> PinnedAction {
            PrimaryButton(onClick = onCheck, enabled = !checking, modifier = Modifier.fillMaxWidth().testTag("delete_account_check")) {
                Text(stringResource(R.string.delete_account_check))
            }
        }
        else -> Unit
    }
}
