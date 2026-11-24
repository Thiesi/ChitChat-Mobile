package io.github.thiesi.chitchat.feature.auth

import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.session.AuthFlow
import kotlinx.coroutines.delay

@Composable
fun AuthScreen(state: SessionState, model: AuthViewModel, onBack: (() -> Unit)? = null,
    onPolicy: ((io.github.thiesi.chitchat.core.policies.PolicyDocumentName) -> Unit)? = null,
    top: (@Composable () -> Unit)? = null) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pauseRegistration() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.retryAtMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { now = System.currentTimeMillis(); if (now < state.retryAtMillis) delay(1000) }
            while (now < state.retryAtMillis)
        }
    }
    val waiting = now < state.retryAtMillis
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Server prefill P1, E (PL): a link's offer sits at the top of the form's scroller.
        top?.invoke()
        // The reset form shows its own refusals beside its action or field (PL, O4b2).
        // Only on the form itself: the pending MFA step has no lines of its own, so its errors stay here (review, #294).
        state.error?.takeUnless { state.authFlow == AuthFlow.RESET && state.reset.pending == null && state.resetOwnsError() }?.let { failure ->
            InlineError(when (failure.code) {
                // Rules acceptance at registration (play-readiness.md §1): the account wasn't created.
                "rules_version_outdated" -> stringResource(R.string.registration_rules_outdated, installationOf(state))
                "rules_acceptance_required" -> stringResource(R.string.registration_rules_required)
                else -> errorText(failure)
            })
        }
        if (state.registrationMayHaveSucceeded) Text(stringResource(R.string.registration_unknown))
        if (waiting && !(state.authFlow == AuthFlow.RESET && state.reset.pending == null)) {
            val seconds = ((state.retryAtMillis - now + 999) / 1000).toInt()
            Text(pluralStringResource(R.plurals.retry_after, seconds, seconds))
        }
        if (state.storageWarning) {
            Text(stringResource(if (state.phase == SessionPhase.SIGNED_OUT || state.phase == SessionPhase.INVALIDATED)
                R.string.session_clear_warning else R.string.session_storage_warning), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = if (state.phase == SessionPhase.AUTHENTICATED) model::refresh else model::signOut) {
                Text(stringResource(R.string.retry_secure_storage))
            }
        }
        when (state.phase) {
            SessionPhase.NO_SERVER, SessionPhase.CONNECTING -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.checking_session))
            }
            SessionPhase.ANONYMOUS -> {
                // The flow was chosen on Server found (O2a); each form has its own title.
                if (state.authFlow == AuthFlow.SIGN_IN) {
                    Text(stringResource(R.string.onboarding_sign_in_title), style = MaterialTheme.typography.titleLarge)
                    ResetCancelOutcome(state)
                }
                state.public?.lockdown?.takeIf { it.enabled }?.let {
                    Text(stringResource(R.string.maintenance_notice))
                    it.message?.takeIf(String::isNotBlank)?.let { message -> Text(message) }
                }
                if (state.refreshRequired) {
                    TextButton(onClick = model::refresh, enabled = !state.busy && !waiting) { Text(stringResource(R.string.refresh_session)) }
                }
                val enabled = !state.busy && !state.refreshRequired && !waiting
                if (state.authFlow == AuthFlow.REGISTER) {
                    if (state.public?.registrationEnabled == true) RegistrationForm(state, model, enabled && !state.public.lockdown.enabled, onPolicy)
                    else Text(stringResource(R.string.registration_disabled))
                }
                else if (state.authFlow == AuthFlow.RESET) ResetPassword(state, model, enabled, now)
                else {
                    if (state.authFlow == AuthFlow.RESTORE) {
                        Text(stringResource(R.string.restore_account), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.restoration_explanation))
                    }
                    PasswordForm(model, enabled, state.authFlow == AuthFlow.RESTORE,
                        forgot = if (state.authFlow == AuthFlow.SIGN_IN) { username -> ForgotPasswordRow(state, model, username) } else null)
                }
                if (state.public?.providers?.isNotEmpty() == true && state.authFlow != AuthFlow.RESET) Text(stringResource(R.string.providers_unavailable))
                // Flow selection moved to Server found; Back returns there (onboarding-restyle.md). The reset form has its
                // own Back to sign in.
                if (onBack != null && state.authFlow != AuthFlow.RESET) TextButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.testTag("onboarding_back")) {
                    Text(stringResource(R.string.back))
                }
                // P-221: below every action on Sign in and Create account only (no other screen gains it).
                if (state.authFlow != AuthFlow.RESTORE && state.authFlow != AuthFlow.RESET)
                    io.github.thiesi.chitchat.core.ui.AccessibilityFeedbackLink(state.public?.accessibilityFeedback)
            }
            SessionPhase.PENDING_MFA -> {
                Text(stringResource(if (state.authFlow == AuthFlow.RESTORE) R.string.finish_restoration else R.string.finish_sign_in), style = MaterialTheme.typography.titleLarge)
                if (state.authFlow == AuthFlow.RESTORE) Text(stringResource(R.string.restoration_mfa_pending))
                Text(stringResource(R.string.mfa_recovery_explanation))
                if (state.refreshRequired) Text(stringResource(R.string.mfa_restart_needed))
                RecoveryForm(model, !state.busy && !waiting && !state.refreshRequired)
                TextButton(onClick = model::signOut) { Text(stringResource(R.string.cancel_sign_in)) }
            }
            SessionPhase.AUTHENTICATED -> {
                Text(stringResource(R.string.signed_in_as, state.user?.username.orEmpty()), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.auth_preview_limit))
                if (state.accountRestored) Text(stringResource(R.string.account_restored))
                if (!state.storageWarning && !state.busy) Text(stringResource(R.string.session_retention))
                state.recoveryCodesRemaining?.let { Text(stringResource(R.string.recovery_remaining, it)) }
                if (state.refreshRequired) Text(stringResource(R.string.session_refresh_needed))
                LanternOutlinedButton(onClick = model::refresh, enabled = !state.busy && !waiting) { Text(stringResource(R.string.refresh_session)) }
                LanternOutlinedButton(onClick = model::signOut) { Text(stringResource(R.string.sign_out)) }
            }
            SessionPhase.GUEST -> {
                Text(stringResource(R.string.guest_detected))
                LanternOutlinedButton(onClick = model::signOut) { Text(stringResource(R.string.end_guest_visit)) }
            }
            SessionPhase.INVALIDATED -> {
                Text(stringResource(R.string.session_not_available), style = MaterialTheme.typography.titleLarge)
                // A deletion refused after the session ended for another reason (PL, D2): a caption under the title.
                io.github.thiesi.chitchat.feature.account.EndedRefusalCaption(state.closure)
                PrimaryButton(onClick = model::reconnect, enabled = !waiting) { Text(stringResource(R.string.reconnect)) }
                TextButton(onClick = model::signOut) { Text(stringResource(R.string.clear_local_session)) }
            }
            SessionPhase.SIGNED_OUT -> {
                Text(stringResource(if (state.storageWarning) R.string.sign_out_needs_attention else R.string.signed_out), style = MaterialTheme.typography.titleLarge)
                if (state.busy) Text(stringResource(R.string.confirming_logout))
                else if (state.logoutUnconfirmed && !state.storageWarning) Text(stringResource(R.string.logout_unconfirmed))
                PrimaryButton(onClick = model::reconnect, enabled = !state.busy && !state.storageWarning && !waiting) {
                    Text(stringResource(R.string.back_to_sign_in))
                }
            }
        }
        if (state.busy && state.phase != SessionPhase.SIGNED_OUT) CircularProgressIndicator()
    }
}

@Composable
private fun PasswordForm(model: AuthViewModel, enabled: Boolean, restore: Boolean,
    forgot: (@Composable (username: String) -> Unit)? = null) {
    var username by rememberSaveable { mutableStateOf("") }
    // Never put a password or recovery code in saved instance state or a ViewModel.
    var password by remember(restore) { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { password = "" }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = enabled && username.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            val entered = password
            password = ""
            focus.clearFocus(); keyboard?.hide()
            model.signIn(username, entered, restore)
        }
    }
    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("sign_in_username").semantics { contentType = ContentType.Username },
        label = { Text(stringResource(R.string.username)) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), shape = MaterialTheme.shapes.medium)
    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("sign_in_password").semantics { contentType = ContentType.Password },
        label = { Text(stringResource(R.string.password)) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }, onDone = { submit() }), shape = MaterialTheme.shapes.medium)
    // In the scroller after the password field, before Back and the P-221 footer (PL, O4b2); the pinned block is unchanged.
    forgot?.invoke(username)
    // Pinned above the keyboard, outside the scroller (#275, PL): it stays visible while typing.
    io.github.thiesi.chitchat.core.ui.PinnedAction {
        PrimaryButton(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth().testTag("sign_in_submit")) {
            Text(stringResource(if (restore) R.string.restore_and_sign_in else R.string.sign_in))
        }
    }
}

/**
 * Step 4a's reset entry (PL, O4b2; #298). At most one completed discovery per runtime per visit ([ResetDiscoveryVisit]):
 * the visit asks while it can, so an ask the repository dropped while the session was busy is asked again once it
 * settles. While nothing is known, an empty 48dp row keeps the layout still; then the Forgot password? button or the
 * static line. A quiet change: no semantics on the empty row and no live region.
 */
@Composable
private fun ForgotPasswordRow(state: SessionState, model: AuthViewModel, username: String) {
    var visit by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<ResetDiscoveryVisit, ArrayList<String>>(
        save = { ResetDiscoveryVisit.save(it) }, restore = { ResetDiscoveryVisit.restore(it) })) { mutableStateOf(ResetDiscoveryVisit()) }
    androidx.compose.runtime.LaunchedEffect(state.generation, state.server, state.phase, state.busy, state.reset.support) {
        visit = visit.observe(state)
        if (visit.shouldAsk(state)) {
            visit = visit.asked(state) // Before the call: a fixture may answer synchronously.
            model.discoverPasswordReset()
        }
    }
    val observed = Modifier.semantics { this[ResetVisitState] = visit.scalars() }
    when (state.reset.support) {
        io.github.thiesi.chitchat.core.session.ResetSupport.SUPPORTED -> TextButton(onClick = { model.startReset(username) },
            enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("forgot_password").then(observed)) {
            Text(stringResource(R.string.forgot_password))
        }
        io.github.thiesi.chitchat.core.session.ResetSupport.UNSUPPORTED -> androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).then(observed), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
            Text(stringResource(R.string.forgot_password_static), Modifier.testTag("forgot_password_static"),
                color = io.github.thiesi.chitchat.core.theme.ChitChatTheme.colors.textMuted)
        }
        else -> androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(48.dp).testTag("forgot_password_pending").then(observed))
    }
}

/**
 * A cancelled reset back on step 4a (PL, O4b2): "Nothing changed" as a muted caption under the title, announced once;
 * an uncertain cancel as the level-2 block. Both end on the next sign-in attempt or on leaving step 4a.
 */
@Composable
private fun ResetCancelOutcome(state: SessionState) {
    when (state.reset.outcome) {
        io.github.thiesi.chitchat.core.session.ResetOutcome.CANCELLED -> Text(stringResource(R.string.reset_cancelled),
            Modifier.testTag("reset_cancelled").semantics { liveRegion = LiveRegionMode.Polite },
            color = io.github.thiesi.chitchat.core.theme.ChitChatTheme.colors.textMuted)
        io.github.thiesi.chitchat.core.session.ResetOutcome.CANCEL_UNCERTAIN -> io.github.thiesi.chitchat.core.ui.WarningNotice(
            stringResource(R.string.reset_cancel_unknown_lead), stringResource(R.string.reset_cancel_unknown_body),
            Modifier.testTag("reset_cancel_unknown"))
        else -> Unit
    }
}

@Composable
private fun RecoveryForm(model: AuthViewModel, enabled: Boolean) {
    var code by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { code = "" }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = enabled && code.isNotBlank()
    val submit = {
        if (canSubmit) {
            val entered = code
            code = ""
            focus.clearFocus(); keyboard?.hide()
            model.recovery(entered)
        }
    }
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.recovery_code)) },
        enabled = enabled, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }), shape = MaterialTheme.shapes.medium)
    PrimaryButton(onClick = submit, enabled = canSubmit) { Text(stringResource(R.string.use_recovery_code)) }
}

/** account_closure_pending names the Restore a closed account button from its own resource, so they always match (PL, D2). */
@Composable
private fun labelled(id: Int): String =
    if (id == R.string.account_closure_pending) stringResource(id, stringResource(R.string.onboarding_restore)) else stringResource(id)

@Composable
private fun errorText(failure: ApiFailure): String = labelled(when {
    failure.kind == FailureKind.STORAGE -> R.string.secure_storage_unavailable
    failure.kind == FailureKind.TLS -> R.string.tls_error
    failure.kind == FailureKind.REDIRECT -> R.string.redirect_error
    failure.kind == FailureKind.INCOMPATIBLE -> R.string.incompatible_server
    failure.kind == FailureKind.EXPIRED -> R.string.session_expired
    failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.TIMEOUT -> R.string.network_error
    failure.status == 429 -> R.string.rate_limited
    failure.status == 503 -> R.string.server_unavailable
    failure.code == "invalid_credentials" -> R.string.invalid_credentials
    failure.code == "invalid_recovery_code" -> R.string.invalid_recovery_code
    failure.code == "csrf_failed" -> R.string.session_refresh_needed
    failure.code == "account_banned" -> R.string.account_banned
    failure.code == "mfa_enrollment_required" -> R.string.mfa_setup_required
    failure.code == "account_closure_pending" -> R.string.account_closure_pending
    failure.code == "account_closed" || failure.code == "account_restoration_expired" -> R.string.account_cannot_restore
    failure.code == "account_not_pending" -> R.string.account_not_pending
    failure.code == "registration_disabled" -> R.string.registration_disabled
    failure.code == "username_taken" -> R.string.username_taken
    failure.code == "invalid_username" -> R.string.username_policy
    failure.code == "weak_password" -> R.string.password_policy
    failure.code == "invalid_birth_date" -> R.string.invalid_birth_date
    failure.code == "registration_too_fast" -> R.string.registration_too_fast
    failure.code?.startsWith("registration_") == true -> R.string.registration_check_failed
    failure.status == 401 -> R.string.session_expired
    else -> R.string.request_rejected
})

/** The installation's name, or its host with break points (as on Server found). */
internal fun installationOf(state: SessionState): String = state.installationName
    ?: io.github.thiesi.chitchat.app.breakableHost(state.server?.value?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty())
