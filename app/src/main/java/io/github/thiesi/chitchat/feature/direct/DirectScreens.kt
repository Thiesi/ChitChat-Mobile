package io.github.thiesi.chitchat.feature.direct

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.messageTime
import io.github.thiesi.chitchat.core.ui.canAcknowledgeVisibleRead
import io.github.thiesi.chitchat.core.ui.isReadEdgeVisible
import io.github.thiesi.chitchat.core.ui.MessageInput
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.typing.hasTypingContent
import io.github.thiesi.chitchat.feature.realtime.TypingStatus
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.feature.realtime.RealtimeStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun DirectInboxScreen(model: DirectInboxViewModel, thread: DirectThreadViewModel,
    onRefreshSession: () -> Unit, onSelect: (Long) -> Unit) {
    val session by model.session.collectAsStateWithLifecycle()
    key(session.server, session.generation, session.user?.id, session.user?.sessionVersion) {
        val connection = thread.realtime?.state?.collectAsStateWithLifecycle()?.value?.takeIf { it.generation == session.generation }
        val state by model.state.collectAsStateWithLifecycle()
        val drafts by thread.drafts.collectAsStateWithLifecycle()
        val attention by thread.attention.collectAsStateWithLifecycle()
        val now = rememberSessionNow(session.retryAtMillis)
        val ready = sessionReady(session, now)
        val focus = LocalFocusManager.current
        val submit = { model.search(); if (!model.state.value.invalidQuery) focus.clearFocus() }
        var discard by remember { mutableStateOf<Long?>(null) }
        DirectLifecycle(model::foreground, model::background, model::invalidate)
        val list = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().testTag("dm_inbox"), list, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SessionNotice(session, now, onRefreshSession)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dm_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = model::refresh, enabled = ready && !state.busy) { Text(stringResource(R.string.dm_refresh)) }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.stale) Text(stringResource(R.string.dm_stale))
                state.error?.let { InlineError(directError(it)) }
                DirectPrivacyNotice(state.inbox?.privacy, R.string.dm_inbox_details,
                    needsAttention = connection?.let { it.phase != RealtimePhase.LIVE || it.broadcasts.any { it.roomId == null } } == true) {
                    if (connection != null) RealtimeStatus(connection, thread.realtime::retry, thread.realtime::dismissBroadcast, roomId = null)
                }
                Text(stringResource(R.string.dm_no_push), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.query, model::updateQuery, Modifier.fillMaxWidth().testTag("dm_search_input"),
                    label = { Text(stringResource(R.string.dm_search_label)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
                Button(onClick = submit, enabled = ready && state.inbox != null && !state.stale && !state.searching) {
                    Text(stringResource(R.string.dm_search))
                }
                Text(stringResource(R.string.dm_search_help), style = MaterialTheme.typography.bodySmall)
                if (state.invalidQuery) InlineError(stringResource(R.string.dm_search_help))
                if (state.searching) CircularProgressIndicator()
                state.searchError?.let { InlineError(directError(it)) }
                if (state.searched && state.results.isEmpty()) Text(stringResource(R.string.dm_search_empty))
            }
            items(state.results, key = { "person:${it.id}" }) { person ->
                OutlinedButton(onClick = { onSelect(person.id) }, Modifier.fillMaxWidth().testTag("dm_person:${person.id}"), enabled = ready) { Text(person.username.orEmpty()) }
            }
            if (drafts.isNotEmpty()) item { Text(stringResource(R.string.dm_drafts), style = MaterialTheme.typography.titleMedium) }
            items(drafts.values.toList(), key = { "draft:${it.peer}" }) { draft ->
                Column {
                    TextButton(onClick = { onSelect(draft.peer) }, enabled = ready) { Text(draft.name ?: stringResource(R.string.dm_draft_peer, draft.peer)) }
                    if (draft.uncertain) Text(stringResource(R.string.dm_unknown))
                    TextButton(onClick = { discard = draft.peer }) { Text(stringResource(R.string.dm_discard_draft)) }
                }
            }
            item { Text(stringResource(R.string.dm_inbox_cap), style = MaterialTheme.typography.bodySmall) }
            if (state.inbox != null && state.inbox!!.conversations.isEmpty()) item { Text(stringResource(R.string.dm_inbox_empty)) }
            items(state.inbox?.conversations.orEmpty(), key = { "peer:${it.peer.id}" }) { conversation ->
                OutlinedCard(onClick = { onSelect(conversation.peer.id) }, Modifier.fillMaxWidth().testTag("dm_conversation:${conversation.peer.id}"), enabled = ready) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conversation.peer.username.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        val last = conversation.lastMessage
                        val preview = if (last.deleted) stringResource(R.string.conversation_deleted)
                            else last.body?.takeIf { it.isNotEmpty() } ?: last.attachment?.name.orEmpty()
                        Text(if (last.outgoing) stringResource(R.string.dm_outgoing_preview, preview.take(160)) else preview.take(160))
                        Text(messageTime(last.createdAt, state.inbox?.display), style = MaterialTheme.typography.labelSmall)
                        if (conversation.unreadCount > 0) Text(pluralStringResource(R.plurals.dm_unread_count,
                            conversation.unreadCount, conversation.unreadCount))
                        attention[conversation.peer.id]?.let { LocalAttention(it) }
                    }
                }
            }
        }
        if (discard != null) AlertDialog(onDismissRequest = { discard = null },
            title = { Text(stringResource(R.string.dm_discard_draft)) }, text = { Text(stringResource(R.string.dm_discard_warning)) },
            confirmButton = { TextButton(onClick = { thread.discardSavedDraft(discard!!); discard = null }) { Text(stringResource(R.string.dm_discard_draft)) } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun DirectThreadScreen(model: DirectThreadViewModel, peer: Long, onRefreshSession: () -> Unit) {
    val session by model.session.collectAsStateWithLifecycle()
    val connection = model.realtime?.state?.collectAsStateWithLifecycle()?.value?.takeIf { it.generation == session.generation }
    key(peer, session.server, session.generation, session.user?.id, session.user?.sessionVersion) {
        val state by model.state.collectAsStateWithLifecycle()
        val attention by model.attention.collectAsStateWithLifecycle()
        val now = rememberSessionNow(session.retryAtMillis)
        val ready = sessionReady(session, now)
        val timeline = rememberLazyListState()
        var blockConfirm by remember { mutableStateOf(false) }
        LaunchedEffect(peer) { model.open(peer) }
        DirectLifecycle(model::foreground, model::background, model::invalidate)
        LaunchedEffect(state.scrollRequest) {
            if (state.scrollRequest > 0 && state.messages.isNotEmpty()) timeline.scrollToItem(state.messages.size + 1)
        }
        LaunchedEffect(state.anchor) { state.anchor?.let { anchor ->
            val index = state.messages.indexOfFirst { it.id == anchor.id }
            if (index >= 0) timeline.scrollToItem(index + 1, anchor.offset)
        } }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateAsState()
        val window = LocalWindowInfo.current
        LaunchedEffect(state.messages, state.busy, state.stale, state.latestWindow, lifecycle, lifecycleState) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow {
                    val info = timeline.layoutInfo
                    val readable = canAcknowledgeVisibleRead(lifecycleState, window.isWindowFocused)
                    val visible = info.visibleItemsInfo.filter { it.offset < info.viewportEndOffset && it.offset + it.size > info.viewportStartOffset }
                    val first = visible.firstOrNull { (it.key as? String)?.startsWith("direct:") == true }
                    val anchor = first?.let { DirectAnchor((it.key as String).substringAfter(':').toLong(), -it.offset) }
                    val newest = state.messages.lastOrNull()?.id
                    val last = visible.firstOrNull { it.key == "direct:$newest" }
                    val incoming = visible.mapNotNull { (it.key as? String)?.removePrefix("direct:")?.toLongOrNull() }
                        .filter { id -> state.messages.any { it.id == id && !it.outgoing } }.toSet()
                    DirectViewport(anchor, incoming.takeIf { readable }.orEmpty(),
                        newest.takeIf { readable && last != null && isReadEdgeVisible(last.offset, last.size, info.viewportStartOffset, info.viewportEndOffset) },
                        readable && !timeline.canScrollForward)
                }.distinctUntilChanged().collect { model.viewport(it.anchor, it.incoming, it.newest, it.edge) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 240.dp
            val headerLimit = maxHeight * 0.3f
            val composerLimit = (maxHeight * 0.45f).coerceAtLeast(64.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!compact) Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    SessionNotice(session, now, onRefreshSession)
                    Text(state.context?.peer?.username ?: stringResource(R.string.dm_title), style = MaterialTheme.typography.titleLarge)
                    state.context?.let { Text(stringResource(R.string.dm_privacy_short, it.privacy.direct.retention), style = MaterialTheme.typography.bodySmall) }
                    ThreadFeedback(model, state, attention[peer], ready)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("dm_timeline"), timeline,
                    contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("older") { if (state.mayHaveOlder) TextButton(onClick = model::older, enabled = !state.busy && !state.stale) {
                        Text(stringResource(R.string.conversation_older))
                    } }
                    if (state.loaded && state.messages.isEmpty()) item("empty") {
                        Text(stringResource(when {
                            !state.latestWindow || state.newerAvailable -> R.string.dm_empty_range
                            state.mayHaveOlder -> R.string.dm_empty_page
                            else -> R.string.dm_empty
                        }))
                    }
                    items(state.messages, key = { "direct:${it.id}" }) { message ->
                        Column(Modifier.fillMaxWidth().testTag("direct:${message.id}")) {
                            if (message.id == state.unreadDividerId) Text(stringResource(R.string.dm_new_divider), style = MaterialTheme.typography.labelMedium)
                            DirectMessageRow(message, state.context)
                        }
                    }
                    item("end") { Spacer(Modifier.height(1.dp)) }
                }
                TypingStatus(model.typing, session, TypingTarget.Direct(peer),
                    viewing = state.loaded && !state.stale && !blockConfirm && state.context?.relationship?.messagingAvailable == true,
                    publishing = ready && model.canSend())
                Row(Modifier.fillMaxWidth().heightIn(max = composerLimit).padding(horizontal = if (compact) 4.dp else 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    run {
                        val context = state.context
                        DirectPrivacyNotice(context?.privacy, R.string.dm_details, compact = true,
                            needsAttention = state.stale || state.error != null || state.validation != null || state.newerAvailable ||
                                state.uncertainSend || state.uncertainRead || state.readError != null || state.uncertainBlock ||
                                attention[peer] != null || state.draftLimit || !model.canEditDraft() ||
                                context?.relationship?.messagingAvailable == false || context?.mutedEverywhere != null || !ready ||
                                connection?.let { it.phase != RealtimePhase.LIVE || it.broadcasts.any { it.roomId == null } } == true) { dismiss ->
                            if (connection != null)
                                RealtimeStatus(connection, model.realtime::retry, model.realtime::dismissBroadcast, roomId = null)
                            Text(context?.peer?.username ?: stringResource(R.string.dm_title), style = MaterialTheme.typography.titleMedium)
                            SessionNotice(session, now, onRefreshSession)
                            ThreadFeedback(model, state, attention[peer], ready, true, dismiss)
                            if (context != null) TextButton(onClick = { dismiss(); blockConfirm = true }, enabled = ready && !state.busy && !state.uncertainBlock) {
                                Text(stringResource(if (context.relationship.blockedByMe) R.string.dm_unblock else R.string.dm_block))
                            }
                        }
                    }
                    MessageInput(state.draft, { value -> model.updateDraft(value)
                        model.typing?.input(session, TypingTarget.Direct(peer), hasTypingContent(value)) }, !state.sending && model.canEditDraft(),
                        stringResource(R.string.dm_message), compact, Modifier.weight(1f).testTag("dm_composer"))
                    Button(onClick = model::send, enabled = ready && model.canSend(), modifier = Modifier.testTag("dm_send")) { Text(stringResource(R.string.dm_send)) }
                }
            }
        }
        if (blockConfirm && state.context != null) {
            val blocked = state.context!!.relationship.blockedByMe
            AlertDialog(onDismissRequest = { blockConfirm = false }, title = { Text(stringResource(if (blocked) R.string.dm_unblock else R.string.dm_block)) },
                text = { Text(stringResource(R.string.dm_block_help)) },
                confirmButton = { TextButton(onClick = { blockConfirm = false; model.setBlocked(!blocked) }, Modifier.testTag("dm_confirm_block")) { Text(stringResource(if (blocked) R.string.dm_unblock else R.string.dm_block)) } },
                dismissButton = { TextButton(onClick = { blockConfirm = false }) { Text(stringResource(R.string.cancel)) } })
        }
    }
}

@Composable
private fun ThreadFeedback(model: DirectThreadViewModel, state: DirectThreadState, attention: DirectAttention?,
    ready: Boolean, alwaysRefresh: Boolean = false, dismiss: () -> Unit = {}) {
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.stale && state.loaded) Text(stringResource(R.string.dm_stale))
    if (state.anchorLost) Text(stringResource(R.string.conversation_anchor_unavailable))
    if (state.incompleteRange) Text(stringResource(R.string.conversation_range_unavailable))
    state.error?.let { InlineError(directError(it)) }
    state.validation?.let { InlineError(stringResource(if (it == "empty_message") R.string.dm_empty_body else R.string.dm_long_body)) }
    if (state.confirmedSendId != null && (state.error != null || state.stale)) Text(stringResource(R.string.dm_confirmed))
    if (state.uncertainSend) {
        Text(stringResource(R.string.dm_unknown))
        TextButton(onClick = { dismiss(); model.latest() }, enabled = ready && !state.busy) { Text(stringResource(R.string.dm_check_latest)) }
        if (state.checkedUnknown) TextButton(onClick = { dismiss(); model.allowDuplicateRisk() }, enabled = ready && !state.busy) { Text(stringResource(R.string.dm_allow_duplicate)) }
    }
    if (state.readError != null || state.uncertainRead) {
        Text(stringResource(R.string.dm_read_failed))
        TextButton(onClick = { dismiss(); model.retryRead() }, enabled = ready && !state.busy && state.latestWindow) { Text(stringResource(R.string.dm_retry_read)) }
    }
    attention?.let { LocalAttention(it)
        if (it.additionalMayBeRead) TextButton(onClick = model::acknowledgeAdditionalRead) { Text(stringResource(R.string.dm_checked_additional)) }
    }
    if (state.uncertainBlock) Text(stringResource(R.string.dm_block_unknown))
    state.context?.let { context ->
        if (context.relationship.blockedByMe) Text(stringResource(R.string.dm_you_blocked))
        else if (!context.relationship.messagingAvailable) Text(stringResource(R.string.dm_unavailable))
        context.mutedEverywhere?.let { Text(stringResource(R.string.conversation_muted, it.reason,
            it.expiresAt?.let { expires -> messageTime(expires, context.display) } ?: stringResource(R.string.conversation_indefinite))) }
    }
    if (alwaysRefresh || state.stale || state.error != null) TextButton(onClick = { dismiss(); model.refresh() }, enabled = ready && !state.busy) { Text(stringResource(R.string.dm_refresh)) }
    if (alwaysRefresh || state.newerAvailable || !state.latestWindow) TextButton(onClick = { dismiss(); model.latest() }, enabled = ready && !state.busy) { Text(stringResource(R.string.dm_latest)) }
    if (state.draftLimit || !model.canEditDraft()) Text(stringResource(R.string.dm_draft_limit))
}

@Composable
private fun DirectMessageRow(message: DirectMessage, context: DirectContext?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
    val tint = if (message.outgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.medium,
        color = tint.compositeOver(MaterialTheme.colorScheme.background), contentColor = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.conversation_author_time, message.sender.username ?: stringResource(R.string.conversation_unknown_user),
            messageTime(message.createdAt, context?.display)), style = MaterialTheme.typography.labelMedium)
        if (message.deleted) Text(stringResource(R.string.conversation_deleted)) else {
            message.replyTo?.let { reply ->
                Text(if (!reply.available || reply.message?.deleted != false) stringResource(R.string.conversation_reply_unavailable)
                    else stringResource(R.string.conversation_reply, reply.message.sender.username.orEmpty(), reply.message.body.take(160)),
                    style = MaterialTheme.typography.bodySmall)
            }
            SelectionContainer { message.body?.let { Text(it) } }
            message.attachment?.let { Text(stringResource(R.string.conversation_attachment, it.name, it.mimeType, it.sizeBytes.toString())) }
            if (message.editedAt != null) Text(stringResource(R.string.conversation_edited), style = MaterialTheme.typography.labelSmall)
        }
        if (message.reactions.isNotEmpty()) Text(message.reactions.joinToString("  ") { "${it.emoji} ${it.users.size}" })
        if (message.outgoing) Text(stringResource(if (message.readAt == null) R.string.dm_sent else R.string.dm_read), style = MaterialTheme.typography.labelSmall)
    }
    }
    }
}

@Composable
private fun DirectPrivacyNotice(privacy: MessagePrivacy?, title: Int = R.string.dm_privacy, compact: Boolean = false,
    needsAttention: Boolean = false,
    actions: @Composable (() -> Unit) -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    if (!compact) privacy?.let { Text(stringResource(R.string.dm_privacy_short, it.direct.retention), style = MaterialTheme.typography.bodySmall) }
    val label = stringResource(title)
    val status = stringResource(R.string.dm_details_attention)
    BadgedBox(badge = { if (needsAttention) Badge() }) {
        if (!compact) TextButton(onClick = { expanded = true }, modifier = Modifier.semantics {
            if (needsAttention) stateDescription = status
        }) { Text(label) }
        else {
            IconButton(onClick = { expanded = true }, modifier = Modifier.semantics {
                contentDescription = label
                if (needsAttention) stateDescription = status
            }) { Text("\u22ee") }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(stringResource(title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            privacy?.let { privacy ->
                Text(stringResource(R.string.dm_privacy_short, privacy.direct.retention))
                Text(stringResource(R.string.dm_retained_edits))
                Text(if (privacy.direct.inspectionEnabled) stringResource(R.string.dm_inspection,
                    stringResource(if (privacy.direct.inspectionRole == "admin") R.string.dm_admin else R.string.dm_super_admin)) else stringResource(R.string.dm_no_inspection))
                Text(if (privacy.revisions.reviewEnabled) stringResource(R.string.dm_revision_review,
                    stringResource(if (privacy.revisions.reviewRole == "admin") R.string.dm_admin else R.string.dm_super_admin)) else stringResource(R.string.dm_no_revision_review))
                if (privacy.revisions.reviewEnabled) {
                    Text(stringResource(if (privacy.revisions.reasonRequired) R.string.dm_reason_required else R.string.dm_reason_not_required))
                    Text(stringResource(if (privacy.revisions.auditEachReview) R.string.dm_review_audited else R.string.dm_review_not_audited))
                    Text(stringResource(if (privacy.revisions.participantNotification) R.string.dm_participant_notice else R.string.dm_no_participant_notice))
                }
            }
            Text(stringResource(R.string.dm_read_policy))
            Text(stringResource(R.string.dm_no_push))
            actions { expanded = false }
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.dm_close)) } })
}

@Composable
private fun LocalAttention(value: DirectAttention) {
    if (value.ids.isNotEmpty()) Text(pluralStringResource(R.plurals.dm_local_attention, value.ids.size, value.ids.size),
        Modifier.testTag("dm_local_attention").semantics { liveRegion = LiveRegionMode.Polite })
    if (value.additionalMayBeRead) Text(stringResource(R.string.dm_additional_read))
}

@Composable
private fun DirectLifecycle(start: () -> Unit, stop: () -> Unit, refresh: () -> Unit) {
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRefresh by rememberUpdatedState(refresh)
    LifecycleEventEffect(Lifecycle.Event.ON_START, onEvent = start)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) stop() }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { delay(60_000); currentRefresh() }
    } }
}

@Composable
private fun rememberSessionNow(retryAt: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(retryAt, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        do { now = System.currentTimeMillis(); if (now < retryAt) delay(1000) } while (now < retryAt)
    } }
    return now
}
private fun sessionReady(session: SessionState, now: Long) = !session.busy && !session.refreshRequired && !session.storageWarning && now >= session.retryAtMillis
@Composable
private fun SessionNotice(session: SessionState, now: Long, refresh: () -> Unit) {
    if (session.busy) Text(stringResource(R.string.checking_session))
    if (session.refreshRequired || session.storageWarning) TextButton(onClick = refresh, enabled = !session.busy && now >= session.retryAtMillis) {
        Text(stringResource(R.string.refresh_session))
    }
    if (now < session.retryAtMillis) Text(stringResource(R.string.rate_limited))
}

@Composable
private fun directError(error: ApiFailure) = stringResource(when {
    error.code == "direct_message_unavailable" -> R.string.dm_unavailable
    error.status == 404 -> R.string.dm_peer_missing
    error.code == "csrf_failed" || error.kind == FailureKind.STORAGE -> R.string.session_refresh_needed
    error.kind == FailureKind.TLS -> R.string.tls_error
    error.kind == FailureKind.REDIRECT -> R.string.redirect_error
    error.kind == FailureKind.INCOMPATIBLE -> R.string.dm_compat
    error.kind == FailureKind.TOO_LARGE -> R.string.dm_too_large
    error.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT) -> R.string.dm_network
    error.kind == FailureKind.EXPIRED || error.status == 401 -> R.string.session_expired
    error.status == 429 -> R.string.rate_limited
    error.status == 503 -> R.string.server_unavailable
    else -> R.string.request_rejected
})
private data class DirectViewport(val anchor: DirectAnchor?, val incoming: Set<Long>, val newest: Long?, val edge: Boolean)
