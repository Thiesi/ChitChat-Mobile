package io.github.thiesi.chitchat.feature.direct

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge
import io.github.thiesi.chitchat.core.ui.DialogScrollBody

// Moved unchanged out of DirectScreens (same tags, state and callbacks); P-220: each text slot scrolls at large font.

@Composable
internal fun DirectDiscardDraftDialog(onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.dm_discard_draft)) },
        text = { DialogScrollBody(Modifier.testTag("dm_discard_body")) { Text(stringResource(R.string.dm_discard_warning)) } },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.dm_discard_draft)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }, modifier = Modifier.lanternDialogEdge())
}

@Composable
internal fun DirectBlockDialog(blocked: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(if (blocked) R.string.dm_unblock else R.string.dm_block)) },
        text = { DialogScrollBody(Modifier.testTag("dm_block_body")) { Text(stringResource(R.string.dm_block_help)) } },
        confirmButton = { TextButton(onClick = onConfirm, Modifier.testTag("dm_confirm_block")) {
            Text(stringResource(if (blocked) R.string.dm_unblock else R.string.dm_block)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }, modifier = Modifier.lanternDialogEdge())
}
