package io.github.thiesi.chitchat.feature.notifications

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.core.ui.messageTime
import kotlinx.coroutines.delay

/** Root lifecycle, never a back-stack pane's disposal, owns synchronization. */
@Composable
internal fun NotificationLifecycle(model: NotificationCenterViewModel, expected: SessionState, center: Boolean, entry: Any?) {
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.foreground(center) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (activity?.isChangingConfigurations != true) model.background() }
    LaunchedEffect(entry, expected.noticeIdentity()) { model.show(expected, center) }
}

@Composable
internal fun NotificationCenterScreen(model: NotificationCenterViewModel, expected: SessionState,
    refreshSession: () -> Unit, isCurrentEntry: () -> Boolean) {
    val observed by model.state.collectAsStateWithLifecycle()
    val value = observed.takeIf { model.belongsTo(expected) } ?: NotificationCenterState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expected.retryAtMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do { now = System.currentTimeMillis(); if (now < expected.retryAtMillis) delay(1000) }
            while (now < expected.retryAtMillis)
        }
    }
    val ready = model.belongsTo(expected) && !expected.busy && !expected.refreshRequired &&
        !expected.storageWarning && now >= expected.retryAtMillis
    val canGet = ready && !value.busy
    val canRead = canGet && value.loaded && !value.stale && !value.unknownRead
    val list = rememberLazyListState()
    LaunchedEffect(value.scrollRequest) { if (value.scrollRequest > 0) list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("notification_list"), state = list,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NotificationCount(value)
                Text(stringResource(R.string.notifications_read_scope), style = MaterialTheme.typography.bodySmall)
                if (value.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (value.writing) R.string.notifications_writing else R.string.notifications_loading))
                }
                if (expected.busy) Text(stringResource(R.string.checking_session))
                if (expected.refreshRequired || expected.storageWarning) TextButton(onClick = {
                    if (isCurrentEntry()) refreshSession()
                }, enabled = !expected.busy && now >= expected.retryAtMillis) { Text(stringResource(R.string.refresh_session)) }
                if (now < expected.retryAtMillis) Text(stringResource(R.string.rate_limited))
                value.error?.let { InlineError(notificationError(it)) }
                if (value.unknownRead) Text(stringResource(R.string.notifications_unknown_read),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (value.incompleteRange) Text(stringResource(R.string.notifications_incomplete))
                else if (value.loaded && value.stale) Text(stringResource(R.string.notifications_stale))
                if (!value.latestWindow) Text(stringResource(R.string.notifications_historical))
                OutlinedButton(onClick = { if (isCurrentEntry()) model.refresh(expected) }, enabled = canGet,
                    modifier = Modifier.fillMaxWidth().testTag("notifications_refresh")) { Text(stringResource(R.string.notifications_refresh)) }
                if (!value.latestWindow || value.incompleteRange) OutlinedButton(onClick = {
                    if (isCurrentEntry()) model.latest(expected)
                }, enabled = canGet, modifier = Modifier.fillMaxWidth().testTag("notifications_latest")) { Text(stringResource(R.string.notifications_latest)) }
                TextButton(onClick = { if (isCurrentEntry()) model.requestAll(expected) },
                    enabled = canRead && (value.unreadCount ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().testTag("notifications_all")) { Text(stringResource(R.string.notifications_mark_all)) }
                if (value.loaded && value.notifications.isEmpty()) Text(stringResource(
                    if (value.latestWindow) R.string.notifications_empty else R.string.notifications_empty_range))
            }
        }
        items(value.notifications, key = { "notification:${it.id}" }) { item ->
            val read = value.isRead(item)
            val status = stringResource(if (read) R.string.notifications_read else R.string.notifications_unread)
            Card(Modifier.fillMaxWidth().testTag("notification:${item.id}").semantics { stateDescription = status }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.labelMedium)
                    Text(messageTime(item.createdAt, expected.chatPolicy?.display), style = MaterialTheme.typography.bodySmall)
                    Text(item.message)
                    item.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    // Plain text only: no browser intent, auto-linking or fake View-message action.
                    if (item.link != null) Text(stringResource(R.string.notifications_link_pending), style = MaterialTheme.typography.bodySmall)
                    if (!read) TextButton(onClick = { if (isCurrentEntry()) model.markRead(expected, item.id) },
                        enabled = canRead, modifier = Modifier.testTag("notification_read:${item.id}")) {
                        Text(stringResource(R.string.notifications_mark_read))
                    }
                }
            }
        }
        item(key = "older") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (value.busy && value.notifications.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (value.notifications.isNotEmpty() && (value.stale || value.error != null)) {
                    Text(stringResource(R.string.notifications_stale))
                    OutlinedButton(onClick = { if (isCurrentEntry()) model.refresh(expected) }, enabled = canGet,
                        modifier = Modifier.fillMaxWidth().testTag("notifications_footer_refresh")) { Text(stringResource(R.string.notifications_refresh)) }
                }
                if (value.mayHaveOlder) OutlinedButton(onClick = { if (isCurrentEntry()) model.older(expected) },
                    enabled = canGet && value.loaded && !value.stale,
                    modifier = Modifier.fillMaxWidth().testTag("notifications_more")) { Text(stringResource(R.string.notifications_more)) }
            }
        }
    }
    value.allChoice?.let { choice ->
        AlertDialog(onDismissRequest = { if (isCurrentEntry()) model.cancelAll(choice) },
            title = { Text(stringResource(R.string.notifications_all_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.notifications_all_body)) } },
            confirmButton = { TextButton(onClick = { if (isCurrentEntry()) model.confirmAll(choice) },
                enabled = canRead, modifier = Modifier.testTag("notifications_all_confirm")) { Text(stringResource(R.string.notifications_mark_all)) } },
            dismissButton = { TextButton(onClick = { if (isCurrentEntry()) model.cancelAll(choice) }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun NotificationCount(value: NotificationCenterState) {
    val text = value.unreadCount?.let { pluralStringResource(if (value.countStale) R.plurals.notifications_last_count else R.plurals.notifications_count, it, it) }
        ?: stringResource(R.string.notifications_count_unknown)
    Text(text, Modifier.testTag("notifications_count"), style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun notificationError(error: ApiFailure) = stringResource(when {
    error.code == "csrf_failed" || error.kind == FailureKind.STORAGE -> R.string.session_refresh_needed
    error.kind == FailureKind.TLS -> R.string.tls_error
    error.kind == FailureKind.REDIRECT -> R.string.redirect_error
    error.kind == FailureKind.INCOMPATIBLE -> R.string.incompatible_server
    error.kind == FailureKind.EXPIRED || error.status == 401 -> R.string.session_expired
    error.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT) -> R.string.notifications_network
    error.status == 429 -> R.string.rate_limited
    error.status == 503 -> R.string.server_unavailable
    else -> R.string.request_rejected
})
