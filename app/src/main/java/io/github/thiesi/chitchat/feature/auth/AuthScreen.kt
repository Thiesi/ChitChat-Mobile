package io.github.thiesi.chitchat.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
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
fun AuthScreen(state: SessionState, model: AuthViewModel) {
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
        state.error?.let { failure ->
            InlineError(errorText(failure))
        }
        if (state.registrationMayHaveSucceeded) Text(stringResource(R.string.registration_unknown))
        if (waiting) {
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
                Text(stringResource(R.string.connected_to_server), style = MaterialTheme.typography.titleLarge)
                state.public?.lockdown?.takeIf { it.enabled }?.let {
                    Text(stringResource(R.string.maintenance_notice))
                    it.message?.takeIf(String::isNotBlank)?.let { message -> Text(message) }
                }
                if (state.refreshRequired) {
                    TextButton(onClick = model::refresh, enabled = !state.busy && !waiting) { Text(stringResource(R.string.refresh_session)) }
                }
                val enabled = !state.busy && !state.refreshRequired && !waiting
                if (state.authFlow == AuthFlow.REGISTER) {
                    if (state.public?.registrationEnabled == true) RegistrationForm(state, model, enabled && !state.public.lockdown.enabled)
                    else Text(stringResource(R.string.registration_disabled))
                }
                else {
                    if (state.authFlow == AuthFlow.RESTORE) {
                        Text(stringResource(R.string.restore_account), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.restoration_explanation))
                    }
                    PasswordForm(model, enabled, state.authFlow == AuthFlow.RESTORE)
                }
                if (state.public?.providers?.isNotEmpty() == true) Text(stringResource(R.string.providers_unavailable))
                if (state.authFlow != AuthFlow.SIGN_IN) TextButton(onClick = { model.selectForm(AuthFlow.SIGN_IN) }, enabled = !state.busy) {
                    Text(stringResource(R.string.back_to_sign_in))
                }
                if (state.authFlow == AuthFlow.SIGN_IN) {
                    TextButton(onClick = { model.selectForm(AuthFlow.RESTORE) }, enabled = !state.busy) { Text(stringResource(R.string.restore_account)) }
                    if (state.public?.registrationEnabled == true && !state.public.lockdown.enabled) TextButton(onClick = { model.selectForm(AuthFlow.REGISTER) }, enabled = !state.busy) {
                        Text(stringResource(R.string.create_account))
                    }
                }
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
                OutlinedButton(onClick = model::refresh, enabled = !state.busy && !waiting) { Text(stringResource(R.string.refresh_session)) }
                Button(onClick = model::signOut) { Text(stringResource(R.string.sign_out)) }
            }
            SessionPhase.GUEST -> {
                Text(stringResource(R.string.guest_detected))
                Button(onClick = model::signOut) { Text(stringResource(R.string.end_guest_visit)) }
            }
            SessionPhase.INVALIDATED -> {
                Text(stringResource(R.string.session_not_available), style = MaterialTheme.typography.titleLarge)
                Button(onClick = model::reconnect, enabled = !waiting) { Text(stringResource(R.string.reconnect)) }
                TextButton(onClick = model::signOut) { Text(stringResource(R.string.clear_local_session)) }
            }
            SessionPhase.SIGNED_OUT -> {
                Text(stringResource(if (state.storageWarning) R.string.sign_out_needs_attention else R.string.signed_out), style = MaterialTheme.typography.titleLarge)
                if (state.busy) Text(stringResource(R.string.confirming_logout))
                else if (state.logoutUnconfirmed && !state.storageWarning) Text(stringResource(R.string.logout_unconfirmed))
                Button(onClick = model::reconnect, enabled = !state.busy && !state.storageWarning && !waiting) {
                    Text(stringResource(R.string.back_to_sign_in))
                }
            }
        }
        if (state.busy && state.phase != SessionPhase.SIGNED_OUT) CircularProgressIndicator()
    }
}

@Composable
private fun PasswordForm(model: AuthViewModel, enabled: Boolean, restore: Boolean) {
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
    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        label = { Text(stringResource(R.string.username)) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next))
    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        label = { Text(stringResource(R.string.password)) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }))
    Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (restore) R.string.restore_and_sign_in else R.string.sign_in))
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
        keyboardActions = KeyboardActions(onDone = { submit() }))
    Button(onClick = submit, enabled = canSubmit) { Text(stringResource(R.string.use_recovery_code)) }
}

@Composable
private fun errorText(failure: ApiFailure): String = stringResource(when {
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
