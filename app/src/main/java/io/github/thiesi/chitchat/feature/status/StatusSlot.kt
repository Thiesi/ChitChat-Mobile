package io.github.thiesi.chitchat.feature.status

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.realtime.RealtimePhase
import io.github.thiesi.chitchat.core.realtime.RealtimeState
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.status.BACK_ONLINE_MILLIS
import io.github.thiesi.chitchat.core.status.RECONNECT_GRACE_MILLIS
import io.github.thiesi.chitchat.core.status.StatusInputs
import io.github.thiesi.chitchat.core.status.StatusLevel
import io.github.thiesi.chitchat.core.status.StatusNotice
import io.github.thiesi.chitchat.core.status.Wait
import io.github.thiesi.chitchat.core.status.isDisconnection
import io.github.thiesi.chitchat.core.status.resolveStatus
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The installation's display name: `application.name`, else the host. */
internal fun SessionState.installationDisplayName(): String =
    installationName ?: server?.value?.toHttpUrlOrNull()?.host.orEmpty()

/**
 * The one status slot under the top bar (status-presentation.md, P-100 to P-102). Its only timers are the
 * row-8 five-second one-shot and the Back online dismissal: both are bound to this composition, never run
 * while the app isn't visible, and carry no elapsed time across the background (P-105 as amended).
 */
@Composable
internal fun StatusSlot(
    session: SessionState,
    realtime: RealtimeState?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onStorageAction: () -> Unit,
    modifier: Modifier = Modifier,
    screen: StatusNotice.Screen? = null,
    syncing: Boolean = false,
    stale: Boolean = false,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val visible = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val phase = realtime?.phase ?: RealtimePhase.STOPPED
    val reconnecting = phase == RealtimePhase.CONNECTING || phase == RealtimePhase.RECONNECTING
    // Saveable so a configuration change keeps the row-8 count (uptime is device-wide); the background still clears it.
    var reconnectingSince by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var monotonicNow by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var disconnected by remember { mutableStateOf(false) }
    var recovered by remember { mutableStateOf(false) }

    // Row 8: start counting on entering CONNECTING/RECONNECTING while visible; leaving either state or the
    // foreground drops the count, so nothing carries over.
    LaunchedEffect(reconnecting, visible) {
        if (!reconnecting || !visible) { reconnectingSince = null; return@LaunchedEffect }
        val now = SystemClock.uptimeMillis()
        val since = reconnectingSince ?: now.also { reconnectingSince = it }
        monotonicNow = now
        delay((since + RECONNECT_GRACE_MILLIS - now).coerceAtLeast(0))
        monotonicNow = since + RECONNECT_GRACE_MILLIS
    }
    // Row 9: the first LIVE after a disconnection notice shows Back online for about two seconds. Not keyed
    // on `disconnected`: clearing it here must not cancel this effect's own dismissal (PR221 review).
    // A flap out of LIVE during the window cancels this effect; finally clears it (PR221 second review).
    LaunchedEffect(phase, visible) {
        // A recovery that happened while away isn't announced on return.
        if (!visible) disconnected = false
        if (!visible || phase != RealtimePhase.LIVE) { recovered = false; return@LaunchedEffect }
        if (disconnected) {
            recovered = true; disconnected = false
            try { delay(BACK_ONLINE_MILLIS) } finally { recovered = false }
        }
    }

    // P-25: the existing session wait gate re-renders when it ends, so row 7 clears and Refresh session
    // enables. Under a minute it counts down each second; longer waits wake once when under a minute.
    var clock by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(session.retryAtMillis, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            clock = nowMillis()
            val remaining = session.retryAtMillis - clock
            if (remaining <= 0) break
            delay(if (remaining <= 60_000) minOf(1_000L, remaining) else remaining - 60_000)
        }
    }

    val inputs = StatusInputs(
        phase = phase,
        errorKind = realtime?.error?.kind,
        storageWarning = session.storageWarning,
        refreshRequired = session.refreshRequired,
        nowMillis = clock,
        retryAtMillis = session.retryAtMillis,
        retryAfterKnown = session.error?.status == 429 && (session.error?.retryAfterSeconds ?: 0L) > 0L,
        reconnectingSinceMillis = reconnectingSince,
        monotonicNowMillis = monotonicNow,
        recovered = recovered,
        screen = screen,
        syncing = syncing,
        stale = stale,
    )
    val notice = resolveStatus(inputs)
    LaunchedEffect(notice, visible) { if (visible && notice.isDisconnection()) disconnected = true }
    if (notice != null && session.phase == SessionPhase.AUTHENTICATED) key(notice.announcementKey()) {
        StatusNoticeView(notice, session, onRetry, onRefresh, onStorageAction, { clock }, modifier)
    }
}

@Composable
private fun StatusNoticeView(
    notice: StatusNotice, session: SessionState, onRetry: () -> Unit, onRefresh: () -> Unit,
    onStorageAction: () -> Unit, nowMillis: () -> Long, modifier: Modifier,
) {
    val colors = ChitChatTheme.colors
    val host = session.installationDisplayName()
    // Announced politely once per notice: the key above recreates this node only when the notice changes.
    val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (notice.level == StatusLevel.CAPTION) {
        // A seconds countdown changes its text every second; screen readers get one stable line instead.
        val spoken = if (notice is StatusNotice.RateLimited && notice.wait is Wait.Seconds)
            stringResource(R.string.status_rate_limited_under_minute) else null
        val semantics = if (spoken != null) Modifier.clearAndSetSemantics { testTag = "status_slot"; contentDescription = spoken; liveRegion = LiveRegionMode.Polite }
            else announce
        Text(captionText(notice), modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).then(semantics)
            .testTag("status_slot"), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        return
    }
    val (title, body) = blockText(notice, host)
    val background: Color = when (notice) {
        StatusNotice.Offline, StatusNotice.Unreachable, StatusNotice.BackOnline -> colors.raised
        StatusNotice.SecureConnectionFailed, StatusNotice.Incompatible, StatusNotice.LiveUpdatesStopped -> colors.surface
        else -> colors.presenceWash
    }
    val (glyph, tint) = when (notice) {
        StatusNotice.SecureConnectionFailed, StatusNotice.Incompatible, StatusNotice.LiveUpdatesStopped -> R.drawable.ic_error to colors.error
        StatusNotice.StorageWarning, StatusNotice.RefreshRequired, StatusNotice.Unreachable, StatusNotice.Offline -> R.drawable.ic_warning to colors.warning
        else -> R.drawable.ic_info to colors.textMuted
    }
    Column(modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp, vertical = 12.dp).testTag("status_slot"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(glyph), contentDescription = null, Modifier.size(20.dp), tint = tint)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                body?.let { append(' '); append(it) }
            }, Modifier.weight(1f).then(announce), style = MaterialTheme.typography.bodyMedium, color = colors.text)
        }
        val action: Pair<Int, () -> Unit>? = when (notice) {
            StatusNotice.SecureConnectionFailed, StatusNotice.Incompatible, StatusNotice.LiveUpdatesStopped -> R.string.retry to onRetry
            StatusNotice.RefreshRequired -> R.string.refresh_session to onRefresh
            StatusNotice.StorageWarning -> R.string.retry_secure_storage to onStorageAction
            else -> null
        }
        action?.let { (label, onClick) ->
            // Refresh session keeps its existing gate: disabled until the server's wait is over.
            val enabled = notice != StatusNotice.RefreshRequired || (!session.busy && nowMillis() >= session.retryAtMillis)
            TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).padding(start = 20.dp)
                .testTag("status_action")) { Text(stringResource(label)) }
        }
    }
}

/**
 * What makes a notice "new" for the polite announcement: its kind, and for a wait only a coarse bucket, so a
 * countdown keeps one node and is announced once (PR221 review). Minute and hour buckets change rarely.
 */
private fun StatusNotice.announcementKey(): Any = when (this) {
    is StatusNotice.RateLimited -> when (val w = wait) {
        null -> "rate:unknown"
        is Wait.Seconds -> "rate:seconds"
        Wait.AboutAMinute -> "rate:minute"
        is Wait.AboutMinutes -> "rate:minutes:${w.minutes}"
        is Wait.AboutHours -> "rate:hours:${w.hours}"
    }
    else -> this
}

@Composable
private fun blockText(notice: StatusNotice, host: String): Pair<String, String?> = when (notice) {
    StatusNotice.StorageWarning -> stringResource(R.string.session_storage_warning) to null
    StatusNotice.Offline -> stringResource(R.string.status_offline_title) to stringResource(R.string.status_offline_body)
    StatusNotice.SecureConnectionFailed -> stringResource(R.string.status_tls_title, host) to stringResource(R.string.status_tls_body)
    StatusNotice.Incompatible -> stringResource(R.string.status_incompatible_title, host) to stringResource(R.string.status_incompatible_body)
    StatusNotice.LiveUpdatesStopped -> stringResource(R.string.status_stopped_title) to stringResource(R.string.status_stopped_body)
    StatusNotice.RefreshRequired -> stringResource(R.string.status_refresh_title) to null
    StatusNotice.Unreachable -> stringResource(R.string.status_unreachable_title, host) to stringResource(R.string.status_unreachable_body)
    StatusNotice.BackOnline -> stringResource(R.string.status_back_online) to null
    is StatusNotice.Screen, is StatusNotice.RateLimited, StatusNotice.Syncing, StatusNotice.Stale -> captionText(notice) to null
}

@Composable
private fun captionText(notice: StatusNotice): String = when (notice) {
    is StatusNotice.RateLimited -> notice.wait?.let { stringResource(R.string.status_rate_limited, waitText(it)) }
        ?: stringResource(R.string.status_rate_limited_unknown)
    StatusNotice.Syncing -> stringResource(R.string.status_syncing)
    StatusNotice.Stale -> stringResource(R.string.status_stale)
    else -> ""
}

@Composable
internal fun waitText(wait: Wait): String = when (wait) {
    is Wait.Seconds -> stringResource(R.string.wait_seconds, wait.seconds)
    Wait.AboutAMinute -> stringResource(R.string.wait_about_a_minute)
    is Wait.AboutMinutes -> pluralStringResource(R.plurals.wait_about_minutes, wait.minutes, wait.minutes)
    is Wait.AboutHours -> pluralStringResource(R.plurals.wait_about_hours, wait.hours, wait.hours)
}
