package io.github.thiesi.chitchat.feature.rooms

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.rooms.RoomInfo
import io.github.thiesi.chitchat.core.rooms.canCreateRooms
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.InlineError
import kotlinx.coroutines.delay

@Composable
fun RoomsScreen(model: RoomsViewModel, onRefreshSession: () -> Unit, onOpenRoom: (Long) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) model.background()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session.retryAtMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { now = System.currentTimeMillis(); if (now < session.retryAtMillis) delay(1000) }
            while (now < session.retryAtMillis)
        }
    }
    val ready = session.phase == SessionPhase.AUTHENTICATED && !session.busy &&
        !session.refreshRequired && !session.storageWarning && now >= session.retryAtMillis
    val canMutate = ready && !state.busy && !state.stale && !state.mutationUncertain
    LazyColumn(Modifier.fillMaxWidth().testTag("rooms_directory"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rooms_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.rooms_slice_disclosure), style = MaterialTheme.typography.bodySmall)
                RoomSessionFeedback(session, now, onRefreshSession)
                if (!state.detailOpen && state.draft == null) RoomFeedback(state)
                if (state.stale && state.loaded) Text(stringResource(R.string.rooms_stale))
                if (state.syncing) Text(stringResource(R.string.rooms_syncing))
                if (state.busy) {
                    CircularProgressIndicator()
                    TextButton(onClick = model::cancelRequest) { Text(stringResource(R.string.cancel)) }
                }
                Button(onClick = model::refresh, enabled = ready && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.mutationUncertain) R.string.rooms_reconcile else R.string.rooms_refresh))
                }
                if (canCreateRooms(session.user)) Button(onClick = model::showCreate, enabled = canMutate) {
                    Text(stringResource(R.string.rooms_create))
                }
                if (state.loaded && state.rooms.isEmpty()) Text(stringResource(R.string.rooms_empty))
            }
        }
        items(state.rooms, key = { it.id }) { room ->
            Card(onClick = { model.select(room.id) }, enabled = ready && !state.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(when { room.isMember -> R.string.rooms_joined
                        room.invited -> R.string.rooms_invited; else -> R.string.rooms_discoverable }))
                    if (room.infoLine.isNotBlank()) Text(room.infoLine)
                    Text(stringResource(visibilityLabel(room.visibility)))
                    if (room.unreadCount > 0) Badge {
                        Text(stringResource(R.string.rooms_unread, if (room.unreadCount >= 100) "99+" else room.unreadCount.toString()))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rooms_presence_disclosure))
                OutlinedTextField(value = state.lookup, onValueChange = model::updateLookup,
                    label = { Text(stringResource(R.string.rooms_lookup_id)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.rooms_lookup_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::lookup, enabled = ready && !state.busy && state.lookup.isNotBlank()) {
                    Text(stringResource(R.string.rooms_lookup))
                }
            }
        }
    }
    if (state.draft != null) CreateRoomDialog(state, model, canMutate && canCreateRooms(session.user), canCreateRooms(session.user)) {
        RoomSessionFeedback(session, now, onRefreshSession)
    }
    else if (state.leaveConfirmation) AlertDialog(onDismissRequest = { model.confirmLeave(false) },
        title = { Text(stringResource(R.string.rooms_leave_title)) },
        text = { Text(stringResource(R.string.rooms_leave_warning)) },
        confirmButton = { TextButton(onClick = model::leave, enabled = canMutate) { Text(stringResource(R.string.rooms_leave)) } },
        dismissButton = { TextButton(onClick = { model.confirmLeave(false) }) { Text(stringResource(R.string.cancel)) } })
    else if (state.detailOpen) RoomDetailDialog(state, model, canMutate,
        session.user?.roles?.any { it in setOf("super_admin", "admin", "chat_admin", "global_moderator") } == true,
        onOpenRoom) {
        RoomSessionFeedback(session, now, onRefreshSession)
    }
}

@Composable
private fun RoomSessionFeedback(session: SessionState, now: Long, onRefresh: () -> Unit) {
    if (session.busy) Text(stringResource(R.string.checking_session))
    if (session.refreshRequired || session.storageWarning) {
        Text(stringResource(R.string.session_refresh_needed))
        TextButton(onClick = onRefresh, enabled = !session.busy && now >= session.retryAtMillis) {
            Text(stringResource(R.string.refresh_session))
        }
    }
    if (now < session.retryAtMillis) Text(stringResource(R.string.rate_limited))
}

@Composable
private fun RoomDetailDialog(state: RoomsUiState, model: RoomsViewModel, canMutate: Boolean,
    canReadPrivate: Boolean, onOpenRoom: (Long) -> Unit, sessionFeedback: @Composable () -> Unit) {
    val room = state.selected
    AlertDialog(onDismissRequest = model::closeDetail,
        title = { Text(room?.name ?: stringResource(R.string.rooms_details)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sessionFeedback()
            RoomFeedback(state)
            if (state.busy) {
                CircularProgressIndicator()
                TextButton(onClick = model::cancelRequest) { Text(stringResource(R.string.cancel)) }
            }
            if (room != null) {
                RoomMetadata(room)
                Button(onClick = { model.closeDetail(); onOpenRoom(room.id) }, enabled = !state.busy &&
                    (room.visibility != "private" || room.isMember || canReadPrivate)) {
                    Text(stringResource(R.string.rooms_open_conversation))
                }
                Text(stringResource(R.string.rooms_presence_disclosure))
                if (room.visibility == "private" && !room.isMember) Text(stringResource(R.string.rooms_private_invitation))
                if (room.memberRole == "owner") Text(stringResource(R.string.rooms_owner_immutable))
                else if (room.isMember) Button(onClick = { model.confirmLeave(true) }, enabled = canMutate) {
                    Text(stringResource(R.string.rooms_leave))
                }
                else Button(onClick = model::join, enabled = canMutate) { Text(stringResource(R.string.rooms_join)) }
            }
            if (state.stale) Text(stringResource(R.string.rooms_stale))
            if (state.stale || state.mutationUncertain) TextButton(onClick = model::refresh, enabled = !state.busy) {
                Text(stringResource(R.string.rooms_reconcile))
            }
        } },
        confirmButton = { TextButton(onClick = model::closeDetail, enabled = !state.busy) { Text(stringResource(R.string.rooms_close)) } })
}

@Composable
private fun RoomMetadata(room: RoomInfo) {
    Text(stringResource(R.string.rooms_identifier, room.id, room.key))
    if (room.infoLine.isNotBlank()) Text(room.infoLine)
    Text(stringResource(visibilityLabel(room.visibility)))
    Text(stringResource(when (room.visibility) { "private" -> R.string.rooms_private_help
        "unlisted" -> R.string.rooms_unlisted_help; else -> R.string.rooms_public_help }))
    Text(stringResource(R.string.rooms_age_value, room.minimumAge))
    Text(if (room.inactivityTimeoutSeconds == 0) stringResource(R.string.rooms_no_inactivity)
        else pluralStringResource(R.plurals.rooms_inactivity_value, room.inactivityTimeoutSeconds, room.inactivityTimeoutSeconds))
    Text(stringResource(R.string.rooms_guest_value, stringResource(guestLabel(room.guestAccess))))
    room.memberRole?.let { Text(stringResource(R.string.rooms_role_value, it)) }
}

@Composable
private fun CreateRoomDialog(state: RoomsUiState, model: RoomsViewModel, canMutate: Boolean, hasCreateRole: Boolean,
    sessionFeedback: @Composable () -> Unit) {
    val draft = state.draft ?: return
    AlertDialog(onDismissRequest = model::closeCreate,
        title = { Text(stringResource(R.string.rooms_create)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sessionFeedback()
            if (!hasCreateRole) InlineError(stringResource(R.string.rooms_forbidden))
            RoomFeedback(state)
            Text(stringResource(R.string.rooms_create_ownership))
            RoomField(draft.key, { model.updateDraft(draft.copy(key = it)) }, R.string.rooms_key, !state.busy)
            Text(stringResource(R.string.rooms_key_help), style = MaterialTheme.typography.bodySmall)
            RoomField(draft.name, { model.updateDraft(draft.copy(name = it)) }, R.string.rooms_name, !state.busy)
            RoomField(draft.info, { model.updateDraft(draft.copy(info = it)) }, R.string.rooms_info, !state.busy, multiline = true)
            RoomChoices(R.string.rooms_visibility, listOf("public", "unlisted", "private"), draft.visibility,
                !state.busy, ::visibilityLabel) { model.updateDraft(draft.copy(visibility = it)) }
            Text(stringResource(R.string.rooms_visibility_help))
            RoomField(draft.minimumAge, { model.updateDraft(draft.copy(minimumAge = it)) }, R.string.rooms_minimum_age,
                !state.busy, numeric = true)
            RoomField(draft.inactivitySeconds, { model.updateDraft(draft.copy(inactivitySeconds = it)) }, R.string.rooms_inactivity,
                !state.busy, numeric = true)
            Text(stringResource(R.string.rooms_inactivity_help))
            RoomChoices(R.string.rooms_guest_access, listOf("none", "read", "write"), draft.guestAccess,
                !state.busy, ::guestLabel) { model.updateDraft(draft.copy(guestAccess = it)) }
            Text(stringResource(R.string.rooms_guest_help))
            if (state.busy) {
                CircularProgressIndicator()
                TextButton(onClick = model::cancelRequest) { Text(stringResource(R.string.cancel)) }
            }
            if (state.stale || state.mutationUncertain) TextButton(onClick = model::refresh, enabled = !state.busy) {
                Text(stringResource(R.string.rooms_reconcile))
            }
        } },
        confirmButton = { TextButton(onClick = model::create, enabled = canMutate) { Text(stringResource(R.string.rooms_create_submit)) } },
        dismissButton = { TextButton(onClick = model::closeCreate, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun RoomField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean,
    numeric: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 2 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
}

@Composable
private fun RoomChoices(title: Int, choices: List<String>, selected: String, enabled: Boolean,
    label: (String) -> Int, onSelect: (String) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
    choices.forEach { value ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected == value, enabled = enabled,
            role = Role.RadioButton, onClick = { onSelect(value) }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == value, onClick = null, enabled = enabled)
            Text(stringResource(label(value)), Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun RoomFeedback(state: RoomsUiState) {
    state.validation?.let { InlineError(roomErrorCode(it)) }
    state.error?.let { InlineError(roomError(it)) }
    state.syncError?.let { InlineError(roomError(it)) }
    if (state.mutationUncertain) Text(stringResource(R.string.rooms_unconfirmed))
    state.notice?.let { Text(stringResource(when (it) { "created" -> R.string.rooms_created
        "joined" -> R.string.rooms_join_success; "left" -> R.string.rooms_leave_success
        "cancelled" -> R.string.rooms_cancelled; else -> R.string.rooms_unconfirmed })) }
}

private fun visibilityLabel(value: String) = when (value) { "private" -> R.string.rooms_private
    "unlisted" -> R.string.rooms_unlisted; else -> R.string.rooms_public }
private fun guestLabel(value: String) = when (value) { "read" -> R.string.rooms_guest_read
    "write" -> R.string.rooms_guest_write; else -> R.string.rooms_guest_none }

@Composable
private fun roomError(failure: ApiFailure): String = when {
    failure.kind == FailureKind.TLS -> stringResource(R.string.tls_error)
    failure.kind == FailureKind.REDIRECT -> stringResource(R.string.redirect_error)
    failure.kind == FailureKind.INCOMPATIBLE || failure.kind == FailureKind.TOO_LARGE -> stringResource(R.string.incompatible_server)
    failure.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT) -> stringResource(R.string.rooms_network)
    failure.kind == FailureKind.EXPIRED || failure.status == 401 -> stringResource(R.string.session_not_available)
    failure.status == 429 -> stringResource(R.string.rate_limited)
    failure.status == 503 -> stringResource(R.string.server_unavailable)
    failure.code == "csrf_failed" || failure.kind == FailureKind.STORAGE -> stringResource(R.string.session_refresh_needed)
    else -> roomErrorCode(failure.code)
}

@Composable
private fun roomErrorCode(code: String?) = stringResource(when (code) {
    "invalid_room_id", "validation_error" -> R.string.rooms_invalid_id
    "room_not_found", "room_forbidden" -> R.string.rooms_unavailable
    "invitation_required" -> R.string.rooms_invitation_required
    "birth_date_required" -> R.string.rooms_birth_date_required
    "minimum_age_not_met" -> R.string.rooms_minimum_age_not_met
    "owner_cannot_leave", "owner_role_immutable" -> R.string.rooms_owner_immutable
    "not_a_member" -> R.string.rooms_not_a_member
    "forbidden", "guest_not_allowed" -> R.string.rooms_forbidden
    "room_key_taken" -> R.string.rooms_key_taken
    "invalid_room_key" -> R.string.rooms_invalid_key
    "invalid_room_name" -> R.string.rooms_invalid_name
    "invalid_room_info" -> R.string.rooms_invalid_info
    "invalid_visibility" -> R.string.rooms_invalid_visibility
    "invalid_minimum_age" -> R.string.rooms_invalid_age
    "invalid_inactivity_timeout" -> R.string.rooms_invalid_inactivity
    "invalid_guest_access", "guest_access_scope" -> R.string.rooms_guest_help
    "invalid_room_numbers" -> R.string.rooms_invalid_numbers
    else -> R.string.request_rejected
})
