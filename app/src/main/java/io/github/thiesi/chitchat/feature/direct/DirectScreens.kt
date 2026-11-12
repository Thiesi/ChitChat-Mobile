package io.github.thiesi.chitchat.feature.direct

import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton

import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.core.ui.AttentionBadge

import androidx.compose.material3.MaterialTheme
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.direct.*
import io.github.thiesi.chitchat.core.conversation.ComposerReply
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import io.github.thiesi.chitchat.core.reactions.ReactionEmoji
import io.github.thiesi.chitchat.core.reactions.selectedBy
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.MessageBody
import io.github.thiesi.chitchat.core.ui.MessageMentions
import io.github.thiesi.chitchat.core.ui.messageLinks
import io.github.thiesi.chitchat.core.ui.openMessageLink
import io.github.thiesi.chitchat.core.ui.messageTime
import io.github.thiesi.chitchat.core.ui.canAcknowledgeVisibleRead
import io.github.thiesi.chitchat.core.ui.isReadEdgeVisible
import io.github.thiesi.chitchat.core.ui.MessageInput
import io.github.thiesi.chitchat.core.ui.AttachAvailability
import io.github.thiesi.chitchat.core.ui.AttachButton
import io.github.thiesi.chitchat.core.ui.AttachNotice
import io.github.thiesi.chitchat.core.ui.AttachNotices
import io.github.thiesi.chitchat.core.ui.AttachmentCard
import io.github.thiesi.chitchat.core.ui.AttachmentEvidenceRow
import io.github.thiesi.chitchat.core.ui.AttachmentReviewDialog
import io.github.thiesi.chitchat.core.ui.attachAvailability
import io.github.thiesi.chitchat.core.ui.captionTooLong
import io.github.thiesi.chitchat.core.media.AttachmentPolicy
import io.github.thiesi.chitchat.core.media.AttachmentPolicyObservation
import io.github.thiesi.chitchat.core.ui.MessageSendButton
import io.github.thiesi.chitchat.core.ui.MessageActions
import io.github.thiesi.chitchat.core.ui.MessageReactionPicker
import io.github.thiesi.chitchat.core.ui.reactionLabel
import io.github.thiesi.chitchat.core.ui.MessageActionSelection
import io.github.thiesi.chitchat.core.ui.ComposerReplyControl
import io.github.thiesi.chitchat.core.ui.ComposerReplyOptions
import io.github.thiesi.chitchat.core.ui.copyMessage
import io.github.thiesi.chitchat.core.ui.messageExcerpt
import io.github.thiesi.chitchat.core.typing.TypingTarget
import io.github.thiesi.chitchat.core.typing.hasTypingContent
import io.github.thiesi.chitchat.feature.realtime.TypingStatus
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.feature.realtime.RealtimeStatus
import io.github.thiesi.chitchat.core.targets.MessageTarget
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.feature.targets.ReplyPreview
import io.github.thiesi.chitchat.feature.targets.RequestedMessageMarker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun DirectInboxScreen(model: DirectInboxViewModel, thread: DirectThreadViewModel,
    onRefreshSession: () -> Unit, isCurrentEntry: () -> Boolean, onSelect: (Long) -> Unit) {
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
        var discard by remember { mutableStateOf<DirectDraft?>(null) }
        var detailsOpen by remember { mutableStateOf(false) }
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
                    expanded = detailsOpen, onExpandedChange = { detailsOpen = it },
                    needsAttention = connection?.let { it.phase != RealtimePhase.LIVE || it.broadcasts.any { it.roomId == null } } == true) {
                    if (connection != null) RealtimeStatus(connection, thread.realtime::retry, thread.realtime::dismissBroadcast, roomId = null)
                }
                Text(stringResource(R.string.dm_no_push), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.query, model::updateQuery, Modifier.fillMaxWidth().testTag("dm_search_input"),
                    label = { Text(stringResource(R.string.dm_search_label)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }), shape = MaterialTheme.shapes.medium)
                PrimaryButton(onClick = submit, enabled = ready && state.inbox != null && !state.stale && !state.searching) {
                    Text(stringResource(R.string.dm_search))
                }
                Text(stringResource(R.string.dm_search_help), style = MaterialTheme.typography.bodySmall)
                if (state.invalidQuery) InlineError(stringResource(R.string.dm_search_help))
                if (state.searching) CircularProgressIndicator()
                state.searchError?.let { InlineError(directError(it)) }
                if (state.searched && state.results.isEmpty()) Text(stringResource(R.string.dm_search_empty))
            }
            items(state.results, key = { "person:${it.id}" }) { person ->
                LanternOutlinedButton(onClick = { onSelect(person.id) }, Modifier.fillMaxWidth().testTag("dm_person:${person.id}"), enabled = ready) { Text(person.username.orEmpty()) }
            }
            if (drafts.isNotEmpty()) item { Text(stringResource(R.string.dm_drafts), style = MaterialTheme.typography.titleMedium) }
            items(drafts.values.toList(), key = { "draft:${it.peer}" }) { draft ->
                Column {
                    TextButton(onClick = { onSelect(draft.peer) }, enabled = ready) { Text(draft.name ?: stringResource(R.string.dm_draft_peer, draft.peer)) }
                    if (draft.uncertain) Text(stringResource(R.string.dm_unknown))
                    draft.composerReply?.let { Text(stringResource(R.string.composer_reply_id, it.messageId)) }
                    TextButton(onClick = { if (isCurrentEntry()) discard = draft }) { Text(stringResource(R.string.dm_discard_draft)) }
                }
            }
            item { Text(stringResource(R.string.dm_inbox_cap), style = MaterialTheme.typography.bodySmall) }
            if (state.inbox != null && state.inbox!!.conversations.isEmpty()) item { Text(stringResource(R.string.dm_inbox_empty)) }
            items(state.inbox?.conversations.orEmpty(), key = { "peer:${it.peer.id}" }) { conversation ->
                OutlinedCard(onClick = { onSelect(conversation.peer.id) }, Modifier.fillMaxWidth().testTag("dm_conversation:${conversation.peer.id}"), enabled = ready, shape = MaterialTheme.shapes.large) {
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
        val originalDiscard = discard
        if (originalDiscard != null) AlertDialog(onDismissRequest = { if (discard === originalDiscard) discard = null },
            title = { Text(stringResource(R.string.dm_discard_draft)) }, text = { Text(stringResource(R.string.dm_discard_warning)) },
            confirmButton = { TextButton(onClick = {
                thread.discardSavedDraft(originalDiscard, isCurrentEntry)
                if (discard === originalDiscard) discard = null
            }) { Text(stringResource(R.string.dm_discard_draft)) } },
            dismissButton = { TextButton(onClick = { if (discard === originalDiscard) discard = null }) { Text(stringResource(R.string.cancel)) } }, modifier = Modifier.lanternDialogEdge())
    }
}

@Composable
fun DirectThreadScreen(model: DirectThreadViewModel, peer: Long, onRefreshSession: () -> Unit,
    targets: MessageTargetViewModel? = null, obscured: Boolean = false, onProfile: ((Long) -> Boolean)? = null,
    completion: io.github.thiesi.chitchat.feature.completion.CompletionViewModel? = null, completionEntryId: String = "",
    onStopped: (() -> Unit)? = null,
    isCurrentEntry: () -> Boolean = { true }) {
    val session by model.session.collectAsStateWithLifecycle()
    val displayedSession = session
    val connection = model.realtime?.state?.collectAsStateWithLifecycle()?.value?.takeIf { it.generation == session.generation }
    key(peer, session.server, session.generation, session.user?.id, session.user?.sessionVersion) {
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(peer) { model.open(peer, displayedSession) }
        DirectLifecycle(model::foreground, onStopped ?: model::background, model::invalidate)
        if (model.belongsTo(displayedSession) && state.peerId == peer) {
            val focus = LocalFocusManager.current
            val attention by model.attention.collectAsStateWithLifecycle()
            val now = rememberSessionNow(session.retryAtMillis)
            val ready = sessionReady(session, now)
            val timeline = rememberLazyListState()
            var blockConfirm by remember { mutableStateOf(false) }
            var detailsOpen by remember { mutableStateOf(false) }
            var profileDetailsToken by remember { mutableStateOf<Any?>(null) }
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
            var actions by remember { mutableStateOf<MessageActionSelection?>(null) }
            var replyOptions by remember { mutableStateOf<ComposerReply?>(null) }
            var focusReply by remember { mutableStateOf<ComposerReply?>(null) }
            val composerFocus = remember { FocusRequester() }
            val androidContext = LocalContext.current
            val attach = remember { DirectAttachmentControls() }
            val composerDialog = actions != null || replyOptions != null || state.authorDialog != null || state.reactionDialog != null ||
                attach.modalOpen || state.attachmentReview != null
            fun paneInteractive() = isCurrentEntry() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !obscured && !blockConfirm && !detailsOpen
            fun interactive() = paneInteractive() && model.state.value.authorDialog == null && model.state.value.reactionDialog == null
            val checkAuthorHistory: (DirectAuthorUnknown) -> Unit = { original ->
                if (interactive()) model.checkAuthorHistory(displayedSession, original)
            }
            val checkReactionHistory: (DirectReactionUnknown) -> Unit = { original ->
                if (interactive() && actions == null && replyOptions == null)
                    model.checkReactionHistory(displayedSession, original)
            }
            fun openReactions(message: DirectMessage) {
                if (interactive() && actions == null && replyOptions == null && model.beginReactions(displayedSession, peer, message))
                    focus.clearFocus()
            }
            fun openActions(message: DirectMessage) {
                if (interactive() && actions == null && replyOptions == null && model.canUseMessage(displayedSession, peer, message)) {
                    focus.clearFocus(); actions = MessageActionSelection(message.id)
                }
            }
            val selectedReply = state.composerReply
            val original = selectedReply?.let { choice -> state.messages.firstOrNull { it.id == choice.messageId } }
            val replyTitle = if (original != null && !original.deleted)
                stringResource(R.string.composer_reply_author, original.sender.username ?: stringResource(R.string.conversation_unknown_user))
                else stringResource(R.string.composer_reply_id, selectedReply?.messageId ?: 0)
            val replyPreview = when {
                original?.deleted == true -> stringResource(R.string.conversation_deleted)
                original == null -> stringResource(R.string.composer_reply_not_visible)
                else -> messageExcerpt(original.body.orEmpty())
            }
            fun showReplyOptions(choice: ComposerReply) {
                if (interactive() && actions == null && replyOptions == null && model.state.value.composerReply === choice) {
                    focus.clearFocus(); replyOptions = choice
                }
            }
            LaunchedEffect(state.composerReply, obscured, lifecycleState, state.authorDialog, state.reactionDialog, state.busy) {
                if (replyOptions != null && replyOptions !== state.composerReply) replyOptions = null
                if (obscured || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    actions = null; replyOptions = null; focusReply = null; detailsOpen = false; blockConfirm = false
                    // Navigation/background retires a private proposal, never an active POST.
                    model.state.value.authorDialog?.let { if (!model.state.value.busy) model.dismissAuthor(it.request) }
                    model.state.value.reactionDialog?.let { if (!model.state.value.busy) model.dismissReactions(it.request) }
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
            LaunchedEffect(state.messages, state.busy, state.stale, state.latestWindow, obscured, composerDialog, detailsOpen, blockConfirm, lifecycle, lifecycleState) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    snapshotFlow {
                        val info = timeline.layoutInfo
                        val readable = canAcknowledgeVisibleRead(lifecycleState, window.isWindowFocused,
                            !obscured && !composerDialog && !detailsOpen && !blockConfirm)
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
            val dmDrafts by model.drafts.collectAsStateWithLifecycle()
            val attachPolicy = session.attachmentPolicy
            val attachAvailable = attachAvailability(attachPolicy)
            val attachVisible = session.user?.guest == false && attachPolicy != AttachmentPolicy.Unavailable &&
                state.context?.let { it.relationship.messagingAvailable && it.mutedEverywhere == null } == true
            // The drafts flow is the recomposition signal; the VM's actual-entry query is the authority.
            val attachment = dmDrafts[peer]?.attachment?.let { model.visibleAttachment(displayedSession, peer, isCurrentEntry) }
            val longCaption = attachment != null && captionTooLong(state.draft)
            val attachmentFacts = attachment?.let { it.cardFacts(model.attachmentPolicyObservation(displayedSession, peer, it, isCurrentEntry), longCaption) }
            LaunchedEffect(attachAvailable) {
                if (attachAvailable == AttachAvailability.AVAILABLE) { attach.invalidChecked = false
                    if (attach.notice != AttachNotice.NOT_ADDED) attach.notice = null }
            }
            val attachSettled = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && model.canBeginAttachment()
            // Display gate only; sendAttachment rechecks the original entry, slot, READY bytes, disclosure and policy.
            val attachmentSendable = attachment?.phase == DirectAttachmentPhase.READY && !longCaption && ready && attachSettled &&
                attachmentFacts?.observation.let { it == AttachmentPolicyObservation.Unadvertised || it == AttachmentPolicyObservation.WithinAdvertisedFacts }
            // A confirmed upload adopts its canonical row only through a fresh actual entry; never a resend.
            LaunchedEffect(attachment, attachSettled, state.busy) {
                val confirmed = attachment?.takeIf { it.phase == DirectAttachmentPhase.CONFIRMED } ?: return@LaunchedEffect
                if (lifecycleState.isAtLeast(Lifecycle.State.STARTED) && !state.busy)
                    model.adoptConfirmedAttachment(displayedSession, peer, confirmed, isCurrentEntry)
            }
            val attachCannotWait = !attachVisible || attachAvailable != AttachAvailability.AVAILABLE || state.uncertainSend || state.uncertainBlock
            DirectAttachmentHost(attach, model, displayedSession, peer, completionEntryId, isCurrentEntry,
                interactive = { interactive() && actions == null && replyOptions == null }, settled = attachSettled, waitingCanEnd = attachCannotWait)
            fun onAttach() {
                if (!interactive() || actions != null || replyOptions != null) return
                when (attachAvailable) {
                    AttachAvailability.SERVER_OFF -> attach.notice = AttachNotice.SERVER_OFF
                    AttachAvailability.SETTINGS_UNREADABLE -> attach.notice = AttachNotice.SETTINGS_UNREADABLE
                    AttachAvailability.AVAILABLE -> {
                        if (!model.canBeginAttachment()) return
                        val current = model.visibleAttachment(displayedSession, peer, isCurrentEntry)
                        focus.clearFocus()
                        attach.notice = null
                        if (current == null) { attach.sheetOriginal = null; attach.sheet = true }
                        else if (current.removable()) attach.replacing = current
                    }
                }
            }
            val attachmentCard: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { cardLimit -> attachmentFacts?.let { facts ->
                val original = attachment
                // An upload itself sets sending; its Cancel must stay usable while the body is still being written.
                AttachmentCard(facts, enabled = !obscured && !composerDialog && (!state.sending || original.phase == DirectAttachmentPhase.UPLOADING),
                    onRemove = original.takeIf { it.removable() }?.let { { if (interactive() && actions == null && replyOptions == null)
                        model.removeAttachment(displayedSession, peer, it, isCurrentEntry) } },
                    onSendAnyway = original.takeIf { it.phase == DirectAttachmentPhase.PRIVACY_CHOICE }?.let { { if (interactive() &&
                        actions == null && replyOptions == null) model.prepareOriginalPhoto(displayedSession, peer, it, isCurrentEntry) } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    unknownActions = original.takeIf { it.phase == DirectAttachmentPhase.UNKNOWN }?.let { unknown -> {
                        TextButton(onClick = { if (interactive() && actions == null && replyOptions == null)
                            model.checkAttachmentHistory(displayedSession, peer, completionEntryId, unknown, isCurrentEntry) },
                            enabled = ready && !state.busy && !obscured && state.attachmentReview == null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("attachment_card_check_history")) {
                            Text(stringResource(R.string.attach_check_history))
                        }
                    } },
                    onCancel = original.takeIf { it.phase == DirectAttachmentPhase.UPLOADING && it.transfer?.fullBodyReported != true }?.let { {
                        if (isCurrentEntry() && !obscured) model.cancelAttachmentUpload(displayedSession, peer, it, isCurrentEntry) } }, maxHeight = cardLimit)
            } }
            val attachNotices: @Composable () -> Unit = {
                AttachNotices(attach, displayedSession, persisting = attach.invalidChecked && !session.busy &&
                    attachPolicy == AttachmentPolicy.Invalid, canCheckAgain = !session.busy && now >= session.retryAtMillis,
                    onCheckAgain = { if (isCurrentEntry()) onRefreshSession() })
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxHeight < 240.dp
                val replyInline = compact
                val replyControl: @Composable (Boolean) -> Unit = { inline -> selectedReply?.let { choice ->
                    ComposerReplyControl(inline, replyTitle, replyPreview, !state.sending && !obscured && !composerDialog,
                        onOptions = { showReplyOptions(choice) }, onCancel = {
                            if (interactive() && actions == null && replyOptions == null) model.cancelReply(displayedSession, peer, choice)
                        })
                } }
                val headerLimit = maxHeight * 0.3f
                val composerLimit = (maxHeight * 0.45f).coerceAtLeast(64.dp)
                val inlineCompletion=maxHeight>=360.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale<=1.3f && selectedReply==null
                val completionSource=profileDetailsToken
                val completionPeerName=state.context?.peer?.username.orEmpty()
                fun completionAllowed(): Boolean {
                    val current=model.state.value;val owner=model.session.value
                    return isCurrentEntry() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        (window.isWindowFocused || detailsOpen && completionSource!=null && profileDetailsToken===completionSource) &&
                        !obscured && !blockConfirm && actions==null && replyOptions==null && model.belongsTo(displayedSession) &&
                        current.peerId==peer && current.context?.peer?.username==completionPeerName && current.loaded &&
                        !current.busy && !current.stale && !current.sending && current.authorDialog==null && current.reactionDialog==null &&
                        model.canEditDraft() && !owner.busy && !owner.refreshRequired && !owner.storageWarning && System.currentTimeMillis()>=owner.retryAtMillis
                }
                val completionComposer=io.github.thiesi.chitchat.feature.completion.rememberComposerCompletion(completion,displayedSession,
                    io.github.thiesi.chitchat.feature.completion.CompletionTarget.Direct(peer,completionPeerName),completionEntryId,state.draft,
                    currentBody={ model.state.value.takeIf { model.belongsTo(displayedSession) && it.peerId==peer }?.draft },
                    canEdit={ fromDetails -> if (fromDetails) completionAllowed() && detailsOpen && completionSource!=null && profileDetailsToken===completionSource
                        else interactive() && actions==null && replyOptions==null },canOffer=::completionAllowed,
                    write={ model.updateDraft(displayedSession,peer,it) },
                    onTextChanged={ model.typing?.input(displayedSession,TypingTarget.Direct(peer),hasTypingContent(it)) },inlineChoices=inlineCompletion && !detailsOpen)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!compact) Column(Modifier.fillMaxWidth().heightIn(max = headerLimit).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        SessionNotice(session, now, onRefreshSession)
                        Text(state.context?.peer?.username ?: stringResource(R.string.dm_title), style = MaterialTheme.typography.titleLarge)
                        state.context?.let { Text(stringResource(R.string.dm_privacy_short, it.privacy.direct.retention), style = MaterialTheme.typography.bodySmall) }
                        ThreadFeedback(model, state, attention[peer], ready, onAuthorCheck = checkAuthorHistory,
                            onReactionCheck = checkReactionHistory)
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
                            val highlighted = state.highlightedMessageId == message.id
                            Column(Modifier.fillMaxWidth().testTag("direct:${message.id}")
                                .then(if (highlighted) Modifier.semantics { selected = true } else Modifier)) {
                                if (highlighted) RequestedMessageMarker(message.id)
                                if (message.id == state.unreadDividerId) Text(stringResource(R.string.dm_new_divider), style = MaterialTheme.typography.labelMedium)
                                DirectMessageRow(message, state.context, targets?.let { coordinator -> { id ->
                                    if (interactive() && actions == null && replyOptions == null) {
                                        focus.clearFocus()
                                        coordinator.load(displayedSession, MessageTarget.DirectMessage(peer, id))
                                    }
                                    Unit
                                } }, onActions = { openActions(message) }, viewer = displayedSession.user?.id ?: 0,
                                    canReact = interactive() && actions == null && replyOptions == null &&
                                        model.canBeginReactions(displayedSession, peer, message),
                                    onReactions = { openReactions(message) })
                            }
                        }
                        item("end") { Spacer(Modifier.height(1.dp)) }
                    }
                    TypingStatus(model.typing, session, TypingTarget.Direct(peer),
                        viewing = state.loaded && !state.stale && !blockConfirm && !detailsOpen && !composerDialog && !obscured && state.context?.relationship?.messagingAvailable == true,
                        publishing = ready && !composerDialog && !detailsOpen && !blockConfirm && !obscured && model.canSend())
                    if (!replyInline) replyControl(false)
                    if (!compact) { attachNotices(); attachmentCard(headerLimit * 0.5f) }
                    if (inlineCompletion && !detailsOpen) io.github.thiesi.chitchat.feature.completion.CompletionSuggestions(completionComposer)
                    Row(Modifier.fillMaxWidth().heightIn(max = composerLimit).padding(horizontal = if (compact) 4.dp else 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        run {
                            val context = state.context
                            val profileSource = profileDetailsToken
                            DirectPrivacyNotice(context?.privacy, if (completionComposer.choice!=null) R.string.completion_details else R.string.dm_details, compact = true,
                                expanded = detailsOpen, onExpandedChange = {
                                    if (it && !detailsOpen) profileDetailsToken = Any()
                                    if (!it) profileDetailsToken = null
                                    detailsOpen = it
                                },
                                uncertain = state.uncertainSend || state.uncertainRead || state.uncertainBlock ||
                                    state.authorUnknown != null || state.reactionUnknown != null,
                                failed = state.error != null || state.validation != null || state.readError != null ||
                                    (compact && attachmentFacts?.needsAttention == true),
                                needsAttention = (compact && (attachmentFacts != null || attach.notice != null || attach.picked != null)) ||
                                    completionComposer.choice!=null || state.stale || state.error != null || state.validation != null || state.newerAvailable ||
                                    state.uncertainSend || state.uncertainRead || state.readError != null || state.uncertainBlock ||
                                    state.authorUnknown != null || state.authorLimit || state.authorNotice != null ||
                                    state.reactionUnknown != null || state.reactionLimit || state.reactionNotice != null ||
                                    attention[peer] != null || state.draftLimit || !model.canEditDraft() ||
                                    context?.relationship?.messagingAvailable == false || context?.mutedEverywhere != null || !ready ||
                                    connection?.let { it.phase != RealtimePhase.LIVE || it.broadcasts.any { it.roomId == null } } == true) { dismiss ->
                                if (connection != null)
                                    RealtimeStatus(connection, model.realtime::retry, model.realtime::dismissBroadcast, roomId = null)
                                Text(context?.peer?.username ?: stringResource(R.string.dm_title), style = MaterialTheme.typography.titleMedium)
                                if (compact) { attachNotices(); attachmentCard(androidx.compose.ui.unit.Dp.Infinity) }
                                if (completionComposer.choice!=null) {
                                    Text(stringResource(R.string.completion_title))
                                    io.github.thiesi.chitchat.feature.completion.CompletionSuggestions(completionComposer,fromDetails=true,onAccepted={
                                        if (detailsOpen && profileSource!=null && profileDetailsToken===profileSource) dismiss()
                                    })
                                }
                                SessionNotice(session, now, onRefreshSession)
                                ThreadFeedback(model, state, attention[peer], ready, true, dismiss, checkAuthorHistory, checkReactionHistory)
                                if (onProfile != null && context != null) TextButton(onClick = {
                                    val current = model.state.value
                                    // Details is the producer modal. Keep it until the root accepts the exact peer.
                                    if (detailsOpen && profileSource != null && profileDetailsToken === profileSource &&
                                        isCurrentEntry() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                                        !obscured && model.belongsTo(displayedSession) && current.peerId == peer &&
                                        current.context === context && !current.busy && !current.stale &&
                                        current.authorDialog == null && current.reactionDialog == null && onProfile(peer)) {
                                        dismiss(); focus.clearFocus()
                                    }
                                }, enabled = ready && !state.busy && !state.stale && !obscured,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("dm_profile")) {
                                    Text(stringResource(R.string.profile_open))
                                }
                                if (context != null) TextButton(onClick = { dismiss(); blockConfirm = true }, enabled = ready && !state.busy && !state.uncertainBlock) {
                                    Text(stringResource(if (context.relationship.blockedByMe) R.string.dm_unblock else R.string.dm_block))
                                }
                            }
                        }
                        if (replyInline) replyControl(true)
                        MessageInput(completionComposer.value, completionComposer::input,
                            !state.sending && model.canEditDraft() && !composerDialog && !obscured,
                            stringResource(if (attachment != null) R.string.attach_caption else R.string.dm_message), compact, Modifier.weight(1f).onPreInterceptKeyBeforeSoftKeyboard(completionComposer::key).onPreviewKeyEvent(completionComposer::navigationKey).focusRequester(composerFocus).testTag("dm_composer"))
                        if (attachVisible) AttachButton(attachAvailable, enabled = !obscured && !composerDialog && !detailsOpen && !blockConfirm &&
                            !state.sending && (attachAvailable != AttachAvailability.AVAILABLE || (ready && model.canBeginAttachment() &&
                                attachment?.removable() != false)), onClick = ::onAttach, modifier = Modifier.testTag("dm_attach"))
                        MessageSendButton(stringResource(if (state.sending) R.string.conversation_sending else R.string.dm_send),
                            onClick = { if (interactive() && actions == null && replyOptions == null) {
                                val file = model.visibleAttachment(displayedSession, peer, isCurrentEntry)
                                if (file == null) model.send(displayedSession, peer)
                                else if (file === attachment && file.phase == DirectAttachmentPhase.READY)
                                    model.sendAttachment(displayedSession, peer, completionEntryId, file, isCurrentEntry)
                            } },
                            enabled = !composerDialog && !detailsOpen && !blockConfirm && !obscured && (attachmentSendable ||
                                (ready && model.canSend() && state.draft.phpTrim().isNotEmpty())),
                            sending = state.sending, modifier = Modifier.testTag("dm_send"))
                    }
                }
            }
            model.visibleAttachmentReview(displayedSession, peer, completionEntryId, isCurrentEntry)?.takeIf { paneInteractive() }?.let { review ->
                AttachmentReviewDialog(review.phase.shared(),
                    review.evidence.map { AttachmentEvidenceRow(it, it.filename, messageTime(it.sentAt, state.context?.display)) },
                    review.error?.let { directError(it) }, ready && !state.busy && review.phase != DirectAttachmentReviewPhase.CHECKING,
                    onConfirm = { key -> (key as? DirectAttachmentEvidence)?.let {
                        model.confirmAttachmentEvidence(displayedSession, peer, completionEntryId, review, it, isCurrentEntry) } },
                    onReject = { model.rejectAttachmentEvidence(displayedSession, peer, completionEntryId, review, isCurrentEntry) },
                    onAccept = { model.acceptAttachmentDuplicateRisk(displayedSession, peer, completionEntryId, review, isCurrentEntry) },
                    onCheckAgain = { model.visibleAttachment(displayedSession, peer, isCurrentEntry)?.let {
                        model.checkAttachmentHistory(displayedSession, peer, completionEntryId, it, isCurrentEntry) } },
                    onClose = { model.closeAttachmentReview(displayedSession, peer, completionEntryId, review, isCurrentEntry) })
            }
            actions?.let { request ->
                val message = state.messages.firstOrNull { it.id == request.messageId }
                val available = message != null && model.canUseMessage(displayedSession, peer, message)
                val links = remember(message) { message?.body?.let { messageLinks(it) }.orEmpty() }
                MessageActions(available, message?.let { model.canReplyTo(displayedSession, peer, it) } == true,
                    !message?.body.isNullOrEmpty(), onReply = {
                        if (actions === request && interactive() && message != null && model.replyTo(displayedSession, peer, message)) {
                            actions = null; focusReply = model.state.value.composerReply
                        }
                    }, onCopy = {
                        if (actions === request && interactive() && message != null && model.canUseMessage(displayedSession, peer, message)) {
                            message.body?.takeIf { it.isNotEmpty() }?.let { copyMessage(androidContext, it) }
                            actions = null
                        }
                    }, onDismiss = { if (actions === request) actions = null },
                    onEdit = message?.takeIf { it.sender.id == displayedSession.user?.id && it.canEdit }?.let { original -> {
                        if (actions === request && interactive() && model.beginAuthor(displayedSession, peer, original, AuthorChangeKind.EDIT)) actions = null
                    } }, canEdit = message?.let { model.canBeginAuthor(displayedSession, peer, it, AuthorChangeKind.EDIT) } == true,
                    onDelete = message?.takeIf { it.sender.id == displayedSession.user?.id && it.canDelete }?.let { original -> {
                        if (actions === request && interactive() && model.beginAuthor(displayedSession, peer, original, AuthorChangeKind.DELETE)) actions = null
                    } }, canDelete = message?.let { model.canBeginAuthor(displayedSession, peer, it, AuthorChangeKind.DELETE) } == true,
                    onReactions = message?.let { original -> {
                        if (actions === request && interactive() &&
                            model.beginReactions(displayedSession, peer, original)) {
                            actions = null; focus.clearFocus()
                        }
                    } }, canReact = message?.let { model.canBeginReactions(displayedSession, peer, it) } == true,
                    links = links, onOpenLink = { target ->
                        if (actions === request && interactive() && message != null &&
                            model.canUseMessage(displayedSession, peer, message) && links.any { it.target == target }) {
                            openMessageLink(androidContext, target)
                        }
                    })
            }
            state.authorDialog?.takeIf { paneInteractive() }?.let { dialog ->
                val request = dialog.request
                val pending = state.authorUnknown?.takeIf { it.peerId == peer && it.messageId == request.original.id && it.kind == request.kind }
                DirectAuthorChangeDialog(dialog, model.authorTargetCurrent(request), model.authorAvailable(request), state.busy,
                    onChange = { if (paneInteractive()) model.updateAuthorBody(request, it) },
                    onConfirm = { if (paneInteractive()) when {
                        pending != null -> model.checkAuthorHistory(displayedSession, pending)
                        dialog.checked -> model.reviewAuthorProposal(request)
                        else -> model.submitAuthor(request)
                    } }, onDismiss = { if (isCurrentEntry()) model.dismissAuthor(request) }, pending = pending,
                    canConfirm = when {
                        pending != null -> ready && !state.busy
                        dialog.checked -> model.canReviewAuthor(request)
                        else -> model.authorAvailable(request)
                    }, currentPreview = model.authorCurrentPreview(request), error = state.error?.let { directError(it) })
            }
            state.reactionDialog?.takeIf { paneInteractive() }?.let { dialog ->
                val request = dialog.request
                val current = model.reactionCurrentRow(request)
                val pending = state.reactionUnknown?.takeIf { it.peerId == peer && it.messageId == request.original.id }
                MessageReactionPicker(request, request.original.id, current?.reactions, current?.deleted == true,
                    displayedSession.user?.id ?: 0, state.busy, dialog.checked,
                    current != null && current !== request.original, pending?.messageId,
                    "dm_reactions_dialog", "dm_reaction_choices", ready && !state.busy,
                    model.canReviewReactions(request), state.error?.let { directError(it) },
                    canChoose = { emoji, desired -> paneInteractive() && model.canSetReaction(request, emoji, desired) },
                    onChoose = { emoji, desired -> if (paneInteractive()) model.setReaction(request, emoji, desired) },
                    onCheck = { if (paneInteractive() && pending != null) model.checkReactionHistory(displayedSession, pending) },
                    onReview = { if (paneInteractive()) model.reviewReactions(request) },
                    onDismiss = { if (isCurrentEntry()) model.dismissReactions(request) })
            }
            replyOptions?.let { choice ->
                val current = state.composerReply === choice
                ComposerReplyOptions(if (current) replyTitle else stringResource(R.string.composer_reply_id, choice.messageId),
                    if (current) replyPreview else stringResource(R.string.composer_reply_not_visible), current && !state.sending,
                    onOpenOriginal = targets?.takeIf { current && ready && !state.busy }?.let { coordinator -> {
                        if (replyOptions === choice && interactive() && model.state.value.composerReply === choice) {
                            replyOptions = null; focus.clearFocus()
                            coordinator.load(displayedSession, MessageTarget.DirectMessage(peer, choice.messageId))
                        }
                    } }, onCancel = {
                        if (replyOptions === choice && interactive() && model.state.value.composerReply === choice) {
                            model.cancelReply(displayedSession, peer, choice); replyOptions = null
                        }
                    }, onDismiss = { if (replyOptions === choice) replyOptions = null })
            }
            if (blockConfirm && state.context != null) {
                val blocked = state.context!!.relationship.blockedByMe
                AlertDialog(onDismissRequest = { blockConfirm = false }, title = { Text(stringResource(if (blocked) R.string.dm_unblock else R.string.dm_block)) },
                    text = { Text(stringResource(R.string.dm_block_help)) },
                    confirmButton = { TextButton(onClick = { blockConfirm = false; model.setBlocked(displayedSession, peer, !blocked) }, Modifier.testTag("dm_confirm_block")) { Text(stringResource(if (blocked) R.string.dm_unblock else R.string.dm_block)) } },
                    dismissButton = { TextButton(onClick = { blockConfirm = false }) { Text(stringResource(R.string.cancel)) } }, modifier = Modifier.lanternDialogEdge())
            }
        }
    }
}

@Composable
private fun ThreadFeedback(model: DirectThreadViewModel, state: DirectThreadState, attention: DirectAttention?,
    ready: Boolean, alwaysRefresh: Boolean = false, dismiss: () -> Unit = {}, onAuthorCheck: (DirectAuthorUnknown) -> Unit,
    onReactionCheck: (DirectReactionUnknown) -> Unit) {
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
    if (state.authorLimit || model.authorCapacityFull()) Text(stringResource(R.string.author_limit))
    state.authorUnknown?.let { original ->
        Text(stringResource(R.string.author_unconfirmed, original.messageId.toString()))
        if (state.authorDialog == null) TextButton(onClick = { dismiss(); onAuthorCheck(original) },
            enabled = ready && !state.busy, modifier = Modifier.testTag("author_check_history")) {
            Text(stringResource(R.string.author_check_history))
        }
    }
    state.authorNotice?.let { notice -> Text(stringResource(when (notice) {
        "author_edited" -> R.string.author_edited
        "author_deleted" -> R.string.author_deleted
        else -> R.string.author_checked
    }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (state.reactionLimit || model.reactionCapacityFull()) Text(stringResource(R.string.dm_reaction_limit))
    state.reactionUnknown?.let { original ->
        Text(stringResource(R.string.reaction_unconfirmed, original.messageId.toString()))
        if (state.reactionDialog == null) TextButton(onClick = { dismiss(); onReactionCheck(original) },
            enabled = ready && !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("reaction_check_history")) {
            Text(stringResource(R.string.reaction_check_history))
        }
    }
    state.reactionNotice?.let { notice -> Text(stringResource(if (notice == "reaction_changed")
        R.string.reaction_changed else R.string.reaction_checked), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
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
private fun DirectMessageRow(message: DirectMessage, context: DirectContext?, onOpenReply: ((Long) -> Unit)? = null,
    onActions: (() -> Unit)? = null, viewer: Long = 0, canReact: Boolean = false, onReactions: (() -> Unit)? = null) {
    val actionsLabel = stringResource(R.string.message_actions)
    val mentionHints = remember(message.mentions) { MessageMentions.direct(message.mentions) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
    val tint = if (message.outgoing) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.medium,
        color = tint.compositeOver(MaterialTheme.colorScheme.background), contentColor = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.conversation_author_time, message.sender.username ?: stringResource(R.string.conversation_unknown_user),
            messageTime(message.createdAt, context?.display)), style = MaterialTheme.typography.labelMedium)
        if (message.deleted) Text(stringResource(R.string.conversation_deleted)) else {
            message.replyTo?.let { reply ->
                val original = reply.message
                val text = when {
                    !reply.available || original == null -> stringResource(R.string.conversation_reply_unavailable)
                    original.deleted -> stringResource(R.string.conversation_deleted)
                    else -> stringResource(R.string.conversation_reply, original.sender.username.orEmpty(), messageExcerpt(original.body))
                }
                ReplyPreview(text, onOpenReply?.takeIf { reply.available && reply.message != null }?.let { open -> { open(reply.messageId) } },
                    Modifier.testTag("direct_reply:${message.id}"))
            }
            Column(Modifier.then(if (onActions != null) Modifier.heightIn(min = 48.dp)
                .combinedClickable(onClickLabel = actionsLabel, onLongClickLabel = actionsLabel,
                    onClick = onActions, onLongClick = onActions) else Modifier)) {
                message.body?.let { MessageBody(it, mentions = mentionHints) }
                message.attachment?.let { Text(stringResource(R.string.conversation_attachment, it.name, it.mimeType, it.sizeBytes.toString())) }
                if (message.editedAt != null) Text(stringResource(R.string.conversation_edited), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (message.reactions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            message.reactions.forEachIndexed { index, group ->
                val emoji = ReactionEmoji.fromValue(group.emoji)
                val label = if (emoji != null) reactionLabel(emoji) else group.emoji
                val description = pluralStringResource(R.plurals.reaction_chip_description, group.users.size, label, group.users.size)
                FilterChip(selected = group.selectedBy(viewer), onClick = { onReactions?.invoke() }, enabled = canReact && onReactions != null,
                    label = { Text("${group.emoji} ${group.users.size}") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("dm_reaction:${message.id}:$index")
                        .semantics { contentDescription = description })
            }
        }
        if (message.outgoing) Text(stringResource(if (message.readAt == null) R.string.dm_sent else R.string.dm_read), style = MaterialTheme.typography.labelSmall)
    }
    }
    }
}

@Composable
internal fun DirectPrivacyNotice(privacy: MessagePrivacy?, title: Int = R.string.dm_privacy, compact: Boolean = false,
    needsAttention: Boolean = false,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    uncertain: Boolean = false, failed: Boolean = false,
    actions: @Composable (() -> Unit) -> Unit = {}) {
    if (!compact) privacy?.let { Text(stringResource(R.string.dm_privacy_short, it.direct.retention), style = MaterialTheme.typography.bodySmall) }
    val label = stringResource(title)
    val status = stringResource(R.string.dm_details_attention)
    BadgedBox(badge = { if (needsAttention) AttentionBadge(uncertain=uncertain,failed=failed) }) {
        if (!compact) TextButton(onClick = { onExpandedChange(true) }, modifier = Modifier.semantics {
            if (needsAttention) stateDescription = status
        }) { Text(label) }
        else {
            IconButton(onClick = { onExpandedChange(true) }, modifier = Modifier.semantics {
                contentDescription = label
                if (needsAttention) stateDescription = status
            }) { Text("\u22ee") }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { onExpandedChange(false) }, title = { Text(stringResource(title)) }, text = {
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
            actions { onExpandedChange(false) }
        }
    }, confirmButton = { TextButton(onClick = { onExpandedChange(false) }) { Text(stringResource(R.string.dm_close)) } }, modifier = Modifier.lanternDialogEdge())
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
    error.code == "invalid_reply_target" -> R.string.composer_reply_invalid
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
