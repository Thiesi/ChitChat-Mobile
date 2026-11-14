package io.github.thiesi.chitchat.feature.push

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.push.PushDistributor
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge
import io.github.thiesi.chitchat.core.ui.DialogScrollBody

// The push dialogs, moved unchanged out of their screens (same composition, tags, state and callbacks) so each can
// also be rendered on its own. P-220: each text slot is a DialogScrollBody, so at large font only the body scrolls,
// with its scroll-edge cue, while the title and buttons stay.

@Composable
internal fun PushExplainDialog(busy: Boolean, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        modifier = Modifier.testTag("push_explain").lanternDialogEdge(),
        title = { Text(stringResource(R.string.push_explain_title)) },
        text = { DialogScrollBody(Modifier.testTag("push_explain_body")) { Text(stringResource(R.string.push_explain_body)) } },
        confirmButton = { TextButton(onClick = onContinue, enabled = !busy, modifier = Modifier.testTag("push_explain_continue")) {
            Text(stringResource(R.string.push_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun PushChooseDialog(distributors: List<PushDistributor>, picked: PushDistributor?, busy: Boolean,
    onPick: (PushDistributor) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, modifier = Modifier.testTag("push_choose").lanternDialogEdge(),
        title = { Text(stringResource(R.string.push_choose_title)) },
        // The choices scroll in the slot like text, each keeping its 48dp row (P-220 item 6).
        text = { DialogScrollBody(Modifier.testTag("push_choose_body")) {
            distributors.forEach { distributor ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(picked === distributor, role = Role.RadioButton,
                    onClick = { onPick(distributor) }).testTag("push_distributor:${distributor.packageName}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = picked === distributor, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(distributor.label)
                }
            }
        } },
        confirmButton = { TextButton(onClick = onConfirm,
            enabled = picked != null && !busy, modifier = Modifier.testTag("push_choose_confirm")) {
            Text(stringResource(R.string.push_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}

/** No delivery app, but Play services: Google delivery, only on an explicit "Use Google". */
@Composable
internal fun PushUseGoogleDialog(busy: Boolean, onGuide: () -> Unit, onUseGoogle: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        modifier = Modifier.testTag("push_google").lanternDialogEdge(),
        title = { Text(stringResource(R.string.push_none_title)) },
        text = { DialogScrollBody(Modifier.testTag("push_google_body")) {
            Text(stringResource(R.string.push_google_body))
            // The label lines up with the body and sits close to it; the 48dp target stays, and the
            // dialog's own spacing comes before the buttons (PR219 J review).
            TextButton(onClick = onGuide, contentPadding = PaddingValues(start = 0.dp, top = 0.dp, end = 12.dp, bottom = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("push_google_guide")) { Text(stringResource(R.string.push_google_link)) }
        } },
        confirmButton = { TextButton(onClick = onUseGoogle, enabled = !busy,
            modifier = Modifier.testTag("push_use_google")) { Text(stringResource(R.string.push_use_google)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}

/** Neither a delivery app nor Play services (or a build without the embedded distributor). */
@Composable
internal fun PushNoDistributorDialog(onGuide: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose,
        modifier = Modifier.testTag("push_none").lanternDialogEdge(),
        title = { Text(stringResource(R.string.push_none_title)) },
        text = { DialogScrollBody(Modifier.testTag("push_none_body")) { Text(stringResource(R.string.push_none_body)) } },
        confirmButton = { TextButton(onClick = onGuide) { Text(stringResource(R.string.push_none_link)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.push_close)) } })
}

/** Turn off from this phone's card. */
@Composable
internal fun PushPhoneOffDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        modifier = Modifier.testTag("push_phone_off_dialog").lanternDialogEdge(),
        title = { Text(stringResource(R.string.push_turn_off_title)) },
        text = { DialogScrollBody(Modifier.testTag("push_phone_off_body")) { Text(stringResource(R.string.push_remove_body)) } },
        confirmButton = { TextButton(onClick = onConfirm,
            modifier = Modifier.testTag("push_phone_off_confirm")) { Text(stringResource(R.string.push_turn_off)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}

/** Remove (or, for this phone, Turn off) a device in Notification settings. */
@Composable
internal fun PushRemoveDialog(currentSession: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, modifier = Modifier.testTag("push_remove_dialog").lanternDialogEdge(),
        title = { Text(stringResource(if (currentSession) R.string.push_turn_off_title else R.string.push_remove_title)) },
        text = { DialogScrollBody(Modifier.testTag("push_remove_body")) { Text(stringResource(R.string.push_remove_body)) } },
        confirmButton = { TextButton(onClick = onConfirm,
            modifier = Modifier.testTag("push_remove_confirm")) {
            Text(stringResource(if (currentSession) R.string.push_turn_off else R.string.push_remove)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}
