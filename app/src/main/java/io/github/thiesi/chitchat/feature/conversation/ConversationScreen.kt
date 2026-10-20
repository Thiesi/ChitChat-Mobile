package io.github.thiesi.chitchat.feature.conversation

import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.conversation.serverInstant
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.PresencePhase
import io.github.thiesi.chitchat.core.ui.InlineError
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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
    var pingsOpen by remember { mutableStateOf(false) }
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
            timeline.scrollToItem(state.messages.lastIndex + 1)
            timeline.layoutInfo.visibleItemsInfo.lastOrNull()?.let { last ->
                timeline.scrollBy((last.offset + last.size - timeline.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
            }
        }
    }
    LaunchedEffect(state.anchor) {
        state.anchor?.let { anchor ->
            val index = state.messages.indexOfFirst { it.id == anchor.id }
            if (index >= 0) timeline.scrollToItem(index + 1, anchor.offset)
        }
    }
    LaunchedEffect(state.messages, state.busy, state.stale, state.latestWindow, pingsOpen, lifecycle,
        connection?.presencePhase, connection?.selectedRoomId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow {
                val visible = timeline.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull { (it.key as? String)?.startsWith("message:") == true }
                val anchor = first?.let { TimelineAnchor((it.key as String).substringAfter(':').toLong(), -it.offset) }
                val newest = state.messages.lastOrNull()?.id
                val last = visible.firstOrNull { it.key == "message:$newest" }
                val fullyVisible = last != null && last.offset < timeline.layoutInfo.viewportEndOffset &&
                    last.offset + last.size <= timeline.layoutInfo.viewportEndOffset
                Triple(anchor, if (fullyVisible && !pingsOpen) newest else null, !timeline.canScrollForward && !pingsOpen)
            }.collect { (anchor, newest, edge) -> model.viewport(anchor, newest, edge) }
        }
    }
    val ready = !session.busy && !session.refreshRequired && !session.storageWarning && now >= session.retryAtMillis
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val headerLimit = maxHeight * 0.3f
    val composerLimit = maxHeight * 0.45f
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(state.context?.room?.name ?: stringResource(R.string.conversation_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (model.realtime == null) R.string.conversation_manual else R.string.conversation_realtime),
                style = MaterialTheme.typography.bodySmall)
            if (model.realtime != null && state.loaded) RoomPresencePanel(
                presenceHere,
                state.context?.let(model::presenceEligible) == true,
                canEnter = ready && !state.busy && !state.stale, enter = model::enterRoom)
            if (state.syncing) Text(stringResource(R.string.conversation_syncing))
            if (state.anchorUnavailable) Text(stringResource(R.string.conversation_anchor_unavailable))
            if (state.newerAvailable) TextButton(onClick = model::latest, enabled = ready && !state.busy) {
                Text(stringResource(R.string.conversation_jump_new))
            }
            if (state.stale && state.loaded) Text(stringResource(R.string.conversation_stale))
            if (session.busy) Text(stringResource(R.string.checking_session))
            if (session.refreshRequired || session.storageWarning) {
                TextButton(onClick = onRefreshSession, enabled = !session.busy && now >= session.retryAtMillis) {
                    Text(stringResource(R.string.refresh_session))
                }
            }
            if (now < session.retryAtMillis) Text(stringResource(R.string.rate_limited))
            state.error?.let { InlineError(conversationError(it)) }
            state.syncError?.let {
                InlineError(if (it.code == "history_range_unavailable") stringResource(R.string.conversation_range_unavailable)
                    else conversationError(it))
                TextButton(onClick = model::latest, enabled = ready && !state.busy) { Text(stringResource(R.string.conversation_latest)) }
            }
            state.readError?.let {
                Text(stringResource(R.string.conversation_read_failed))
                TextButton(onClick = model::retryRead, enabled = ready && !state.busy && state.atLatest) { Text(stringResource(R.string.conversation_retry_read)) }
            }
            state.validation?.let { InlineError(validationMessage(it)) }
            state.notice?.let { notice -> Text(stringResource(when (notice) {
                "ping_sent" -> R.string.conversation_ping_sent; "sent" -> R.string.conversation_sent
                "window" -> R.string.conversation_window; "duplicate_risk" -> R.string.conversation_duplicate_choice
                "latest_reset" -> R.string.conversation_latest_reset
                else -> R.string.conversation_cancelled
            }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = model::refresh, enabled = ready && !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.conversation_refresh)) }
                TextButton(onClick = { pingsOpen = true }, enabled = state.loaded, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.conversation_pings)) }
                if (state.busy || state.syncing) TextButton(onClick = model::cancelRequest, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
            }
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
        }
        Column(Modifier.fillMaxWidth().heightIn(max = composerLimit).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!state.atLatest || !state.latestWindow) TextButton(onClick = model::latest, enabled = ready && !state.busy) {
                Text(stringResource(R.string.conversation_latest))
            }
            if (state.uncertainSend) {
                Text(stringResource(R.string.conversation_uncertain))
                if (state.reconciledAfterUncertain) TextButton(onClick = model::acknowledgeDuplicateRisk, enabled = !state.busy) {
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
                    TextButton(onClick = model::enterRoom, enabled = ready && state.loaded && !state.busy && !state.stale &&
                        presenceHere?.presencePhase != PresencePhase.CONNECTING) {
                        Text(stringResource(if (presenceHere?.presencePhase == PresencePhase.CONNECTING)
                            R.string.presence_entering else R.string.presence_enter))
                    }
                }
            }
            OutlinedTextField(value = state.draft, onValueChange = model::updateDraft, enabled = !state.sending,
                label = { Text(stringResource(R.string.conversation_message)) }, minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag("conversation_composer"))
            Button(onClick = model::send, enabled = ready && !state.busy && model.canSend() && state.draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (state.sending) R.string.conversation_sending else R.string.conversation_send)) }
        }
    }
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
private fun displayTime(value: String, context: ConversationContext?): String {
    val instant = serverInstant(value) ?: return stringResource(R.string.conversation_time_unknown)
    val preferences = context?.policy?.display
    val deviceLocale = LocalConfiguration.current.locales[0]
    val locale = preferences?.dateLocale?.let(Locale::forLanguageTag) ?: deviceLocale
    val twentyFour = when (preferences?.hourCycle) { "h23" -> true; "h12" -> false; else -> DateFormat.is24HourFormat(LocalContext.current) }
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(instant.atZone(ZoneId.systemDefault())) + " " +
        DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm a", locale).format(instant.atZone(ZoneId.systemDefault()))
}

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
