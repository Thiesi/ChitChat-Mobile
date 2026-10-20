package io.github.thiesi.chitchat.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.InlineError

@Composable
internal fun IgnorePersonDialog(model: IgnorePersonViewModel, state: IgnorePersonState,
    session: SessionState, roomId: Long, now: Long, onRefresh: () -> Unit) {
    val target = state.target?.takeIf { it.roomId == roomId && it.matches(session) } ?: return
    val ignored = session.chatPolicy?.ignoredUserIds?.contains(target.userId)
    val ready = !session.busy && !session.refreshRequired && !session.storageWarning &&
        !state.busy && !state.unconfirmed && now >= session.retryAtMillis && ignored != null
    AlertDialog(onDismissRequest = { model.close(target) }, modifier = Modifier.testTag("room_person_dialog"),
        title = { Text(target.username ?: stringResource(R.string.conversation_unknown_user)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ignore_scope))
            Text(stringResource(when (ignored) {
                true -> R.string.ignore_confirmed; false -> R.string.ignore_not_ignored; null -> R.string.ignore_unknown
            }))
            if (state.busy) { CircularProgressIndicator(); Text(stringResource(R.string.ignore_saving)) }
            state.error?.let { InlineError(ignoreError(it)) }
            if (state.unconfirmed || session.refreshRequired) Text(stringResource(R.string.ignore_unconfirmed))
            if (now < session.retryAtMillis) Text(stringResource(R.string.rate_limited))
            if (ignored != null) TextButton(onClick = { model.change(target, !ignored) }, enabled = ready,
                modifier = Modifier.testTag("room_ignore_change")) {
                Text(stringResource(if (ignored) R.string.ignore_stop else R.string.ignore_start))
            }
            TextButton(onClick = onRefresh, enabled = !state.busy && !session.busy && now >= session.retryAtMillis) {
                Text(stringResource(R.string.refresh_session))
            }
            if (state.busy) Text(stringResource(R.string.ignore_cancel_warning))
        } },
        confirmButton = { TextButton(onClick = { model.close(target) }) {
            Text(stringResource(if (state.busy) R.string.cancel else R.string.rooms_close))
        } })
}

@Composable
private fun ignoreError(failure: ApiFailure) = stringResource(when {
    failure.code == "user_not_found" || failure.status == 404 -> R.string.ignore_person_unavailable
    failure.kind == FailureKind.NETWORK || failure.kind == FailureKind.TIMEOUT -> R.string.network_error
    failure.kind == FailureKind.TLS -> R.string.tls_error
    failure.status == 429 -> R.string.rate_limited
    failure.kind == FailureKind.INCOMPATIBLE || failure.kind == FailureKind.TOO_LARGE -> R.string.incompatible_server
    failure.kind == FailureKind.EXPIRED || failure.status == 401 -> R.string.session_expired
    failure.code == "csrf_failed" || failure.kind == FailureKind.STORAGE -> R.string.session_refresh_needed
    else -> R.string.request_rejected
})
