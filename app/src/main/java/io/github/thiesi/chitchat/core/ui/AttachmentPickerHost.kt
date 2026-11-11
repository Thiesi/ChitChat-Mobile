package io.github.thiesi.chitchat.core.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.phpTrim
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import java.net.URI
import kotlinx.coroutines.launch

/** Screen-local picker/dialog state for one pane, generic over the feature's private draft type.
 * Never saved: a URI, grant or original reference must not outlive this composition. A configuration
 * change during picking loses the original Replace binding, so the feature VM then refuses that
 * result instead of replacing a file without consent.
 */
@Stable
internal class AttachmentControls<D : Any> {
    var sheet by mutableStateOf(false)
    var replacing by mutableStateOf<D?>(null)
    var notice by mutableStateOf<AttachNotice?>(null)
    var invalidChecked by mutableStateOf(false)
    var picked by mutableStateOf<PickedFile<D>?>(null)
    /** Original selection a confirmed Replace is bound to, until its one picker returns. */
    var sheetOriginal: D? = null
    var pickOriginal: D? = null
    val modalOpen: Boolean get() = sheet || replacing != null
}

internal enum class AttachNotice { SERVER_OFF, SETTINGS_UNREADABLE, NOT_ADDED }
internal class PickedFile<D : Any>(val read: PickedAttachmentRead, val source: AttachmentPickSource, val original: D?) {
    override fun toString() = "PickedFile"
}

internal fun attachAvailability(policy: AttachmentPolicy): AttachAvailability = when {
    policy == AttachmentPolicy.Invalid -> AttachAvailability.SETTINGS_UNREADABLE
    policy is AttachmentPolicy.Known && policy.maxBytes <= 0 -> AttachAvailability.SERVER_OFF
    else -> AttachAvailability.AVAILABLE
}

/** Meaning of a definite outcome. Only reached while the body is provably incomplete or the server
 * answered with a refusal; anything after a possibly complete body is UNKNOWN, never mapped here.
 */
internal fun attachmentSendFailure(error: ApiFailure?, policyBlocked: Boolean): AttachmentSendFailure? = when {
    policyBlocked -> AttachmentSendFailure.PolicyChanged
    error == null -> null
    error.status == 413 -> AttachmentSendFailure.TooLarge(error.maxBytes)
    error.code == "attachment_type_not_allowed" || error.code == "attachment_type_unknown" -> AttachmentSendFailure.TypeRefused
    error.code == "attachment_empty" -> AttachmentSendFailure.Empty
    error.status == 429 -> AttachmentSendFailure.RateLimited(error.retryAfterSeconds)
    error.code == "membership_required" -> AttachmentSendFailure.Membership
    error.code == "direct_message_unavailable" -> AttachmentSendFailure.Unavailable
    error.status == null && error.kind in setOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.TLS) -> AttachmentSendFailure.NotSent
    else -> AttachmentSendFailure.Rejected
}

/** The caption limit both upload routes apply (PHP-trimmed, code points). */
internal fun captionTooLong(caption: String): Boolean { val trimmed = caption.phpTrim()
    return trimmed.codePointCount(0, trimmed.length) > 4_000 }

/** The installation name used in copy: the chosen server's host, never a guessed brand. */
internal fun installationName(session: SessionState): String =
    session.server?.value?.let { runCatching { URI(it).host }.getOrNull() } ?: ""

/** Pickers, the choice sheet, Replace confirmation and one-shot admission for one pane.
 * Only explicit person actions open a picker; only the feature's original-bound [admit] accepts a
 * file. Nothing here reads bytes, sends or retries a request.
 */
@Composable
internal fun <D : Any> AttachmentPickerHost(controls: AttachmentControls<D>, policy: AttachmentPolicy,
    rateLimit: UploadRateLimit?, interactive: () -> Boolean, settled: Boolean, waitingCanEnd: Boolean,
    admit: (PickedFile<D>) -> Boolean, stillReplaceable: (D) -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val override = LocalAttachmentPickerOverride.current
    val filters = attachmentPickFilters(policy)
    fun deliver(read: PickedAttachmentRead?, source: AttachmentPickSource) {
        val original = controls.pickOriginal
        controls.pickOriginal = null
        if (read != null) controls.picked = PickedFile(read, source, original)
    }
    fun received(uri: android.net.Uri?, source: AttachmentPickSource) {
        if (uri == null) { controls.pickOriginal = null; return }
        val resolver = context.applicationContext.contentResolver
        scope.launch { deliver(pickedAttachmentRead(resolver, uri), source) }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        received(it, AttachmentPickSource.PHOTO_PICKER)
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        received(it, AttachmentPickSource.FILE)
    }
    fun launch(source: AttachmentPickSource) {
        val original = controls.sheetOriginal
        controls.sheet = false; controls.sheetOriginal = null
        if (!interactive()) return
        controls.pickOriginal = original
        if (override != null) { override.pick(source, filters) { deliver(it, source) }; return }
        try {
            when (source) {
                AttachmentPickSource.PHOTO_PICKER -> photo.launch(PickVisualMediaRequest(when (val type = filters.photo) {
                    is PhotoPickerFilter.Single -> ActivityResultContracts.PickVisualMedia.SingleMimeType(type.mimeType)
                    else -> ActivityResultContracts.PickVisualMedia.ImageOnly
                }))
                AttachmentPickSource.FILE -> file.launch(filters.documentTypes.toTypedArray())
            }
        } catch (_: android.content.ActivityNotFoundException) {
            controls.pickOriginal = null; controls.notice = AttachNotice.NOT_ADDED
        }
    }
    // The picker result can arrive while the returning pane is still reloading or re-entering
    // presence. Admit exactly once when the pane is settled; a refusal drops the read unopened.
    val pending = controls.picked
    LaunchedEffect(pending, settled, waitingCanEnd) {
        val chosen = pending ?: return@LaunchedEffect
        if (!settled && !waitingCanEnd) return@LaunchedEffect
        controls.picked = null
        val admitted = settled && interactive() && admit(chosen)
        controls.notice = if (admitted) null else AttachNotice.NOT_ADDED
    }
    if (controls.sheet) AttachChoiceSheet(filters, rateLimit,
        onPhoto = { launch(AttachmentPickSource.PHOTO_PICKER) }, onFile = { launch(AttachmentPickSource.FILE) },
        onDismiss = { controls.sheet = false; controls.sheetOriginal = null })
    controls.replacing?.let { original ->
        ReplaceAttachmentDialog(onReplace = {
            controls.replacing = null
            if (interactive() && stillReplaceable(original)) { controls.sheetOriginal = original; controls.sheet = true }
        }, onDismiss = { controls.replacing = null })
    }
}

/** Shown while a returned pick waits for the pane to settle, so the wait is never silent. */
@Composable
internal fun AttachWaitingLine(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("attach_waiting"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.attach_waiting), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = ChitChatTheme.colors.textMuted)
        val cancel = stringResource(R.string.attach_waiting_cancel)
        IconButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = cancel }) { CloseIcon() }
    }
}

/** The pending-pick line and the unavailable/not-added notices, shared by every composer. */
@Composable
internal fun <D : Any> AttachNotices(controls: AttachmentControls<D>, session: SessionState, persisting: Boolean,
    canCheckAgain: Boolean, onCheckAgain: () -> Unit) {
    if (controls.picked != null) AttachWaitingLine(onCancel = { controls.picked = null })
    when (val notice = controls.notice) {
        AttachNotice.NOT_ADDED -> Text(stringResource(R.string.attach_not_added), Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .testTag("attach_not_added").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        AttachNotice.SERVER_OFF, AttachNotice.SETTINGS_UNREADABLE -> AttachUnavailableNotice(
            if (notice == AttachNotice.SERVER_OFF) AttachAvailability.SERVER_OFF else AttachAvailability.SETTINGS_UNREADABLE,
            installationName(session), persisting = persisting, canCheckAgain = canCheckAgain,
            onCheckAgain = { controls.invalidChecked = true; onCheckAgain() }, onDismiss = { controls.notice = null })
        null -> Unit
    }
}
