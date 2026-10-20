package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.PresencePhase
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.MessageInput
import io.github.thiesi.chitchat.core.ui.canAcknowledgeVisibleRead
import io.github.thiesi.chitchat.core.ui.isReadEdgeVisible
import kotlinx.coroutines.delay

@Composable
fun ConversationScreen(model: ConversationViewModel, roomId: Long, onRefreshSession: () -> Unit) {
    val session by model.session.collectAsStateWithLifecycle()
    key(roomId, session.server, session.generation, session.user?.id, session.user?.sessionVersion) {
        ConversationContent(model, roomId, onRefreshSession)
    }
}

@Composable
private fun ConversationContent(model: ConversationViewModel, roomId: Long, onRefreshSession: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val connection = model.realtime?.state?.collectAsStateWithLifecycle()?.value
    val presenceHere = connection?.takeIf { it.generation == session.generation && it.selectedRoomId == roomId }
    val activePresence = model.realtime == null || presenceHere?.presencePhase in setOf(PresencePhase.ACTIVE, PresencePhase.WARNING)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val window = LocalWindowInfo.current
    var pingsOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val timeline = rememberLazyListState()
    LaunchedEffect(roomId) { model.open(roomId) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) model.background() }
    LaunchedEffect(session.retryAtMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { now = System.currentTimeMillis(); if (now < session.retryAtMillis) delay(1000) }
            while (now < session.retryAtMillis)
        }
    }
    LaunchedEffect(state.scrollRequest) {
        if (state.scrollRequest > 0 && state.messages.isNotEmpty()) {
            // A real end item lets LazyColumn measure/clamp the bottom, including a newest
            // message taller than the viewport. Do not calculate overflow from old layoutInfo.
            timeline.scrollToItem(state.messages.size + 1)
        }
    }
    LaunchedEffect(state.anchor) {
        state.anchor?.let { anchor ->
            val index = state.messages.indexOfFirst { it.id == anchor.id }
            if (index >= 0) timeline.scrollToItem(index + 1, anchor.offset)
        }
    }
    LaunchedEffect(state.messages, state.busy, state.stale, state.latestWindow, pingsOpen, detailsOpen, lifecycle,
        connection?.presencePhase, connection?.selectedRoomId, lifecycleState) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow {
                val visible = timeline.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull { (it.key as? String)?.startsWith("message:") == true }
                val anchor = first?.let { TimelineAnchor((it.key as String).substringAfter(':').toLong(), -it.offset) }
                val newest = state.messages.lastOrNull()?.id
                val last = visible.firstOrNull { it.key == "message:$newest" }
                val edgeVisible = last != null && isReadEdgeVisible(last.offset, last.size,
                    timeline.layoutInfo.viewportStartOffset, timeline.layoutInfo.viewportEndOffset)
                val readable = canAcknowledgeVisibleRead(lifecycleState, window.isWindowFocused, !pingsOpen && !detailsOpen)
                Triple(anchor, if (edgeVisible && readable) newest else null, !timeline.canScrollForward && readable)
            }.collect { (anchor, newest, edge) -> model.viewport(anchor, newest, edge) }
        }
    }
    val ready = !session.busy && !session.refreshRequired && !session.storageWarning && now >= session.retryAtMillis
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val compact = maxHeight < 240.dp
    val headerLimit = maxHeight * 0.3f
    val composerLimit = (maxHeight * 0.45f).coerceAtLeast(64.dp)
    val header: @Composable (() -> Unit) -> Unit = { close ->
            Text(state.context?.room?.name ?: stringResource(R.string.conversation_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (model.realtime == null) R.string.conversation_manual else R.string.conversation_realtime),
                style = MaterialTheme.typography.bodySmall)
            if (model.realtime != null && state.loaded) RoomPresencePanel(
                presenceHere,
                state.context?.let(model::presenceEligible) == true,
                canEnter = ready && !state.busy && !state.stale, enter = { close(); model.enterRoom() })
            if (state.syncing) Text(stringResource(R.string.conversation_syncing))
            if (state.anchorUnavailable) Text(stringResource(R.string.conversation_anchor_unavailable))
            if (state.newerAvailable) TextButton(onClick = { close(); model.latest() }, enabled = ready && !state.busy) {
                Text(stringResource(R.string.conversation_jump_new))
            }
            if (state.stale && state.loaded) Text(stringResource(R.string.conversation_stale))
            if (session.busy) Text(stringResource(R.string.checking_session))
            if (session.refreshRequired || session.storageWarning) {
                TextButton(onClick = { close(); onRefreshSession() }, enabled = !session.busy && now >= session.retryAtMillis) {
                    Text(stringResource(R.string.refresh_session))
                }
            }
            if (now < session.retryAtMillis) Text(stringResource(R.string.rate_limited))
            state.error?.let { InlineError(conversationError(it)) }
            state.syncError?.let {
                InlineError(if (it.code == "history_range_unavailable") stringResource(R.string.conversation_range_unavailable)
                    else conversationError(it))
                TextButton(onClick = { close(); model.latest() }, enabled = ready && !state.busy) { Text(stringResource(R.string.conversation_latest)) }
            }
            state.readError?.let {
                Text(stringResource(R.string.conversation_read_failed))
                TextButton(onClick = { close(); model.retryRead() }, enabled = ready && !state.busy && state.latestWindow) { Text(stringResource(R.string.conversation_retry_read)) }
            }
            state.validation?.let { InlineError(validationMessage(it)) }
            state.notice?.let { notice -> Text(stringResource(when (notice) {
                "ping_sent" -> R.string.conversation_ping_sent; "sent" -> R.string.conversation_sent
                "window" -> R.string.conversation_window; "duplicate_risk" -> R.string.conversation_duplicate_choice
                "latest_reset" -> R.string.conversation_latest_reset
                else -> R.string.conversation_cancelled
            }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (!state.atLatest || !state.latestWindow) TextButton(onClick = { close(); model.latest() }, enabled = ready && !state.busy) {
                Text(stringResource(R.string.conversation_latest))
            }
            if (state.uncertainSend) {
                Text(stringResource(R.string.conversation_uncertain))
                if (state.reconciledAfterUncertain) TextButton(onClick = { close(); model.acknowledgeDuplicateRisk() }, enabled = !state.busy) {
                    Text(stringResource(R.string.conversation_allow_resend))
                }
            }
            state.context?.let { context ->
                when {
                    context.mute != null -> Text(stringResource(R.string.conversation_muted, context.mute!!.reason,
                        context.mute!!.expiresAt?.let { displayTime(it, context) } ?: stringResource(R.string.conversation_indefinite)))
                    !context.room.isMember -> Text(stringResource(R.string.conversation_membership))
                    session.user?.guest == true && context.room.guestAccess != "write" -> Text(stringResource(R.string.conversation_guest_readonly))
                }
                if (model.realtime != null && model.presenceEligible(context) && !activePresence) {
                    TextButton(onClick = { close(); model.enterRoom() }, enabled = ready && state.loaded && !state.busy && !state.stale &&
                        presenceHere?.presencePhase != PresencePhase.CONNECTING) {
                        Text(stringResource(if (presenceHere?.presencePhase == PresencePhase.CONNECTING)
                            R.string.presence_entering else R.string.presence_enter))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { close(); model.refresh() }, enabled = ready && !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.conversation_refresh)) }
                TextButton(onClick = { close(); pingsOpen = true }, enabled = state.loaded, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.conversation_pings)) }
                if (state.busy || state.syncing) TextButton(onClick = { close(); model.cancelRequest() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
            }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            header {}
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversation_timeline"), state = timeline,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "history_header") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (state.busy && !state.sending) CircularProgressIndicator()
                    if (state.loaded && state.messages.isEmpty()) Text(stringResource(R.string.conversation_empty))
                    if (state.mayHaveOlder) TextButton(onClick = model::older, enabled = !state.busy && !state.stale && !state.uncertainSend) {
                        Text(stringResource(R.string.conversation_older))
                    }
                }
            }
            items(state.messages, key = { "message:${it.id}" }) { message ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("message:${message.id}")) {
                    if (state.unreadDividerId == message.id) {
                        HorizontalDivider(); Text(stringResource(R.string.conversation_unread), style = MaterialTheme.typography.labelMedium)
                    }
                    val ignored = message.senderId in (state.context?.policy?.ignoredUserIds ?: emptySet())
                    if (ignored && message.id !in state.revealed) TextButton(onClick = { model.reveal(message.id) }) {
                        Text(stringResource(R.string.conversation_ignored))
                    } else MessageContent(message, state.context)
                }
            }
            item(key = "history_end") { Spacer(Modifier.height(1.dp)) }
        }
        Row(Modifier.fillMaxWidth().heightIn(max = composerLimit).padding(horizontal = if (compact) 4.dp else 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            run {
                val label = stringResource(R.string.conversation_details)
                val blocked = !ready || state.busy || !model.canSend()
                val status = stringResource(if (blocked) R.string.conversation_send_unavailable_details else R.string.conversation_details_attention)
                val attention = state.busy || state.syncing || state.stale || state.newerAvailable || state.uncertainSend ||
                    state.error != null || state.syncError != null || state.readError != null || state.validation != null ||
                    state.notice != null || blocked
                BadgedBox(badge = { if (attention) Badge() }) {
                    IconButton(onClick = { detailsOpen = true }, modifier = Modifier.semantics {
                        contentDescription = label
                        if (attention) stateDescription = status
                    }) { Text("\u22ee") }
                }
            }
            MessageInput(state.draft, model::updateDraft, !state.sending,
                stringResource(R.string.conversation_message), compact,
                Modifier.weight(1f).testTag("conversation_composer"))
            Button(onClick = model::send, enabled = ready && !state.busy && model.canSend() && state.draft.isNotBlank(),
                modifier = Modifier.testTag("conversation_send")) { Text(stringResource(if (state.sending) R.string.conversation_sending else R.string.conversation_send)) }
        }
    }
    if (detailsOpen) AlertDialog(onDismissRequest = { detailsOpen = false },
        title = { Text(stringResource(R.string.conversation_details)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { header { detailsOpen = false } } },
        confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text(stringResource(R.string.rooms_close)) } })
    }
    if (pingsOpen) AlertDialog(onDismissRequest = { pingsOpen = false },
        title = { Text(stringResource(R.string.conversation_pings)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.conversation_pings_private))
            if (state.pings.isEmpty()) Text(stringResource(R.string.conversation_no_pings))
            state.pings.forEach { ping ->
                Text(stringResource(R.string.conversation_ping_people, ping.sender.username ?: stringResource(R.string.conversation_unknown_user),
                    ping.target.username ?: stringResource(R.string.conversation_unknown_user)))
                Text(ping.message); Text(displayTime(ping.createdAt, state.context), style = MaterialTheme.typography.labelSmall)
            }
        } }, confirmButton = { TextButton(onClick = { pingsOpen = false }) { Text(stringResource(R.string.rooms_close)) } })
}

@Composable
private fun MessageContent(message: RoomMessage, context: ConversationContext?) {
    val sender = message.username ?: stringResource(R.string.conversation_unknown_user)
    Text(stringResource(R.string.conversation_author_time, sender, displayTime(message.createdAt, context)), style = MaterialTheme.typography.labelMedium)
    when {
        message.deleted -> Text(stringResource(R.string.conversation_deleted), style = MaterialTheme.typography.bodyMedium)
        else -> {
            message.replyTo?.let { reply ->
                val original = reply.message
                Text(if (!reply.available || original == null || original.deleted)
                stringResource(R.string.conversation_reply_unavailable)
            else stringResource(R.string.conversation_reply, original.sender?.username ?: stringResource(R.string.conversation_unknown_user),
                original.body.orEmpty().take(160)), style = MaterialTheme.typography.bodySmall) }
            if (message.type == "emote") Text(stringResource(R.string.conversation_emote, sender, message.body.orEmpty()))
            else message.body?.let { Text(it) }
            message.attachment?.let { Text(stringResource(R.string.conversation_attachment, it.name, it.mimeType, it.sizeBytes.toString())) }
            if (message.editedAt != null) Text(stringResource(R.string.conversation_edited), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (message.reactions.isNotEmpty()) Text(message.reactions.joinToString("  ") { "${it.emoji} ${it.users.size}" })
}

@Composable
private fun displayTime(value: String, context: ConversationContext?) =
    io.github.thiesi.chitchat.core.ui.messageTime(value, context?.policy?.display)

@Composable
private fun conversationError(error: ApiFailure) = stringResource(when {
    error.code == "ping_target_not_found" -> R.string.conversation_ping_target
    error.code == "cannot_ping_self" -> R.string.conversation_ping_self
    error.code == "guest_not_allowed" -> R.string.conversation_guest_ping
    error.code == "membership_required" -> R.string.conversation_membership
    error.code == "guest_read_only" -> R.string.conversation_guest_readonly
    error.status in setOf(403, 404) && error.code != "csrf_failed" -> R.string.conversation_unavailable
    error.status == 429 -> R.string.rate_limited
    error.kind == FailureKind.TLS -> R.string.tls_error
    error.kind == FailureKind.REDIRECT -> R.string.redirect_error
    error.kind in setOf(FailureKind.INCOMPATIBLE, FailureKind.TOO_LARGE) -> R.string.incompatible_server
    error.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT) -> R.string.conversation_network
    error.kind == FailureKind.EXPIRED || error.status == 401 -> R.string.session_expired
    error.code == "csrf_failed" || error.kind == FailureKind.STORAGE -> R.string.session_refresh_needed
    error.status == 503 -> R.string.server_unavailable
    else -> R.string.request_rejected
})

@Composable
private fun validationMessage(code: String) = stringResource(when (code) {
    "empty_message" -> R.string.conversation_empty_message
    "message_too_long" -> R.string.conversation_too_long
    "ping_too_long" -> R.string.conversation_ping_long
    "invalid_ping_command" -> R.string.conversation_ping_help
    else -> R.string.conversation_commands
})
