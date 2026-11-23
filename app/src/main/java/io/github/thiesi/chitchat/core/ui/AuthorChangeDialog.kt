package io.github.thiesi.chitchat.core.ui

import io.github.thiesi.chitchat.core.theme.lanternDialogEdge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.mutations.AuthorChangeKind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints

/** Presentation only. Owner-specific canonical requests, authorization and jobs stay in the VM.
 * Private text is not saved or logged; intentionally no generated content string representation.
 */
internal class AuthorDialogPresentation(val kind: AuthorChangeKind, val body: String, val originalBody: String,
    val validationText: String?, val checked: Boolean, val pendingMessageId: Long?, val recovery: Boolean = false)

@Composable
internal fun MessageAuthorChangeDialog(dialog: AuthorDialogPresentation, targetCurrent: Boolean, available: Boolean,
    busy: Boolean, onChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit,
    canConfirm: Boolean = available, currentPreview: String? = null, error: String? = null) {
    val editing = dialog.kind == AuthorChangeKind.EDIT
    if (editing) {
        // A floating alert can pan its footer beneath the IME when large text makes the form tall.
        // This complex editor owns a full native dialog window and consumes insets exactly once.
        // Keep one editor node through IME resize; short, wide windows place actions beside it.
        Dialog(onDismissRequest = { if (!busy) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize().testTag("author_change_dialog"), color = MaterialTheme.colorScheme.surface) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    val compact = maxHeight < 200.dp && maxWidth >= 400.dp
                    AuthorEditorLayout(compact) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.message_edit_action), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                        AuthorChangeText(dialog, targetCurrent, available, busy, onChange, currentPreview, error, compact)
                    }
                    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                        AuthorConfirm(dialog, canConfirm && !busy, onConfirm)
                    }
                    }
                }
            }
        }
    } else {
        AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, modifier = Modifier.testTag("author_change_dialog").lanternDialogEdge(),
            title = { Text(stringResource(R.string.author_delete_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthorChangeText(dialog, targetCurrent, available, busy, onChange, currentPreview, error)
            } },
            confirmButton = { AuthorConfirm(dialog, canConfirm && !busy, onConfirm) },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    }
}

/** Measure each slot once without moving/recreating the focused field when the IME changes height. */
@Composable
private fun AuthorEditorLayout(compact: Boolean, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val actions = children[1].measure(Constraints(maxWidth = if (compact) width / 2 else width, maxHeight = height))
        val bodyWidth = if (compact) width - actions.width else width
        val bodyHeight = if (compact) height else (height - actions.height).coerceAtLeast(0)
        val body = children[0].measure(Constraints.fixed(bodyWidth, bodyHeight))
        layout(width, height) {
            body.placeRelative(0, 0)
            actions.placeRelative(width - actions.width, if (compact) 0 else height - actions.height)
        }
    }
}

@Composable
private fun AuthorChangeText(dialog: AuthorDialogPresentation, targetCurrent: Boolean, available: Boolean,
    busy: Boolean, onChange: (String) -> Unit, currentPreview: String?, error: String?, compact: Boolean = false) {
    val editing = dialog.kind == AuthorChangeKind.EDIT
    val pending = dialog.pendingMessageId
    Text(stringResource(R.string.author_retention))
    if (pending != null) Text(stringResource(R.string.author_unconfirmed, pending.toString()))
    if (editing && (pending != null || dialog.checked)) Text(stringResource(R.string.author_proposal_retained))
    if (dialog.checked) Text(if (currentPreview == null) stringResource(R.string.author_current_unavailable)
        else stringResource(R.string.author_current_message, messageExcerpt(currentPreview)))
    if (editing && (targetCurrent || pending != null || dialog.checked)) {
        // #70/#284 (PL, TL 2026-11-23): a state change never gives focus or brings up the keyboard by itself. A read-only
        // body (unknown, checked) holds no input focus: focus clears on the same transition that makes it read-only, so
        // the keyboard closes once, as before. Review then makes it editable without focus; only a tap on it focuses it.
        // Otherwise Review restarted the focused field's input session, the keyboard came up unasked and Save moved
        // under the next tap. The initial open is unchanged. Input focus only: accessibility focus isn't touched.
        val focus = LocalFocusManager.current
        val readOnly = !available
        // canFocus = false keeps a read-only body from taking focus; the clear below drops focus it already holds, on the
        // read-only transition itself. It's a plain clear (no moveFocus), so no other node takes input focus. It stays
        // because a canFocus change isn't guaranteed to release focus that's already held.
        LaunchedEffect(readOnly) { if (readOnly) focus.clearFocus() }
        MessageInput(dialog.body, onChange, !busy,
            stringResource(if (pending != null || dialog.checked) R.string.author_proposed_text else R.string.author_edit_body), compact,
            Modifier.fillMaxWidth().focusProperties { canFocus = !readOnly }.testTag("author_edit_body"), readOnly = readOnly)
        Text(stringResource(R.string.author_character_count, dialog.body.codePointCount(0, dialog.body.length)))
    } else if (!targetCurrent) Text(stringResource(R.string.message_actions_unavailable))
    else if (pending == null) Text(messageExcerpt(dialog.originalBody))
    error?.let { Text(it) }
    dialog.validationText?.let { Text(it) }
    if (busy) Text(stringResource(R.string.author_working))
    else if (targetCurrent && !available && pending == null && !dialog.checked) Text(stringResource(R.string.author_temporarily_unavailable))
}

@Composable
private fun AuthorConfirm(dialog: AuthorDialogPresentation, enabled: Boolean, onConfirm: () -> Unit) {
    val pending = dialog.pendingMessageId
    TextButton(onClick = onConfirm, enabled = enabled,
        modifier = Modifier.testTag(if (pending != null) "author_check_history" else "author_change_confirm")) {
        val label = stringResource(when {
            pending != null -> R.string.author_check_history
            dialog.checked -> R.string.author_review_edit
            dialog.kind == AuthorChangeKind.EDIT -> R.string.author_save
            else -> R.string.author_delete_confirm
        })
        if (dialog.kind == AuthorChangeKind.EDIT && dialog.recovery) {
            // #70/#284 (PL ruling B, 2026-11-23): on the recovery path (from the transition into unknown until the dialog
            // closes) the confirm keeps the width of the widest recovery label, so the action row keeps one arrangement
            // across Check, Review and Save. At 150% and 200% "Review edit" stacked under Cancel while "Save" fit beside
            // it, so the row reflowed on Review and a tap aimed at Review landed between the buttons. A first edit keeps
            // today's arrangement. The sizing labels are invisible and silent; the visible one is end-aligned and read.
            Box(contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                for (sizing in listOf(R.string.author_check_history, R.string.author_review_edit, R.string.author_save))
                    Text(stringResource(sizing), Modifier.alpha(0f).clearAndSetSemantics { })
                Text(label)
            }
        } else Text(label)
    }
}
