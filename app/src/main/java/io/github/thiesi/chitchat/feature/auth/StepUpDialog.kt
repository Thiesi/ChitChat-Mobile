package io.github.thiesi.chitchat.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.account.VerificationMethods
import io.github.thiesi.chitchat.core.ui.InlineError

/** Reusable sensitive-action prompt; credentials stay inside its non-saveable input/request. */
@Composable
internal fun StepUpDialog(title: String, methods: VerificationMethods, busy: Boolean, errorMessage: String?,
    onVerify: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    var recoveryChosen by remember { mutableStateOf(false) }
    var secret by remember(methods.mfaEnabled, methods.hasPassword) { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { secret = "" }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val recovery = methods.mfaEnabled && recoveryChosen && methods.recoveryCodesRemaining > 0
    val canEnter = recovery || (!methods.mfaEnabled && methods.hasPassword)
    val filled = if (recovery) secret.isNotBlank() else secret.isNotEmpty()
    val submit = {
        if (canEnter && !busy && filled) {
            val entered = secret
            secret = ""; focus.clearFocus(); keyboard?.hide()
            onVerify(entered, recovery)
        }
    }
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (methods.mfaEnabled) {
                    Text(stringResource(R.string.step_up_passkey_unavailable))
                    Text(stringResource(R.string.recovery_remaining, methods.recoveryCodesRemaining))
                    if (methods.recoveryCodesRemaining > 0 && !recoveryChosen) TextButton(onClick = { recoveryChosen = true }) {
                        Text(stringResource(R.string.choose_emergency_recovery))
                    }
                    if (recovery) Text(stringResource(R.string.step_up_recovery_warning))
                } else if (methods.hasPassword) Text(stringResource(R.string.step_up_password_explanation))
                else Text(stringResource(R.string.step_up_provider_unavailable))
                errorMessage?.let { InlineError(it) }
                if (canEnter) OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth().semantics {
                    if (!recovery) contentType = ContentType.Password
                }, label = { Text(stringResource(if (recovery) R.string.recovery_code else R.string.current_password)) },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = { if (canEnter) TextButton(onClick = submit, enabled = !busy && filled) {
            Text(stringResource(R.string.verify_and_continue))
        } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
