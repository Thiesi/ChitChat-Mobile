package io.github.thiesi.chitchat.feature.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.rooms.RoomTransferMember
import io.github.thiesi.chitchat.core.theme.Lantern
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge
import io.github.thiesi.chitchat.core.ui.InlineError
import io.github.thiesi.chitchat.feature.auth.StepUpDialog

/** Bold the first occurrence of [bold] inside [text] (P-217's "**{name}** …" lines). */
private fun boldName(text: String, bold: String): AnnotatedString = buildAnnotatedString {
    append(text)
    val at = text.indexOf(bold)
    if (at >= 0 && bold.isNotEmpty()) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), at, at + bold.length)
}

/**
 * The Ownership part of the room detail dialog (P-217 v1 entry), shown in place of Leave for an owner and below the
 * room facts for a global room manager. Loading is quiet: the explanation alone, no spinner or placeholder row.
 */
@Composable
internal fun RoomTransferSection(transfer: RoomTransferUi?, ownerViewer: Boolean, enabled: Boolean,
    onOpen: () -> Unit, onRetry: () -> Unit, onCheckRoom: () -> Unit = {}) {
    if (ownerViewer) Text(stringResource(R.string.rooms_owner_explanation), Modifier.testTag("rooms_owner_explanation"))
    if (transfer == null) return
    if (!transfer.ownerActing) transfer.owner?.let { owner ->
        Text(boldName(stringResource(R.string.rooms_transfer_owner, owner.username), owner.username))
    }
    if (transfer.outcome == TransferOutcome.SUCCESS && transfer.ownerActing) transfer.outcomeName?.let { name ->
        Text(boldName(stringResource(R.string.rooms_transfer_success, name, transfer.roomName), name),
            Modifier.testTag("rooms_transfer_success"))
    }
    // A transfer that may have committed (left mid-POST): Check room first, never a new choice.
    if (transfer.outcome == TransferOutcome.UNKNOWN && !transfer.pickerOpen) {
        TransferOutcomeLine(transfer, onCheckRoom) { "" }
        return
    }
    when (transfer.load) {
        TransferLoad.LOADING, TransferLoad.HIDDEN -> Unit
        TransferLoad.FAILED -> {
            Text(stringResource(R.string.rooms_transfer_check_failed))
            TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("rooms_transfer_retry")) {
                Text(stringResource(R.string.retry))
            }
        }
        TransferLoad.LOADED -> if (transfer.candidates.isEmpty()) {
            if (transfer.ownerActing) Text(stringResource(R.string.rooms_transfer_none), Modifier.testTag("rooms_transfer_none"))
        } else TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("rooms_transfer_open")) {
            Text(stringResource(R.string.rooms_transfer_row))
        }
    }
}

/** Full-screen picker of the flagged members, shown in place of the dialog (never stacked over it). */
@Composable
internal fun RoomTransferPicker(transfer: RoomTransferUi, enabled: Boolean, onFilter: (String) -> Unit,
    onChoose: (RoomTransferMember) -> Unit, onClose: () -> Unit, onCheckRoom: () -> Unit, errorText: @Composable (ApiFailure) -> String) {
    val locked = transfer.posting || transfer.outcome == TransferOutcome.UNKNOWN
    BackHandler(enabled = !locked) { onClose() }
    Dialog(onDismissRequest = { if (!locked) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("rooms_transfer_picker")) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rooms_transfer_title), style = MaterialTheme.typography.headlineSmall)
                TransferOutcomeLine(transfer, onCheckRoom, errorText)
                if (transfer.candidates.size > 10) OutlinedTextField(transfer.filter, onFilter,
                    label = { Text(stringResource(R.string.rooms_transfer_filter)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rooms_transfer_filter"), shape = MaterialTheme.shapes.medium)
                val shown = transfer.candidates.filter { transfer.filter.isBlank() || it.username.contains(transfer.filter.trim(), ignoreCase = true) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.id }) { member ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(enabled = enabled && !locked) { onChoose(member) }
                            .padding(horizontal = 8.dp).testTag("rooms_transfer_member:${member.id}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).background(Lantern.identity(member.id), Lantern.person).clearAndSetSemantics {},
                                contentAlignment = Alignment.Center) {
                                Text(member.username.take(member.username.offsetByCodePoints(0, 1)), color = Lantern.ink,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(member.username, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(roleLabel(member.role)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = onClose, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
internal fun TransferOutcomeLine(transfer: RoomTransferUi, onCheckRoom: () -> Unit, errorText: @Composable (ApiFailure) -> String) {
    val name = transfer.outcomeName.orEmpty()
    transfer.error?.let { InlineError(errorText(it)) }
    val line = when (transfer.outcome) {
        TransferOutcome.ALREADY_OWNER -> AnnotatedString(stringResource(R.string.rooms_transfer_already_owner, name))
        TransferOutcome.NOT_ELIGIBLE -> boldName(stringResource(R.string.rooms_transfer_not_eligible, name),
            stringResource(R.string.rooms_transfer_not_eligible_lead, name))
        TransferOutcome.NOT_MEMBER -> AnnotatedString(stringResource(R.string.rooms_transfer_not_member, name))
        TransferOutcome.CHANGED -> boldName(stringResource(R.string.rooms_transfer_changed), stringResource(R.string.rooms_transfer_changed_lead))
        TransferOutcome.FORBIDDEN -> AnnotatedString(stringResource(R.string.rooms_transfer_forbidden))
        TransferOutcome.UNKNOWN -> boldName(stringResource(R.string.rooms_transfer_unknown), stringResource(R.string.rooms_transfer_unknown))
        TransferOutcome.SUCCESS, null -> null
    }
    if (line != null) Text(line, Modifier.testTag("rooms_transfer_outcome"))
    if (transfer.outcome == TransferOutcome.CHANGED || transfer.outcome == TransferOutcome.UNKNOWN)
        TextButton(onClick = onCheckRoom, enabled = !transfer.checking, modifier = Modifier.heightIn(min = 48.dp).testTag("rooms_transfer_check")) {
            Text(stringResource(R.string.rooms_transfer_check))
        }
}

/** P-217's level-3 decision: Make owner in signature (nothing is deleted), never error-coloured. */
@Composable
internal fun RoomTransferConfirm(transfer: RoomTransferUi, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val target = transfer.chosen ?: return
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.rooms_transfer_confirm_title, target.username, transfer.roomName)) },
        text = { Text(if (transfer.ownerActing) stringResource(R.string.rooms_transfer_confirm_owner, target.username)
            else stringResource(R.string.rooms_transfer_confirm_admin, target.username,
                transfer.owner?.username ?: stringResource(R.string.rooms_transfer_current_owner))) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("rooms_transfer_make_owner")) {
            Text(stringResource(R.string.rooms_transfer_make_owner), color = io.github.thiesi.chitchat.core.theme.ChitChatTheme.colors.signatureText)
        } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        modifier = Modifier.lanternDialogEdge().testTag("rooms_transfer_confirm"))
}

@Composable
internal fun RoomTransferStepUp(transfer: RoomTransferUi, onVerify: (String, Boolean) -> Unit, onCancel: () -> Unit,
    errorText: @Composable (ApiFailure) -> String) {
    val methods = transfer.methods ?: return
    StepUpDialog(stringResource(R.string.rooms_transfer_step_up_title), methods, transfer.verificationBusy,
        transfer.error?.let { errorText(it) }, onVerify, onCancel)
}

private fun roleLabel(role: String) = when (role) {
    "moderator" -> R.string.rooms_role_moderator
    "owner" -> R.string.rooms_role_owner
    else -> R.string.rooms_role_member
}
