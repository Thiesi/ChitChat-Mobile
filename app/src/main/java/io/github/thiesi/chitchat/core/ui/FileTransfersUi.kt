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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import kotlinx.coroutines.launch

/**
 * Save, Share and Open on screen (received files F5b; files-media.md "Save" and "Share out"; PL and TL rulings of
 * 2026-11-20). The app-scoped [FileTransfers] does the work; this holds what needs an Activity: the create-document
 * picker, the handoff to another app, the first-use note, and telling a genuine background from the app's own picker or
 * chooser.
 */
internal class FileTransfersUi(val transfers: FileTransfers, private val markNoteSeen: suspend () -> Unit) : androidx.lifecycle.ViewModel() {
    // An Activity-scoped ViewModel (#318 review): the open first-use note, the saved place and the picker flag survive a
    // configuration change, like the transfer itself.
    /** Share or Open waiting for the first-use note's Continue (PL: shown once per install, before any download). */
    var noteFor by mutableStateOf<Pair<AttachmentRef, TransferPurpose>?>(null)
        internal set
    internal var noteSeen = false
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
        if (!noteSeen) { noteFor = ref to purpose; return }
        transfers.prepare(ref, purpose)
    }

    internal suspend fun continueNote() {
        val (ref, purpose) = noteFor ?: return
        noteFor = null
        noteSeen = true
        markNoteSeen()
        transfers.prepare(ref, purpose)
    }

    fun cancelNote() { noteFor = null }
}

internal val LocalFileTransfers = staticCompositionLocalOf<FileTransfersUi?> { null }

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
internal fun FileTransfersHost(ui: FileTransfersUi, noteSeen: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    ui.noteSeen = noteSeen
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
        try {
            ui.transfers.handedOff(ready)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) { ui.transfers.noApp(ready) }
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
        onDispose { if (activity?.isChangingConfigurations != true) ui.transfers.dismiss() }
    }
    if (withNote) ui.noteFor?.let {
        AlertDialog(onDismissRequest = ui::cancelNote, modifier = Modifier.testTag("transfer_note").lanternDialogEdge(),
            text = { DialogScrollBody { Text(stringResource(R.string.transfer_first_use)) } },
            confirmButton = { TextButton(onClick = { scope.launch { ui.continueNote() } }, modifier = Modifier.testTag("transfer_note_continue")) {
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
                if (percent != null) CircularProgressIndicator(progress = { percent / 100f }, Modifier.size(20.dp), color = text, strokeWidth = 2.dp)
                else CircularProgressIndicator(Modifier.size(20.dp), color = text, strokeWidth = 2.dp)
                val saving = current.purpose == TransferPurpose.SAVE
                val line = when {
                    percent == null && saving -> stringResource(R.string.transfer_saving_indeterminate, name)
                    percent == null -> stringResource(R.string.transfer_preparing_indeterminate, name)
                    saving -> stringResource(R.string.transfer_saving, name, percent)
                    else -> stringResource(R.string.transfer_preparing, name, percent)
                }
                // Announced once at the start; the percent is visual and read on focus (PL).
                Text(line, color = text, maxLines = 2, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag("transfer_line"))
                TextButton(onClick = ui.transfers::cancel, modifier = Modifier.heightIn(min = 48.dp).testTag("transfer_cancel")) {
                    Text(stringResource(R.string.cancel), color = text)
                }
            }
            is TransferState.Saved -> NoticeLine(ui.savedPlace?.let { stringResource(R.string.transfer_saved_to, it) }
                ?: stringResource(R.string.transfer_saved, name), muted)
            is TransferState.Stopped -> {
                val line = stopLine(current) ?: return@Column
                val retry: @Composable () -> Unit = {
                    if (current.purpose != TransferPurpose.SAVE && current.reason == TransferStop.FAILED)
                        TextButton(onClick = { ui.transfers.dismiss(); ui.hand(current.ref, current.purpose) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("transfer_retry")) { Text(stringResource(R.string.retry), color = text) }
                }
                if (stopLevel(current) == 1) NoticeLine(line, muted)
                else {
                    // Level 2: the shared warning notice, its first sentence as the bold lead (PL).
                    val cut = line.indexOf(". ").let { if (it < 0) line.length else it + 1 }
                    val lead = line.substring(0, cut)
                    val body = line.substring(cut).trim()
                    if (overlay) Column(Modifier.testTag("transfer_level_2")) {
                        WarningLine(lead, body, style = MaterialTheme.typography.bodyMedium, color = text); retry()
                    } else WarningNotice(lead, body, Modifier.testTag("transfer_level_2"), actions = retry)
                }
            }
            is TransferState.Ready -> Unit
        }
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
        TransferStop.RATE_LIMITED -> reason
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
