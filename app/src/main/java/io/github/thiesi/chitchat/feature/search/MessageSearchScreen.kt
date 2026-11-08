package io.github.thiesi.chitchat.feature.search

import androidx.compose.material3.MaterialTheme
import io.github.thiesi.chitchat.core.theme.lanternSurfaceBorder

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.direct.validatedDirectPrivacy
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.search.*
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.messageTime
import io.github.thiesi.chitchat.feature.direct.DirectPrivacyNotice
import kotlinx.coroutines.delay

private fun privacyReady(scope: MessageSearchScope, session: SessionState) =
    scope == MessageSearchScope.ROOMS || runCatching { validatedDirectPrivacy(session.privacy) }.isSuccess

@Composable
private fun SearchLifecycle(model: MessageSearchViewModel, expected: SessionState, id: String, isCurrentEntry: () -> Boolean) {
    val activity = LocalActivity.current
    val value by model.state.collectAsStateWithLifecycle()
    val entry = value.entry?.takeIf { it.navigationEntryId == id && it.matches(expected) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (isCurrentEntry()) model.open(expected, id) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) entry?.let(model::background) }
    LaunchedEffect(id, expected.server, expected.generation, expected.user?.id, expected.user?.sessionVersion, expected.phase) {
        if (isCurrentEntry()) model.open(expected, id)
    }
    DisposableEffect(model, entry, id) { onDispose {
        if (activity?.isChangingConfigurations != true) entry?.let(model::close)
    } }
}

@Composable
internal fun MessageSearchScreen(model: MessageSearchViewModel, expected: SessionState, navigationEntryId: String,
    refreshSession: () -> Unit,
    onOpen: ((MessageSearchEntry, MessageSearchResults, MessageSearchHit) -> Unit)? = null,
    isCurrentEntry: () -> Boolean) {
    SearchLifecycle(model, expected, navigationEntryId, isCurrentEntry)
    val observed by model.state.collectAsStateWithLifecycle()
    val entry = observed.entry?.takeIf { it.navigationEntryId == navigationEntryId && it.matches(expected) }
    if (entry == null) CircularProgressIndicator()
    else {
        val value = observed
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(expected.retryAtMillis, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { now = System.currentTimeMillis(); if (now < expected.retryAtMillis) delay(1000) } while (now < expected.retryAtMillis)
        } }
        var details by remember(entry) { mutableStateOf(false) }
        val privacy = runCatching { validatedDirectPrivacy(expected.privacy) }.getOrNull()
        val ready = !expected.busy && !expected.refreshRequired && !expected.storageWarning && now >= expected.retryAtMillis
        val permitted: (MessageSearchScope) -> Boolean = { scope ->
            isCurrentEntry() && entry.matches(model.session.value) && privacyReady(scope, model.session.value)
        }
        val submit = {
            if (permitted(value.scope) && model.state.value.query == value.query && model.state.value.scope == value.scope)
                model.search(entry)
            Unit
        }
        Column(Modifier.fillMaxSize().testTag("message_search_screen")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value.query, onValueChange = { if (isCurrentEntry()) model.query(entry, it) },
                    label = { Text(stringResource(R.string.message_search_query)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("message_search_query"), shape = MaterialTheme.shapes.medium)
                val pending = value.pending
                if (pending != null) key(pending) {
                    val cancelLabel = stringResource(R.string.message_search_cancel)
                    IconButton(onClick = { if (isCurrentEntry()) model.cancel(pending) },
                        modifier = Modifier.size(48.dp).testTag("message_search_cancel").semantics {
                            contentDescription = cancelLabel
                        }) { Text("×", style = MaterialTheme.typography.headlineSmall) }
                } else key(value.query, value.scope) {
                    val searchLabel = stringResource(R.string.message_search_submit)
                    IconButton(onClick = submit, enabled = ready && privacyReady(value.scope, expected) &&
                        value.query.isNotBlank() && !value.inputTooLong,
                        modifier = Modifier.size(48.dp).testTag("message_search_submit").semantics { contentDescription = searchLabel }) {
                        val ink = LocalContentColor.current
                        Canvas(Modifier.size(24.dp)) {
                            drawCircle(ink, 6.dp.toPx(), Offset(10.dp.toPx(), 10.dp.toPx()), style = Stroke(2.dp.toPx()))
                            drawLine(ink, Offset(14.5.dp.toPx(), 14.5.dp.toPx()), Offset(21.dp.toPx(), 21.dp.toPx()), 2.dp.toPx())
                        }
                    }
                }
                DirectPrivacyNotice(privacy, R.string.message_search_options, compact = true,
                    needsAttention = value.error != null || value.inputTooLong || !ready || !privacyReady(value.scope, expected),
                    expanded = details, onExpandedChange = { details = it }, actions = {
                        Text(stringResource(R.string.message_search_scope))
                        MessageSearchScope.entries.forEach { scope -> key(scope) {
                            FilterChip(selected = value.scope == scope, onClick = {
                                if (isCurrentEntry()) model.scope(entry, scope)
                            }, label = { Text(scopeName(scope)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("message_search_scope_${scope.name}"))
                        } }
                        if (privacy == null) Text(stringResource(R.string.message_search_privacy_missing))
                        TextButton(onClick = { if (isCurrentEntry() && entry.matches(model.session.value)) refreshSession() },
                            enabled = !expected.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("message_search_refresh")) {
                            Text(stringResource(R.string.refresh_session))
                        }
                        Text(stringResource(R.string.message_search_limit))
                        Text(stringResource(if (onOpen == null) R.string.message_search_open_pending else R.string.message_search_open_notice))
                    })
            }
            if (value.inputTooLong) Text(stringResource(R.string.message_search_input_limit), color = MaterialTheme.colorScheme.error)
            if (!privacyReady(value.scope, expected)) Text(stringResource(R.string.message_search_privacy_missing),
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            if (!ready) Text(stringResource(if (now < expected.retryAtMillis) R.string.message_search_rate else R.string.message_search_session),
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            if (value.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("message_search_loading"))
            val result = value.results
            val visible = result?.takeIf { privacyReady(it.attempt.request.scope, expected) }
            key(result) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("message_search_results"), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.message_search_scope_current, scopeName(value.scope)))
                        value.error?.let { failure ->
                            InlineError(stringResource(when {
                                failure.status == 400 -> R.string.message_search_validation
                                failure.status == 429 -> R.string.message_search_rate
                                failure.status in setOf(403, 404) && failure.code != "csrf_failed" -> R.string.message_search_access_lost
                                failure.kind == FailureKind.INCOMPATIBLE -> R.string.message_search_incompatible
                                else -> R.string.message_search_error
                            }))
                        }
                        val failed = value.failedAttempt
                        if (failed != null) key(failed) {
                            Text(stringResource(R.string.message_search_results_for, failed.request.query, scopeName(failed.request.scope)),
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { if (permitted(failed.request.scope)) model.retry(failed) },
                                enabled = ready && !value.busy && privacyReady(failed.request.scope, expected) && !value.inputTooLong,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("message_search_retry")) { Text(stringResource(R.string.retry)) }
                        }
                        if (visible != null) {
                            Text(stringResource(R.string.message_search_results_for, visible.attempt.request.query, scopeName(visible.attempt.request.scope)),
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (visible.attempt.request.scope != MessageSearchScope.ROOMS && privacy != null)
                                Text(stringResource(R.string.dm_privacy_short, privacy.direct.retention), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.message_search_moving), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(if (onOpen == null) R.string.message_search_open_pending else R.string.message_search_open_notice),
                                style = MaterialTheme.typography.bodySmall)
                            if (visible.page.results.isEmpty()) Text(stringResource(if (visible.accessFiltered)
                                R.string.message_search_removed_page else R.string.message_search_empty))
                            if (visible.page.hasMore && !visible.page.canLoadMore) Text(stringResource(R.string.message_search_ceiling))
                        } else Text(stringResource(if (result != null) R.string.message_search_privacy_missing else R.string.message_search_hint))
                    }
                    if (visible != null) items(visible.page.results, key = { row ->
                        (if (row is MessageSearchHit.Room) "room:" else "direct:") + row.messageId
                    }) { row ->
                        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, border = lanternSurfaceBorder()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(when (row) {
                                is MessageSearchHit.Room -> stringResource(R.string.message_search_room, row.room.name)
                                is MessageSearchHit.Direct -> stringResource(R.string.message_search_direct, row.peer.username)
                            }, style = MaterialTheme.typography.titleMedium)
                            Text(row.excerpt, Modifier.testTag("message_search_excerpt"))
                            Text(row.sender?.username ?: stringResource(R.string.message_search_sender_unknown), style = MaterialTheme.typography.bodySmall)
                            Text(messageTime(row.createdAt, expected.chatPolicy?.display) +
                                if (row.editedAt != null) stringResource(R.string.message_search_edited) else "", style = MaterialTheme.typography.bodySmall)
                            if (onOpen != null) TextButton(onClick = {
                                if (permitted(visible.attempt.request.scope)) onOpen(entry, visible, row)
                            }, enabled = ready && !value.busy && value.targetBinding == null,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("message_search_open:${if (row is MessageSearchHit.Room) "room" else "direct"}:${row.messageId}")) {
                                Text(stringResource(R.string.message_search_open_original))
                            }
                        } }
                    }
                }
                if (visible != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    val previousLabel = stringResource(R.string.message_search_previous)
                    val nextLabel = stringResource(R.string.message_search_next)
                    IconButton(onClick = { if (permitted(visible.attempt.request.scope)) model.previous(entry, visible) },
                        enabled = ready && !value.busy && visible.attempt.request.offset > 0,
                        modifier = Modifier.size(48.dp).testTag("message_search_previous").semantics { contentDescription = previousLabel }) {
                        Text(if (rtl) "›" else "‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(stringResource(R.string.message_search_offset,
                        visible.attempt.request.offset / MessageSearchViewModel.PAGE_SIZE + 1), Modifier.padding(top = 12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { if (permitted(visible.attempt.request.scope)) model.next(entry, visible) },
                        enabled = ready && !value.busy && visible.page.canLoadMore,
                        modifier = Modifier.size(48.dp).testTag("message_search_next").semantics { contentDescription = nextLabel }) {
                        Text(if (rtl) "‹" else "›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

@Composable private fun scopeName(scope: MessageSearchScope) = stringResource(when (scope) {
    MessageSearchScope.ALL -> R.string.message_search_all
    MessageSearchScope.ROOMS -> R.string.message_search_rooms
    MessageSearchScope.DIRECT -> R.string.message_search_direct_scope
})
