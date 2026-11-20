package io.github.thiesi.chitchat.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTag
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.media.AttachmentRef
import io.github.thiesi.chitchat.core.media.DocumentSaveDestination
import io.github.thiesi.chitchat.core.media.FileNames
import io.github.thiesi.chitchat.core.media.FileTransfers
import io.github.thiesi.chitchat.core.media.ShareProvider
import io.github.thiesi.chitchat.core.media.TransferPurpose
import io.github.thiesi.chitchat.core.media.TransferState
import io.github.thiesi.chitchat.core.media.TransferStop
import io.github.thiesi.chitchat.core.status.Wait
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge
import io.github.thiesi.chitchat.feature.status.waitText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Save, Share and Open on screen (received files F5b; files-media.md "Save" and "Share out"; PL and TL rulings of
 * 2026-11-20). The app-scoped [FileTransfers] does the work; this holds what needs an Activity: the create-document
 * picker, the handoff to another app, the first-use note, and telling a genuine background from the app's own picker or
 * chooser.
 */
internal class FileTransfersUi(val transfers: FileTransfers,
    /** The stored once-per-install flag; read when a Share or Open asks, never assumed before it loads (#318 review). */
    private val noteSeen: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    private val markNoteSeen: suspend () -> Unit) : androidx.lifecycle.ViewModel() {
    // An Activity-scoped ViewModel (#318 review): the open first-use note, the saved place and the picker flag survive a
    // configuration change, like the transfer itself.
    /** Share or Open waiting for the first-use note's Continue (PL: shown once per install, before any download). */
    var noteFor by mutableStateOf<Pair<AttachmentRef, TransferPurpose>?>(null)
        internal set
    /** Where the last Save went, for "Saved to {app}": the picker's provider label, or null. */
    internal var savedPlace by mutableStateOf<String?>(null)
    internal var launchSave: (FileTransfers.PendingSave) -> Unit = {}
    /** The app's own picker or chooser is in front: its ON_STOP isn't a background (TL condition, 2026-11-20). */
    internal var external = false

    /** The first-use dialog is a modal over the conversation (TL): no read proof and no typing while it shows. */
    val dialogOpen: Boolean get() = noteFor != null

    fun save(ref: AttachmentRef) {
        val request = transfers.requestSave(ref) ?: return
        savedPlace = null
        external = true
        launchSave(request)
    }

    /** Share or Open: the first-use note first, once per install; Cancel there starts nothing (TL). */
    fun hand(ref: AttachmentRef, purpose: TransferPurpose) {
        if (transfers.busy || transfers.waiting() || FileNames.isApkLike(ref.attachment)) return
        // The stored value, read now: a tap right after a cold start never sees a not-yet-loaded "unseen".
        viewModelScope.launch {
            if (!noteSeen.first()) noteFor = ref to purpose else transfers.prepare(ref, purpose)
        }
    }

    /** Continue: the copy starts first, then the flag is written, in this ViewModel's scope, so leaving can't drop it. */
    internal fun continueNote() {
        val (ref, purpose) = noteFor ?: return
        noteFor = null
        transfers.prepare(ref, purpose)
        // A failed write only means the note shows again next time; it never blocks the transfer (TL).
        viewModelScope.launch { try { markNoteSeen() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { } }
    }

    fun cancelNote() { noteFor = null }
}

internal val LocalFileTransfers = staticCompositionLocalOf<FileTransfersUi?> { null }

/**
 * Whether Open, Save and Share may be pressed now, from observable state only (#318 review): no transfer running or
 * waiting for its picker or handoff, and no session 429 wait (with a timer to its end, bound to this composition, as the
 * status slot does). Disabled buttons always have the running transfer's notice or the status slot's wait on screen.
 */
@Composable
internal fun rememberTransfersUsable(ui: FileTransfersUi): Boolean {
    val state by ui.transfers.state.collectAsState()
    val pending by ui.transfers.pendingSaveState.collectAsState()
    val session by ui.transfers.sessionState.collectAsState()
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.retryAtMillis) {
        now = System.currentTimeMillis()
        val wait = session.retryAtMillis - now
        if (wait > 0) { kotlinx.coroutines.delay(wait); now = System.currentTimeMillis() }
    }
    val idle = state == null || state is TransferState.Saved || state is TransferState.Stopped
    return idle && pending == null && now >= session.retryAtMillis
}

/** The create-document picker with the server's MIME type and the sanitized suggested title (files-media.md "Save"). */
private class CreateDocument : ActivityResultContract<FileTransfers.PendingSave, Uri?>() {
    override fun createIntent(context: Context, input: FileTransfers.PendingSave) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.suggestedTitle)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

/**
 * At the Activity: provides [LocalFileTransfers], launches the picker and the handoff, and stops a transfer on a genuine
 * background (ON_STOP that isn't a configuration change and isn't the app's own picker or chooser).
 */
@Composable
internal fun FileTransfersHost(ui: FileTransfersUi, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // The result is matched to the transfer's own pending request, which survives a recreated Activity (#318 review).
    val picker = rememberLauncherForActivityResult(CreateDocument()) { uri ->
        ui.external = false
        val request = ui.transfers.pendingSave ?: return@rememberLauncherForActivityResult
        ui.savedPlace = uri?.let { providerLabel(context, it) }
        ui.transfers.saveChosen(request, uri?.let { DocumentSaveDestination(context.contentResolver, it) })
    }
    ui.launchSave = { request ->
        try { picker.launch(request) } catch (_: ActivityNotFoundException) {
            ui.external = false; ui.transfers.saveChosen(request, null)
        }
    }
    val state by ui.transfers.state.collectAsState()
    // The handoff only after the copy is verified: the chooser's ON_STOP then comes after the transfer is finished.
    LaunchedEffect(state) {
        val ready = state as? TransferState.Ready ?: return@LaunchedEffect
        val intent = if (ready.purpose == TransferPurpose.OPEN) ShareProvider.openIntent(context, ready)
            else ShareProvider.shareIntent(context, ready)
        // Start first, then hand off in the same main-thread task (#318 review): with no app for the type, noApp still
        // deletes the copy and says so; the chooser's ON_STOP comes after this task, when nothing is running any more.
        try {
            context.startActivity(intent)
            ui.transfers.handedOff(ready)
        } catch (_: ActivityNotFoundException) { ui.transfers.noApp(ready) }
        // An app or provider can refuse the grant: the copy goes the same way, never left behind (TL).
        catch (_: SecurityException) { ui.transfers.noApp(ready) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true && !ui.external) ui.transfers.backgrounded()
    }
    CompositionLocalProvider(LocalFileTransfers provides ui, content = content)
}

/** The provider's app name for "Saved to {app or folder}", or null (PL: fall back to "Saved {name}"). */
private fun providerLabel(context: Context, uri: Uri): String? = try {
    val authority = uri.authority ?: return null
    if (!DocumentsContract.isDocumentUri(context, uri)) null
    else context.packageManager.resolveContentProvider(authority, 0)?.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
} catch (_: Exception) { null }

/**
 * The transfer's notice row (PL): progress with Cancel, Saved, and every stop line, in the conversation's notice stack
 * and the viewer's bottom block ([overlay]). Conversations also host the first-use dialog ([withNote]).
 */
@Composable
internal fun TransferNotice(modifier: Modifier = Modifier, overlay: Boolean = false, withNote: Boolean = true) {
    val ui = LocalFileTransfers.current ?: return
    val state by ui.transfers.state.collectAsState()
    val scope = rememberCoroutineScope()
    // A finished line clears when the person leaves the conversation (PL); the viewer's copy doesn't clear it.
    val activity = LocalActivity.current
    if (withNote) androidx.compose.runtime.DisposableEffect(ui) {
        // Leaving clears a finished line and an open first-use note, so neither shows up in another conversation.
        onDispose { if (activity?.isChangingConfigurations != true) { ui.transfers.dismiss(); ui.cancelNote() } }
    }
    if (withNote) ui.noteFor?.let {
        AlertDialog(onDismissRequest = ui::cancelNote, modifier = Modifier.testTag("transfer_note").lanternDialogEdge(),
            text = { DialogScrollBody { Text(stringResource(R.string.transfer_first_use)) } },
            confirmButton = { TextButton(onClick = ui::continueNote, modifier = Modifier.testTag("transfer_note_continue")) {
                Text(stringResource(R.string.transfer_continue)) } },
            dismissButton = { TextButton(onClick = ui::cancelNote, modifier = Modifier.testTag("transfer_note_cancel")) {
                Text(stringResource(R.string.cancel)) } })
    }
    val current = state ?: return
    if (current is TransferState.Ready) return
    val text = if (overlay) io.github.thiesi.chitchat.core.theme.Lantern.overlayText else ChitChatTheme.colors.text
    val muted = if (overlay) text else ChitChatTheme.colors.textMuted
    val name = FileNames.withoutBidiControls(current.ref.attachment.name)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("transfer_notice"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (current) {
            is TransferState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val percent = if (current.total > 0) (current.written * 100 / current.total).toInt().coerceIn(0, 100) else null
                val saving = current.purpose == TransferPurpose.SAVE
                // PL J1: two lines with fixed roles, the status and percent, then the name on one middle-elided line.
                val status = when {
                    percent == null && saving -> stringResource(R.string.transfer_saving_status_indeterminate)
                    percent == null -> stringResource(R.string.transfer_preparing_status_indeterminate)
                    saving -> stringResource(R.string.transfer_saving_status, percent)
                    else -> stringResource(R.string.transfer_preparing_status, percent)
                }
                // PL J2: one stable spoken line (no percent) announced politely, the progress as range info, and Cancel
                // as its own node.
                val spoken = stringResource(if (saving) R.string.transfer_saving_indeterminate else R.string.transfer_preparing_indeterminate, name)
                // The tag goes inside: clearAndSetSemantics drops one chained after it.
                Row(Modifier.weight(1f).clearAndSetSemantics {
                    testTag = "transfer_progress"
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = if (percent != null) ProgressBarRangeInfo(percent / 100f, 0f..1f) else ProgressBarRangeInfo.Indeterminate
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (percent != null) CircularProgressIndicator(progress = { percent / 100f }, Modifier.size(20.dp), color = text, strokeWidth = 2.dp)
                    else CircularProgressIndicator(Modifier.size(20.dp), color = text, strokeWidth = 2.dp)
                    Column {
                        Text(status, color = text, maxLines = 1, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("transfer_line"))
                        OneLineName(name, muted)
                    }
                }
                TextButton(onClick = ui.transfers::cancel, modifier = Modifier.heightIn(min = 48.dp).testTag("transfer_cancel")) {
                    if (overlay) Text(stringResource(R.string.cancel), color = text) else Text(stringResource(R.string.cancel))
                }
            }
            is TransferState.Saved -> NoticeLine(ui.savedPlace?.let { stringResource(R.string.transfer_saved_to, it) }
                ?: stringResource(R.string.transfer_saved, name), muted)
            is TransferState.Stopped -> {
                val line = stopLine(current) ?: return@Column
                val retry: @Composable () -> Unit = {
                    if (current.purpose != TransferPurpose.SAVE && current.reason == TransferStop.FAILED)
                        TextButton(onClick = { ui.transfers.dismiss(); ui.hand(current.ref, current.purpose) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("transfer_retry")) {
                            // PL J4: the ordinary primary text button in the conversation; overlay text only in the viewer.
                            if (overlay) Text(stringResource(R.string.retry), color = text) else Text(stringResource(R.string.retry))
                        }
                }
                if (stopLevel(current) == 1) NoticeLine(line, muted)
                else {
                    // Level 2: the shared warning notice, its first sentence as the bold lead (PL).
                    val cut = line.indexOf(". ").let { if (it < 0) line.length else it + 1 }
                    val lead = line.substring(0, cut)
                    val body = line.substring(cut).trim()
                    if (overlay) Column(Modifier.testTag("transfer_level_2")) {
                        WarningLine(lead, body, style = MaterialTheme.typography.bodyMedium, color = text); retry()
                    } else {
                        // PL J3: the text at most 30% of the window, scrolling inside with the 1dp outline edge, so the
                        // composer never shrinks; the actions stay visible below it.
                        val window = with(androidx.compose.ui.platform.LocalDensity.current) {
                            androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
                        }
                        Column(Modifier.fillMaxWidth().background(ChitChatTheme.colors.presenceWash).padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("transfer_level_2")) {
                            DialogScrollBody(Modifier.heightIn(max = window * 0.3f).testTag("transfer_notice_text")) {
                                WarningLine(lead, body, Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { retry() }
                        }
                    }
                }
            }
            is TransferState.Ready -> Unit
        }
    }
}

/** The name on one line, the middle elided so the start and the extension stay (PL J1). */
@Composable
private fun OneLineName(name: String, color: Color) {
    val style = MaterialTheme.typography.bodySmall.copy(color = color)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints {
        val width = constraints.maxWidth
        val shown = remember(name, width, style) {
            middleElided(name) { !measurer.measure(it, style, maxLines = 1, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)).hasVisualOverflow }
        }
        Text(shown, style = style, maxLines = 1, modifier = Modifier.testTag("transfer_name"))
    }
}

@Composable
private fun NoticeLine(line: String, color: Color) = Column(Modifier.testTag("transfer_level_1")) {
    Text(line, color = color, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("transfer_line"))
}

/**
 * Level 1 for what the person did or a plain fact (a Save they cancelled, no app for the type); level 2 for failures,
 * including the background stop and no space (PL, 2026-11-20).
 */
internal fun stopLevel(stopped: TransferState.Stopped): Int = when {
    stopped.purpose == TransferPurpose.SAVE && stopped.reason == TransferStop.CANCELLED -> 1
    stopped.purpose != TransferPurpose.SAVE && stopped.reason == TransferStop.NO_APP -> 1
    else -> 2
}

/** The PL's copy for each stop (files-media.md; 2026-11-20). A cancelled or backgrounded Share/Open is silent. */
@Composable
private fun stopLine(stopped: TransferState.Stopped): String? {
    val saving = stopped.purpose == TransferPurpose.SAVE
    val reason = when (stopped.reason) {
        TransferStop.GONE -> stringResource(R.string.transfer_reason_gone)
        TransferStop.NOT_ALLOWED -> stringResource(R.string.transfer_reason_not_allowed)
        TransferStop.RATE_LIMITED -> stopped.retryAfterSeconds?.let { stringResource(R.string.status_rate_limited, waitText(Wait.of(it * 1000))) }
            ?: stringResource(R.string.status_rate_limited_unknown)
        TransferStop.BACKGROUND -> stringResource(R.string.transfer_reason_background)
        else -> stringResource(R.string.transfer_reason_failed)
    }
    if (!saving) return when (stopped.reason) {
        TransferStop.GONE -> stringResource(R.string.image_gone)
        TransferStop.NOT_ALLOWED -> stringResource(R.string.image_not_allowed)
        TransferStop.NO_SPACE -> stringResource(R.string.transfer_no_space)
        TransferStop.NO_APP -> stringResource(R.string.transfer_no_app)
        // PL J5: the lead says what didn't happen, then row 7's wait; no Try again during the wait.
        TransferStop.RATE_LIMITED -> stringResource(R.string.transfer_share_not_ready) + " " + reason
        TransferStop.CANCELLED, TransferStop.BACKGROUND -> null
        else -> stringResource(R.string.transfer_share_failed)
    }
    return when {
        stopped.reason == TransferStop.DAMAGED -> stringResource(R.string.transfer_save_damaged)
        stopped.wroteBody && stopped.reason == TransferStop.CANCELLED -> stringResource(R.string.transfer_save_incomplete)
        stopped.wroteBody && stopped.reason == TransferStop.BACKGROUND -> stringResource(R.string.transfer_save_background_incomplete)
        stopped.wroteBody || stopped.reason == TransferStop.STORAGE -> stringResource(R.string.transfer_save_incomplete)
        stopped.reason == TransferStop.CANCELLED -> stringResource(R.string.transfer_nothing_saved_plain)
        else -> stringResource(R.string.transfer_nothing_saved, reason)
    }
}
