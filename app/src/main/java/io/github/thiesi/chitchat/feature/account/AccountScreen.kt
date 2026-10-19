package io.github.thiesi.chitchat.feature.account

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.feature.auth.StepUpDialog
import kotlinx.coroutines.delay

@Composable
fun AccountScreen(model: AccountViewModel, session: SessionState, onRefresh: () -> Unit) {
    // Retire the system picker registration too, not only its network action, on account changes.
    key(session.generation, session.user?.id, session.user?.sessionVersion) { AccountContent(model, session, onRefresh) }
}

@Composable
private fun AccountContent(model: AccountViewModel, session: SessionState, onRefresh: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.applicationContext.contentResolver
    var pickerMissing by remember { mutableStateOf(false) }
    val pickerGeneration = remember { session.generation }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        model.destinationSelected(uri?.let { DocumentExportDestination(resolver, it) }, pickerGeneration)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.background() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.retryAtMillis) {
        do { now = System.currentTimeMillis(); if (now < session.retryAtMillis) delay(1000) }
        while (now < session.retryAtMillis)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.personal_data_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.export_scope))
        Text(stringResource(R.string.export_privacy))
        Text(stringResource(R.string.export_partial_warning))
        if (pickerMissing) InlineError(stringResource(R.string.export_picker_missing))
        if (state.stage != ExportStage.VERIFYING) state.error?.let { InlineError(accountError(it)) }
        if (state.recoveryMayBeSpent) Text(stringResource(R.string.recovery_may_be_spent))
        if (session.refreshRequired || session.storageWarning) {
            Text(stringResource(R.string.session_refresh_needed))
            TextButton(onClick = onRefresh, enabled = !session.busy && now >= session.retryAtMillis) {
                Text(stringResource(R.string.refresh_session))
            }
        }
        if (now < session.retryAtMillis) Text(stringResource(R.string.rate_limited))
        when (state.stage) {
            ExportStage.CHOOSING -> Text(stringResource(R.string.export_choosing))
            ExportStage.RUNNING -> { CircularProgressIndicator(); Text(stringResource(R.string.export_preparing)) }
            ExportStage.SAVED -> Text(stringResource(R.string.export_saved))
            ExportStage.CANCELLED -> Text(stringResource(R.string.export_cancelled))
            else -> Unit
        }
        state.remainingCodes?.let { Text(stringResource(R.string.recovery_remaining, it)) }
        Button(onClick = {
            pickerMissing = false
            if (model.chooseDestination()) {
                try { picker.launch("chitchat-personal-data.json") }
                catch (_: ActivityNotFoundException) { model.cancel(); pickerMissing = true }
            }
        }, enabled = session.phase == SessionPhase.AUTHENTICATED && !session.busy && !session.refreshRequired && !session.storageWarning && now >= session.retryAtMillis &&
            state.stage !in setOf(ExportStage.CHOOSING, ExportStage.RUNNING, ExportStage.VERIFYING), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_personal_data))
        }
        if (state.stage in setOf(ExportStage.CHOOSING, ExportStage.RUNNING)) TextButton(onClick = model::cancel) {
            Text(stringResource(R.string.cancel))
        }
    }
    val methods = state.methods
    if (state.stage == ExportStage.VERIFYING && methods != null) StepUpDialog(
        title = stringResource(R.string.step_up_title), methods = methods, busy = state.verificationBusy,
        errorMessage = state.error?.let { accountError(it) }, onVerify = model::verify, onCancel = model::cancel)
}

@Composable
private fun accountError(failure: ApiFailure) = stringResource(when {
    failure.kind == FailureKind.STORAGE -> R.string.export_save_failed
    failure.kind == FailureKind.TOO_LARGE -> R.string.export_too_large
    failure.kind == FailureKind.EXPIRED || failure.status == 401 -> R.string.session_not_available
    failure.kind == FailureKind.TLS -> R.string.tls_error
    failure.kind == FailureKind.REDIRECT -> R.string.redirect_error
    failure.kind == FailureKind.INCOMPATIBLE -> R.string.incompatible_server
    failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.TIMEOUT -> R.string.export_network_failed
    failure.status == 429 -> R.string.rate_limited
    failure.status == 503 -> R.string.server_unavailable
    failure.code == "step_up_invalid_credentials" -> R.string.step_up_wrong_password
    failure.code == "invalid_recovery_code" -> R.string.invalid_recovery_code
    failure.code == "step_up_required" -> R.string.step_up_retry_rejected
    failure.code == "csrf_failed" -> R.string.session_refresh_needed
    else -> R.string.request_rejected
})
