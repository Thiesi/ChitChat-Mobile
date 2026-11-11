package io.github.thiesi.chitchat.feature.conversation

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.media.*
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.*
import java.net.URI
import kotlinx.coroutines.launch

/** Screen-local picker/dialog state. Never saved: a URI, grant or original reference must not
 * outlive this composition. A configuration change during picking loses the original Replace
 * binding, so the VM then refuses that result instead of replacing a file without consent.
 */
@Stable
internal class RoomAttachmentControls {
    var sheet by mutableStateOf(false)
    var replacing by mutableStateOf<RoomAttachmentDraft?>(null)
    var notice by mutableStateOf<AttachNotice?>(null)
    var invalidChecked by mutableStateOf(false)
    var picked by mutableStateOf<PickedFile?>(null)
    /** Original selection a confirmed Replace is bound to, until its one picker returns. */
    var sheetOriginal: RoomAttachmentDraft? = null
    var pickOriginal: RoomAttachmentDraft? = null
    val modalOpen: Boolean get() = sheet || replacing != null
}

internal enum class AttachNotice { SERVER_OFF, SETTINGS_UNREADABLE, NOT_ADDED }
internal class PickedFile(val read: PickedAttachmentRead, val source: AttachmentPickSource, val original: RoomAttachmentDraft?) {
    override fun toString() = "PickedFile"
}

internal fun attachAvailability(policy: AttachmentPolicy): AttachAvailability = when {
    policy == AttachmentPolicy.Invalid -> AttachAvailability.SETTINGS_UNREADABLE
    policy is AttachmentPolicy.Known && policy.maxBytes <= 0 -> AttachAvailability.SERVER_OFF
    else -> AttachAvailability.AVAILABLE
}

internal fun RoomAttachmentDraft.cardFacts(observation: AttachmentPolicyObservation?) = AttachmentCardFacts(
    filename, mimeType, sizeBytes, when (phase) {
        RoomAttachmentPhase.PREPARING -> AttachmentCardPhase.PREPARING
        RoomAttachmentPhase.READY -> AttachmentCardPhase.READY
        RoomAttachmentPhase.PRIVACY_CHOICE -> AttachmentCardPhase.PRIVACY_CHOICE
        RoomAttachmentPhase.FAILED -> AttachmentCardPhase.FAILED
        else -> AttachmentCardPhase.IN_FLIGHT
    }, privacy, privacyFailure, failure, observation, sendingAvailable = false)

private val replaceablePhases = setOf(RoomAttachmentPhase.PREPARING, RoomAttachmentPhase.READY,
    RoomAttachmentPhase.PRIVACY_CHOICE, RoomAttachmentPhase.FAILED)

internal fun RoomAttachmentDraft.removable() = phase in replaceablePhases && upload == null

/** The installation name used in copy: the chosen server's host, never a guessed brand. */
internal fun installationName(session: SessionState): String =
    session.server?.value?.let { runCatching { URI(it).host }.getOrNull() } ?: ""

/** Pickers, the choice sheet, Replace confirmation and one-shot admission for one room pane.
 * Only explicit person actions open a picker; only the VM's original-bound selectAttachment
 * admits a file. Nothing here reads bytes, sends or retries a request.
 */
@Composable
internal fun RoomAttachmentHost(controls: RoomAttachmentControls, model: ConversationViewModel, session: SessionState,
    roomId: Long, entryId: String, isCurrentEntry: () -> Boolean, interactive: () -> Boolean,
    settled: Boolean, waitingCanEnd: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val override = LocalAttachmentPickerOverride.current
    val filters = attachmentPickFilters(session.attachmentPolicy)
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
        val admitted = settled && interactive() && model.selectAttachment(session, roomId, entryId, chosen.read,
            chosen.source, chosen.original, isCurrentEntry)
        controls.notice = if (admitted) null else AttachNotice.NOT_ADDED
    }
    if (controls.sheet) AttachChoiceSheet(filters, (session.attachmentPolicy as? AttachmentPolicy.Known)?.rateLimits?.room,
        onPhoto = { launch(AttachmentPickSource.PHOTO_PICKER) }, onFile = { launch(AttachmentPickSource.FILE) },
        onDismiss = { controls.sheet = false; controls.sheetOriginal = null })
    controls.replacing?.let { original ->
        ReplaceAttachmentDialog(onReplace = {
            controls.replacing = null
            if (interactive() && model.visibleAttachment(session, roomId, isCurrentEntry)?.let { it === original && it.removable() } == true) {
                controls.sheetOriginal = original; controls.sheet = true
            }
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
