package io.github.thiesi.chitchat.feature.account

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import kotlinx.coroutines.delay

@Composable
fun TypingPreferenceCard(model: TypingPreferenceViewModel, session: SessionState, onRefresh: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) model.cancel()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.retryAtMillis) {
        do { now = System.currentTimeMillis(); if (now < session.retryAtMillis) delay(1000) }
        while (now < session.retryAtMillis)
    }
    val enabled = session.phase == SessionPhase.AUTHENTICATED && session.user?.guest == false &&
        !session.busy && !session.refreshRequired && !session.storageWarning && !state.busy &&
        !state.unconfirmed && now >= session.retryAtMillis
    Card(Modifier.fillMaxWidth().testTag("typing_preference")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.typing_preference_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.typing_preference_scope))
            Text(stringResource(R.string.typing_native_pending))
            val shared = session.shareTyping
            if (shared != null) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = shared, enabled = enabled, role = Role.Switch,
                    onValueChange = model::change).testTag("typing_switch"),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.typing_share_label), Modifier.weight(1f))
                Switch(checked = shared, onCheckedChange = null, enabled = enabled)
            } else {
                Text(stringResource(R.string.typing_preference_unknown))
                Button(onClick = { model.change(false) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.typing_disable))
                }
                TextButton(onClick = { model.change(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.typing_enable))
                }
            }
            if (state.busy) { CircularProgressIndicator(); Text(stringResource(R.string.typing_saving)) }
            state.error?.let { InlineError(typingError(it)) }
            if (state.unconfirmed) Text(stringResource(R.string.typing_unconfirmed))
            if (now < session.retryAtMillis) Text(stringResource(R.string.rate_limited))
            TextButton(onClick = onRefresh, enabled = !state.busy && !session.busy && now >= session.retryAtMillis) {
                Text(stringResource(R.string.refresh_session))
            }
        }
    }
}

@Composable
private fun typingError(failure: ApiFailure) = stringResource(when {
    failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.TIMEOUT -> R.string.network_error
    failure.kind == FailureKind.TLS -> R.string.tls_error
    failure.kind == FailureKind.REDIRECT -> R.string.redirect_error
    failure.kind == FailureKind.INCOMPATIBLE -> R.string.incompatible_server
    failure.kind == FailureKind.EXPIRED || failure.status == 401 -> R.string.session_not_available
    failure.status == 429 -> R.string.rate_limited
    failure.status == 503 -> R.string.server_unavailable
    failure.code == "csrf_failed" || failure.kind == FailureKind.STORAGE -> R.string.session_refresh_needed
    else -> R.string.request_rejected
})
