package io.github.thiesi.chitchat.feature.conversation

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.ConversationContext
import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.conversation.isPrivatePingCommand
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.realtime.PresencePhase
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.feature.realtime.RealtimeStatus
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.MessageInput
import io.github.thiesi.chitchat.core.ui.MessageSendButton
import io.github.thiesi.chitchat.core.ui.MessageActions
import io.github.thiesi.chitchat.core.ui.MessageActionSelection
import io.github.thiesi.chitchat.core.ui.ComposerReplyControl
import io.github.thiesi.chitchat.core.ui.ComposerReplyOptions
import io.github.thiesi.chitchat.core.ui.copyMessage
import io.github.thiesi.chitchat.core.ui.messageExcerpt
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.typing.hasTypingContent
import io.github.thiesi.chitchat.feature.realtime.TypingStatus
import io.github.thiesi.chitchat.feature.account.IgnorePersonViewModel
import io.github.thiesi.chitchat.feature.account.IgnorePersonDialog
import io.github.thiesi.chitchat.core.ui.canAcknowledgeVisibleRead
import io.github.thiesi.chitchat.core.ui.isReadEdgeVisible
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.feature.targets.ReplyPreview
import io.github.thiesi.chitchat.feature.targets.RequestedMessageMarker
import kotlinx.coroutines.delay

@Composable
fun ConversationScreen(model: ConversationViewModel, roomId: Long, onRefreshSession: () -> Unit,
    ignores: IgnorePersonViewModel? = null, targets: MessageTargetViewModel? = null, obscured: Boolean = false,
    isCurrentEntry: () -> Boolean = { true }) {
    val session by model.session.collectAsStateWithLifecycle()
    val displayedSession = session
    key(roomId, session.server, session.generation, session.user?.id, session.user?.sessionVersion) {
        LaunchedEffect(roomId) { model.open(roomId, expected = displayedSession) }
        ConversationContent(model, roomId, onRefreshSession, ignores, targets, obscured, isCurrentEntry)
    }
}

@Composable
private fun ConversationContent(model: ConversationViewModel, roomId: Long, onRefreshSession: () -> Unit,
    ignores: IgnorePersonViewModel?, targets: MessageTargetViewModel?, obscured: Boolean, isCurrentEntry: () -> Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val displayedSession = session // Freeze callback origin; delegated Flow values can change before recomposition.
    if (!model.belongsTo(displayedSession)) return // No old-account frame while collectors catch up.
    val focus = LocalFocusManager.current
    val savedDrafts by model.drafts.collectAsStateWithLifecycle()
    val draftCount = savedDrafts.values.count { it.matches(displayedSession) }
    val ignoreState = ignores?.state?.collectAsStateWithLifecycle()?.value
    val personDialog = ignoreState?.target?.let { it.roomId == roomId && it.matches(session) } == true
    val ignoredIds = session.chatPolicy?.ignoredUserIds ?: state.context?.policy?.ignoredUserIds.orEmpty()
    val activity = LocalActivity.current
    val connection = model.realtime?.state?.collectAsStateWithLifecycle()?.value
    val presenceHere = connection?.takeIf { it.generation == session.generation && it.selectedRoomId == roomId }
    val activePresence = model.realtime == null || presenceHere?.presencePhase in setOf(PresencePhase.ACTIVE, PresencePhase.WARNING)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val window = LocalWindowInfo.current
    var pingsOpen by remember { mutableStateOf(false) }
    val pingsVisible = pingsOpen || state.pingSelection != null
    var detailsOpen by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf<MessageActionSelection?>(null) }
    var replyOptions by remember { mutableStateOf<ComposerReply?>(null) }
    var focusReply by remember { mutableStateOf<ComposerReply?>(null) }
    val composerFocus = remember { FocusRequester() }
    val androidContext = LocalContext.current
    val composerDialog = actions != null || replyOptions != null
    fun interactive() = isCurrentEntry() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !obscured &&
        !pingsOpen && model.state.value.pingSelection == null && !detailsOpen &&
        ignores?.state?.value?.target == null && !model.state.value.draftsOpen
    fun openActions(message: RoomMessage) {
        if (interactive() && actions == null && replyOptions == null && model.canUseMessage(displayedSession, roomId, message)) {
            focus.clearFocus(); actions = MessageActionSelection(message.id)
        }
    }
    val selectedReply = state.composerReply
    val replyIsPing = selectedReply != null && isPrivatePingCommand(state.draft)
    val original = selectedReply?.let { choice -> state.messages.firstOrNull { it.id == choice.messageId } }
    val replyHidden = original?.let { it.senderId in ignoredIds && it.id !in state.revealed } == true
    val replyTitle = if (replyIsPing) stringResource(R.string.composer_reply_ping_title)
        else if (original != null && !original.deleted && !replyHidden)
        stringResource(R.string.composer_reply_author, original.username ?: stringResource(R.string.conversation_unknown_user))
        else stringResource(R.string.composer_reply_id, selectedReply?.messageId ?: 0)
    val replyPreview = when {
        replyIsPing -> stringResource(R.string.composer_reply_ping_ignored)
        replyHidden -> stringResource(R.string.composer_reply_ignored)
        original?.deleted == true -> stringResource(R.string.conversation_deleted)
        original == null -> stringResource(R.string.composer_reply_not_visible)
        else -> messageExcerpt(original.body.orEmpty())
    }
    fun showReplyOptions(choice: ComposerReply) {
        if (interactive() && actions == null && replyOptions == null && model.state.value.composerReply === choice) {
            focus.clearFocus(); replyOptions = choice
        }
    }
    LaunchedEffect(state.composerReply, obscured, lifecycleState) {
        if (replyOptions != null && replyOptions !== state.composerReply) replyOptions = null
        if (obscured || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            actions = null; replyOptions = null; focusReply = null
        }
    }
    LaunchedEffect(focusReply, window.isWindowFocused, lifecycleState, obscured) {
        val pending = focusReply
        if (pending != null && window.isWindowFocused) {
            focusReply = null
            if (interactive() && actions == null && replyOptions == null && model.state.value.composerReply === pending && model.canSend())
                composerFocus.requestFocus()
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val timeline = rememberLazyListState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) {
        model.background(); ignores?.leaveRoom(session, roomId)
    } }
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
    LaunchedEffect(state.messages, state.busy, state.stale, state.latestWindow, pingsVisible, detailsOpen, personDialog, state.draftsOpen, composerDialog, obscured, lifecycle,
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
                val readable = canAcknowledgeVisibleRead(lifecycleState, window.isWindowFocused,
                    !pingsVisible && !detailsOpen && !personDialog && !state.draftsOpen && !composerDialog && !obscured)
                Triple(anchor, if (edgeVisible && readable) newest else null, !timeline.canScrollForward && readable)
            }.collect { (anchor, newest, edge) -> model.viewport(anchor, newest, edge) }
        }
    }
    val ready = !session.busy && !session.refreshRequired && !session.storageWarning && now >= session.retryAtMillis
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val compact = maxHeight < 240.dp
    val replyInline = compact
    val replyControl: @Composable (Boolean) -> Unit = { inline -> selectedReply?.let { choice ->
        ComposerReplyControl(inline, replyTitle, replyPreview, !state.sending && !obscured,
            onOptions = { showReplyOptions(choice) }, onCancel = {
                if (interactive() && actions == null && replyOptions == null) model.cancelReply(displayedSession, roomId, choice)
            }, warning = replyIsPing)
    } }
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
            if (state.anchorUnavailable && state.messages.isNotEmpty()) Text(stringResource(R.string.conversation_anchor_unavailable))
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
            if (state.draftLimit || !model.canEditDraft()) Text(stringResource(R.string.room_draft_limit))
            if (draftCount > 0) TextButton(onClick = { close(); model.showDrafts(displayedSession) }) {
                Text(stringResource(R.string.room_drafts_count, draftCount))
            }
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
                    if (state.loaded && state.messages.isEmpty()) Text(stringResource(
                        if (state.latestWindow) R.string.conversation_empty else R.string.conversation_empty_range))
                    if (state.mayHaveOlder) TextButton(onClick = model::older, enabled = !state.busy && !state.stale && !state.uncertainSend) {
                        Text(stringResource(R.string.conversation_older))
                    }
                }
            }
            items(state.messages, key = { "message:${it.id}" }) { message ->
                val highlighted = state.highlightedMessageId == message.id
                val ignored = message.senderId in ignoredIds
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("message:${message.id}")
                    .then(if (highlighted) Modifier.semantics { selected = true } else Modifier)) {
                    if (highlighted) RequestedMessageMarker(message.id)
                    if (state.unreadDividerId == message.id) {
                        HorizontalDivider(); Text(stringResource(R.string.conversation_unread), style = MaterialTheme.typography.labelMedium)
                    }
                    if (!ignored || ignores != null) {
                        val sender = message.username ?: stringResource(R.string.conversation_unknown_user)
                        val author = stringResource(R.string.conversation_author_time, sender, displayTime(message.createdAt, state.context))
                        if (ignores != null && message.senderId != null && message.senderId != session.user?.id &&
                            session.user?.guest == false) {
                            val label = stringResource(R.string.ignore_author_actions, author)
                            TextButton(onClick = { ignores.open(displayedSession, roomId, message.senderId, message.username) },
                                enabled = !state.busy && !session.busy,
                                modifier = Modifier.testTag("room_author:${message.id}").semantics { contentDescription = label }) {
                                Text(author, style = MaterialTheme.typography.labelMedium)
                            }
                        } else Text(author, style = MaterialTheme.typography.labelMedium)
                    }
                    if (ignored && message.id !in state.revealed) TextButton(onClick = { model.reveal(message.id) }) {
                        Text(stringResource(R.string.conversation_ignored))
                    } else MessageContent(message, state.context, targets?.let { coordinator -> { id ->
                        if (interactive() && actions == null && replyOptions == null) {
                            focus.clearFocus()
                            coordinator.load(displayedSession, MessageTarget.RoomMessage(roomId, id))
                        }
                        Unit
                    } }, onActions = { openActions(message) })
                }
            }
            item(key = "history_end") { Spacer(Modifier.height(1.dp)) }
        }
        TypingStatus(model.typing, session, TypingTarget.Room(roomId),
            viewing = state.loaded && !state.stale && state.context != null && !pingsVisible && !detailsOpen && !personDialog && !state.draftsOpen && !composerDialog && !obscured,
            publishing = ready && !state.busy && !composerDialog && !obscured && model.canSend())
        if (!replyInline) replyControl(false)
        Row(Modifier.fillMaxWidth().heightIn(max = composerLimit).padding(horizontal = if (compact) 4.dp else 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            run {
                val label = stringResource(R.string.conversation_details)
                val blocked = !ready || state.busy || !model.canSend()
                val status = stringResource(if (blocked) R.string.conversation_send_unavailable_details else R.string.conversation_details_attention)
                val attention = state.busy || state.syncing || state.stale || state.newerAvailable || state.uncertainSend ||
                    state.error != null || state.syncError != null || state.readError != null || state.validation != null ||
                    state.notice != null || blocked || connection?.takeIf { it.generation == session.generation }?.let {
                        it.phase != RealtimePhase.LIVE || it.broadcasts.any { it.roomId == null || it.roomId == roomId }
                    } == true
                BadgedBox(badge = { if (attention) Badge() }) {
                    IconButton(onClick = { detailsOpen = true }, modifier = Modifier.semantics {
                        contentDescription = label
                        if (attention) stateDescription = status
                    }) { Text("\u22ee") }
                }
            }
            if (replyInline) replyControl(true)
            MessageInput(state.draft, { value ->
                if (interactive() && actions == null && replyOptions == null && model.updateDraft(displayedSession, roomId, value) && model.state.value.pingSelection == null)
                    model.typing?.input(displayedSession, TypingTarget.Room(roomId), hasTypingContent(value)) },
                !state.sending && model.canEditDraft() && !composerDialog && !obscured,
                stringResource(R.string.conversation_message), compact,
                Modifier.weight(1f).focusRequester(composerFocus).testTag("conversation_composer"))
            MessageSendButton(stringResource(if (state.sending) R.string.conversation_sending else R.string.conversation_send),
                onClick = { if (interactive() && actions == null && replyOptions == null) model.send(displayedSession, roomId) },
                enabled = ready && !state.busy && !composerDialog && !obscured && model.canSend() && state.draft.phpTrim().isNotEmpty(),
                sending = state.sending, modifier = Modifier.testTag("conversation_send"))
        }
    }
    actions?.let { request ->
        val message = state.messages.firstOrNull { it.id == request.messageId }
        val available = message != null && model.canUseMessage(displayedSession, roomId, message)
        MessageActions(available, message?.let { model.canReplyTo(displayedSession, roomId, it) } == true,
            !message?.body.isNullOrEmpty(), onReply = {
                if (actions === request && interactive() && message != null && model.replyTo(displayedSession, roomId, message)) {
                    actions = null; focusReply = model.state.value.composerReply
                }
            }, onCopy = {
                if (actions === request && interactive() && message != null && model.canUseMessage(displayedSession, roomId, message)) {
                    message.body?.takeIf { it.isNotEmpty() }?.let { copyMessage(androidContext, it) }
                    actions = null
                }
            }, onDismiss = { if (actions === request) actions = null })
    }
    replyOptions?.let { choice ->
        val current = state.composerReply === choice
        ComposerReplyOptions(if (current) replyTitle else stringResource(R.string.composer_reply_id, choice.messageId),
            if (current) replyPreview else stringResource(R.string.composer_reply_not_visible), current && !state.sending,
            onOpenOriginal = targets?.takeIf { current && ready && !state.busy }?.let { coordinator -> {
                if (replyOptions === choice && interactive() && model.state.value.composerReply === choice) {
                    replyOptions = null; focus.clearFocus()
                    coordinator.load(displayedSession, MessageTarget.RoomMessage(roomId, choice.messageId))
                }
            } }, onCancel = {
                if (replyOptions === choice && interactive() && model.state.value.composerReply === choice) {
                    model.cancelReply(displayedSession, roomId, choice); replyOptions = null
                }
            }, onDismiss = { if (replyOptions === choice) replyOptions = null })
    }
    if (detailsOpen) AlertDialog(onDismissRequest = { detailsOpen = false },
        title = { Text(stringResource(R.string.conversation_details)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            connection?.takeIf { it.generation == session.generation }?.let {
                val realtime = model.realtime
                RealtimeStatus(it, realtime::retry, realtime::dismissBroadcast, roomId)
            }
            header { detailsOpen = false }
        } },
        confirmButton = { TextButton(onClick = { detailsOpen = false }) { Text(stringResource(R.string.rooms_close)) } })
    }
    if (pingsVisible) {
        val choice = state.pingSelection // Freeze the close callback's original selection, not a delegated live value.
        val visiblePings = state.pings.filterNot { it.sender.id in ignoredIds }
        val targetMissing = choice != null && visiblePings.none { it.id == choice.id }
        val pingList = rememberLazyListState()
        var positioned by remember(choice) { mutableStateOf(false) }
        LaunchedEffect(choice, visiblePings.map { it.id }) {
            val index = visiblePings.indexOfFirst { it.id == choice?.id }
            if (choice != null && !positioned && index >= 0) { pingList.scrollToItem(index + 1); positioned = true }
        }
        val close: () -> Unit = { if (isCurrentEntry()) {
            pingsOpen = false; choice?.let(model::dismissTargetPing)
        } }
        AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("private_pings_dialog"),
        title = { Text(stringResource(if (targetMissing) R.string.ping_target_missing_title else R.string.conversation_pings)) },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), state = pingList,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "explanation") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.conversation_pings_private))
                if (choice != null) Text(stringResource(R.string.ping_target_limit))
                if (targetMissing) Text(stringResource(R.string.ping_target_unavailable))
                else if (visiblePings.isEmpty()) Text(stringResource(R.string.conversation_no_pings))
            } }
            items(visiblePings, key = { it.id }) { ping ->
                val isTarget = ping.id == choice?.id
                val selectedLabel = stringResource(R.string.ping_target_selected)
                Card(Modifier.fillMaxWidth().testTag("private_ping:${ping.id}").semantics {
                    selected = isTarget
                    if (isTarget) stateDescription = selectedLabel
                }) { Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isTarget) Text(selectedLabel, style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.conversation_ping_people, ping.sender.username ?: stringResource(R.string.conversation_unknown_user),
                        ping.target.username ?: stringResource(R.string.conversation_unknown_user)))
                    Text(ping.message); Text(displayTime(ping.createdAt, state.context), style = MaterialTheme.typography.labelSmall)
                } }
            }
        } }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.rooms_close)) } })
    }
    if (ignores != null && ignoreState != null) IgnorePersonDialog(ignores, ignoreState, session, roomId, now, onRefreshSession)
}

@Composable
private fun MessageContent(message: RoomMessage, context: ConversationContext?, onOpenReply: ((Long) -> Unit)? = null,
    onActions: (() -> Unit)? = null) {
    val sender = message.username ?: stringResource(R.string.conversation_unknown_user)
    val actionsLabel = stringResource(R.string.message_actions)
    when {
        message.deleted -> Text(stringResource(R.string.conversation_deleted), style = MaterialTheme.typography.bodyMedium)
        else -> {
            message.replyTo?.let { reply ->
                val original = reply.message
                val text = when {
                    !reply.available || original == null -> stringResource(R.string.conversation_reply_unavailable)
                    original.deleted -> stringResource(R.string.conversation_deleted)
                    else -> stringResource(R.string.conversation_reply, original.sender?.username ?: stringResource(R.string.conversation_unknown_user),
                        messageExcerpt(original.body.orEmpty()))
                }
                ReplyPreview(text, onOpenReply?.takeIf { reply.available && original != null }?.let { open -> { open(reply.messageId) } },
                    Modifier.testTag("room_reply:${message.id}")) }
            Column(Modifier.fillMaxWidth().then(if (onActions != null) Modifier.heightIn(min = 48.dp)
                .combinedClickable(onClickLabel = actionsLabel, onLongClickLabel = actionsLabel,
                    onClick = onActions, onLongClick = onActions) else Modifier)) {
                if (message.type == "emote") Text(stringResource(R.string.conversation_emote, sender, message.body.orEmpty()))
                else message.body?.let { Text(it) }
                message.attachment?.let { Text(stringResource(R.string.conversation_attachment, it.name, it.mimeType, it.sizeBytes.toString())) }
                if (message.editedAt != null) Text(stringResource(R.string.conversation_edited), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (message.reactions.isNotEmpty()) Text(message.reactions.joinToString("  ") { "${it.emoji} ${it.users.size}" })
}

@Composable
private fun displayTime(value: String, context: ConversationContext?) =
    io.github.thiesi.chitchat.core.ui.messageTime(value, context?.policy?.display)

@Composable
private fun conversationError(error: ApiFailure) = stringResource(when {
    error.code == "invalid_reply_target" -> R.string.composer_reply_invalid
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
