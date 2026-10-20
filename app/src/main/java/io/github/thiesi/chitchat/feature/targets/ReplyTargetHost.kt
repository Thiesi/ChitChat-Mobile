package io.github.thiesi.chitchat.feature.targets

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.core.targets.TargetSnapshot
import io.github.thiesi.chitchat.core.targets.TargetRequest
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import kotlinx.coroutines.delay

/** One root owner: old pane disposal cannot cancel a successor's GET or lifecycle. */
@Composable
internal fun ReplyTargetHost(model: MessageTargetViewModel, value: MessageTargetState, session: SessionState,
    conversations: ConversationViewModel, direct: DirectThreadViewModel?, navigationReady: Boolean,
    roomId: Long?, peerId: Long?, onAccept: (TargetSnapshot) -> Boolean,
    onDenied: (TargetRequest, ApiFailure) -> Unit, refreshSession: () -> Unit) {
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) model.background() }
    val request = value.request ?: return
    val inPane = when (val target = request.target) {
        is MessageTarget.RoomMessage -> target.roomId == roomId
        is MessageTarget.DirectMessage -> target.peerId == peerId
        is MessageTarget.RoomPing -> false // Not a reply; the notification/ping consumer is separate.
    }
    LaunchedEffect(request, navigationReady, inPane) {
        if (navigationReady && !inPane) model.cancel(request)
    }
    if (!request.matches(session) || !inPane) return
    LaunchedEffect(request, value.error) {
        if (model.state.value.request === request && request.matches(model.session.value))
            value.error?.let { onDenied(request, it) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.retryAtMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { now = System.currentTimeMillis(); if (now < session.retryAtMillis) delay(1000) }
            while (now < session.retryAtMillis)
        }
    }
    val room by conversations.state.collectAsStateWithLifecycle()
    val thread = direct?.state?.collectAsStateWithLifecycle()?.value
    val result = value.result
    val canAdopt = when (result) {
        is TargetSnapshot.RoomMessage -> !room.busy && !room.reading && conversations.canAdoptTarget(result)
        is TargetSnapshot.DirectMessage -> thread?.busy == false && direct.canAdoptTarget(result)
        else -> false
    }
    val ready = !session.busy && !session.refreshRequired && !session.storageWarning && now >= session.retryAtMillis
    val available = !value.loading && !value.stale && result?.available == true
    LaunchedEffect(request, result, available, canAdopt, ready) {
        if (available && canAdopt && ready) model.adopt(request, onAccept)
    }
    AlertDialog(onDismissRequest = { model.cancel(request) }, modifier = Modifier.testTag("message_target_dialog"),
        title = { Text(stringResource(R.string.message_target_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (value.loading) CircularProgressIndicator()
            val label = when {
                now < session.retryAtMillis -> R.string.message_target_rate
                !ready && !session.busy -> R.string.message_target_session
                value.loading -> R.string.message_target_loading
                value.error?.status in setOf(403, 404) && value.error?.code != "csrf_failed" -> R.string.message_target_unavailable
                value.error?.kind == FailureKind.INCOMPATIBLE -> R.string.message_target_incompatible
                value.error != null -> R.string.message_target_error
                result?.available == false -> R.string.message_target_unavailable
                else -> R.string.message_target_waiting
            }
            Text(stringResource(label))
        } },
        confirmButton = {
            if (session.refreshRequired || session.storageWarning) TextButton(onClick = {
                if (request.matches(model.session.value)) refreshSession()
            }, enabled = !session.busy) { Text(stringResource(R.string.refresh_session)) }
            else if (!value.loading && (!available || value.error != null)) TextButton(onClick = { model.retry(request) },
                enabled = ready) { Text(stringResource(R.string.retry)) }
            else if (available) TextButton(onClick = { model.adopt(request, onAccept) }, enabled = ready && canAdopt) {
                Text(stringResource(R.string.message_target_open_quote))
            }
        },
        dismissButton = { TextButton(onClick = { model.cancel(request) }) { Text(stringResource(R.string.cancel)) } })
}
