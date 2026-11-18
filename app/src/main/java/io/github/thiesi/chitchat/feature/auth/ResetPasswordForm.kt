package io.github.thiesi.chitchat.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.RegistrationPreparation
import io.github.thiesi.chitchat.core.session.ResetOutcome
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.status.Wait
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.core.ui.WarningNotice
import io.github.thiesi.chitchat.feature.status.waitText
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal enum class ResetProblem { PASSWORD, CONFIRMATION }

/** Local hints only, as for Create (4b); the server stays authoritative (`400 validation_error`). */
internal fun resetProblem(username: String, password: String, confirmation: String): ResetProblem? {
    val name = username.trim()
    if (password.codePointCount(0, password.length) !in 12..4096 ||
        password.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 } ||
        (name.isNotEmpty() && password.lowercase(Locale.ROOT).contains(name.lowercase(Locale.ROOT)))) return ResetProblem.PASSWORD
    return if (password != confirmation) ResetProblem.CONFIRMATION else null
}

/** The codes the reset form shows itself, beside its action or field, instead of the screen's general error line. */
internal fun SessionState.resetOwnsError(): Boolean = error?.let {
    it.status == 429 || it.code in setOf("reset_failed", "validation_error", "weak_password", "reset_challenge_expired")
} == true

/**
 * Reset your password (recovery-key.md "Forgot your password?", O4b2), or its MFA step while a reset is pending. Signed
 * out, in the ANONYMOUS phase; a pending reset is not signed in.
 */
@Composable
internal fun ResetPassword(state: SessionState, model: AuthViewModel, enabled: Boolean, now: Long) {
    if (state.reset.pending != null) ResetMfaStep(state, model, enabled) else ResetForm(state, model, enabled, now)
}

/**
 * The form. Key, new password and confirmation live in composition memory only, cleared on ON_STOP and gone when the
 * form leaves (never in bundles or the ViewModel); after a refusal they keep their text, as the spec says. Every attempt uses a fresh challenge: whenever none is prepared and nothing is pending,
 * one is fetched; a submission consumes it even when refused. The action block follows Create (#275, PL): pinned while
 * the keyboard is hidden, absent while the IME is up, with the `reset_failed` and 429 lines directly above the button.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ResetForm(state: SessionState, model: AuthViewModel, enabled: Boolean, now: Long) {
    var username by rememberSaveable { mutableStateOf(model.resetPrefill) }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { key = ""; password = ""; confirmation = "" }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val waiting = now < state.retryAtMillis
    val preparation = state.reset.preparation
    // A fresh challenge before Reset password can be enabled again, after any outcome. Never a resend.
    LaunchedEffect(preparation, state.busy, waiting, state.refreshRequired) {
        if (preparation == RegistrationPreparation.IDLE && !state.busy && !waiting && !state.refreshRequired) model.prepareReset()
    }
    val problem = resetProblem(username, password, confirmation)
    val ready = enabled && preparation == RegistrationPreparation.READY
    val canSubmit = ready && username.isNotBlank() && key.isNotBlank() && password.isNotEmpty() && confirmation.isNotEmpty()
    val submit = {
        attempted = true
        if (canSubmit && problem == null) {
            // The fields keep their text after a refusal (recovery-key.md: reset_failed keeps the key and passwords);
            // they're cleared on ON_STOP and leave memory with the form.
            attempted = false
            focus.clearFocus(); keyboard?.hide()
            model.resetPassword(username, key, password)
        }
    }
    Column(Modifier.testTag("reset_form"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.reset_title), style = MaterialTheme.typography.titleLarge)
        when (state.reset.outcome) {
            // The password may have changed: say what to do, and nothing resends (PL wording).
            ResetOutcome.UNCERTAIN -> WarningNotice(stringResource(R.string.reset_unknown_lead), stringResource(R.string.reset_unknown_body),
                Modifier.testTag("reset_unknown")) {
                TextButton(onClick = { model.selectForm(io.github.thiesi.chitchat.core.session.AuthFlow.SIGN_IN) },
                    Modifier.heightIn(min = 48.dp).testTag("reset_unknown_sign_in")) { Text(stringResource(R.string.reset_sign_in)) }
            }
            ResetOutcome.EXPIRED -> Text(stringResource(R.string.reset_expired), Modifier.testTag("reset_expired")
                .semantics { liveRegion = LiveRegionMode.Polite }, color = ChitChatTheme.colors.textMuted)
            else -> Unit
        }
        Text(stringResource(R.string.reset_lead, installationOf(state)))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("reset_username")
            .semantics { contentType = ContentType.Username },
            label = { Text(stringResource(R.string.username)) }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), shape = MaterialTheme.shapes.medium)
        // Forgiving and unformatted: sent as typed after trimming, the server normalizes. No contentType, so autofill
        // doesn't offer saved passwords here; visible-password keyboard with autocorrect off.
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("reset_key"),
            label = { Text(stringResource(R.string.reset_key_label)) }, enabled = enabled, singleLine = true,
            supportingText = { Text(stringResource(R.string.reset_key_supporting)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            shape = MaterialTheme.shapes.medium)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("reset_password")
            .semantics { contentType = ContentType.NewPassword },
            label = { Text(stringResource(R.string.reset_new_password)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == ResetProblem.PASSWORD,
            supportingText = { Text(stringResource(R.string.password_policy)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            shape = MaterialTheme.shapes.medium)
        OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth().testTag("reset_confirmation"),
            label = { Text(stringResource(R.string.reset_confirm_password)) }, enabled = enabled, singleLine = true,
            isError = attempted && problem == ResetProblem.CONFIRMATION,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            // Done hides the keyboard, which brings the action block back (#275 rules, as on Create).
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }), shape = MaterialTheme.shapes.medium)
        if (attempted && problem != null) InlineError(stringResource(if (problem == ResetProblem.PASSWORD) R.string.password_policy
            else R.string.password_mismatch))
        if (state.error?.code == "validation_error" || state.error?.code == "weak_password") InlineError(stringResource(R.string.password_policy))
        // The calm anti-bot row, as on Create: never a percentage.
        when (preparation) {
            RegistrationPreparation.PREPARING -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.preparing_registration))
            }
            RegistrationPreparation.READY -> Text(stringResource(R.string.registration_ready))
            RegistrationPreparation.IDLE, RegistrationPreparation.FAILED -> TextButton(onClick = model::prepareReset,
                enabled = enabled && !waiting, modifier = Modifier.testTag("reset_refresh_check")) {
                Text(stringResource(R.string.refresh_registration_check))
            }
        }
        TextButton(onClick = { model.selectForm(io.github.thiesi.chitchat.core.session.AuthFlow.SIGN_IN) }, enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("reset_back_to_sign_in")) { Text(stringResource(R.string.back_to_sign_in)) }
        if (!WindowInsets.isImeVisible) io.github.thiesi.chitchat.core.ui.PinnedAction {
            Column(Modifier.fillMaxWidth().testTag("reset_action_block"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                // Generic for every cause; never says which field was wrong (recovery-key.md). InlineError is polite.
                if (state.error?.code == "reset_failed") androidx.compose.foundation.layout.Box(Modifier.testTag("reset_failed")) {
                    InlineError(stringResource(R.string.reset_failed))
                }
                // The tap found a stale challenge: nothing was sent, a fresh check is running (review, #294).
                if (state.error?.code == "reset_challenge_expired") Text(stringResource(R.string.reset_challenge_renewed),
                    polite.testTag("reset_challenge_renewed"), color = ChitChatTheme.colors.textMuted)
                if (state.error?.status == 429) Text(if (waiting) stringResource(R.string.reset_rate_limited,
                    waitText(Wait.of(state.retryAtMillis - now))) else stringResource(R.string.reset_rate_limited_unknown),
                    polite.testTag("reset_rate_limited"), color = MaterialTheme.colorScheme.error)
                PrimaryButton(onClick = submit, enabled = canSubmit && !waiting, modifier = Modifier.fillMaxWidth().testTag("reset_submit")) {
                    Text(stringResource(R.string.reset_submit))
                }
            }
        }
    }
}

/**
 * One more step: the pending reset's MFA (recovery-key.md). Only the recovery code is offered; a passkey-only reset can
 * only be cancelled (B1). Cancel reset and Back ask "Stop resetting your password?" first.
 */
@Composable
private fun ResetMfaStep(state: SessionState, model: AuthViewModel, enabled: Boolean) {
    val pending = state.reset.pending ?: return
    var code by remember { mutableStateOf("") }
    var stopping by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { code = "" }
    BackHandler(enabled = !stopping) { stopping = true }
    val canCode = "recovery_code" in pending.methods
    Column(Modifier.testTag("reset_mfa"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.reset_mfa_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.reset_mfa_lead))
        resetDeadline(pending.expiresAt)?.let { Text(stringResource(R.string.reset_mfa_deadline, it), Modifier.testTag("reset_mfa_deadline")) }
        if (canCode) {
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth().testTag("reset_mfa_code"),
                label = { Text(stringResource(R.string.reset_mfa_code_label)) }, enabled = enabled, singleLine = true,
                supportingText = { Text(stringResource(R.string.reset_mfa_code_not_key)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done), shape = MaterialTheme.shapes.medium)
            Text(stringResource(R.string.reset_mfa_code_warning), color = ChitChatTheme.colors.textMuted)
            PrimaryButton(onClick = { val entered = code; code = ""; model.completeResetRecovery(entered) },
                enabled = enabled && code.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("reset_mfa_use_code")) {
                Text(stringResource(R.string.use_recovery_code))
            }
        } else Text(stringResource(R.string.reset_mfa_passkey_only), Modifier.testTag("reset_mfa_passkey_only"))
        TextButton(onClick = { stopping = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("reset_cancel")) {
            Text(stringResource(R.string.reset_cancel))
        }
    }
    // A decision dialog with no body text, so there's no text slot to scroll (P-220); its actions are asserted at 200%.
    if (stopping) AlertDialog(onDismissRequest = { stopping = false },
        title = { Text(stringResource(R.string.reset_stop_title)) },
        dismissButton = { TextButton(onClick = { stopping = false }, Modifier.testTag("reset_keep_going")) {
            Text(stringResource(R.string.reset_keep_going))
        } },
        confirmButton = { TextButton(onClick = { stopping = false; model.cancelReset() }, Modifier.testTag("reset_stop")) {
            Text(stringResource(R.string.reset_stop))
        } })
}

/** "Finish before {time}" in the device's time format, from the server's `expires_at`; null when unreadable. */
@Composable
private fun resetDeadline(expiresAt: String?): String? = expiresAt?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }.getOrNull()
}
