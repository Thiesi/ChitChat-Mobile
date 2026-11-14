package io.github.thiesi.chitchat.feature.rooms

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

/** Leave room, moved unchanged out of RoomsScreen; P-220: the text slot scrolls at large font. */
@Composable
internal fun RoomLeaveDialog(canLeave: Boolean, onLeave: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.rooms_leave_title)) },
        text = { DialogScrollBody(Modifier.testTag("rooms_leave_body")) { Text(stringResource(R.string.rooms_leave_warning)) } },
        confirmButton = { TextButton(onClick = onLeave, enabled = canLeave) { Text(stringResource(R.string.rooms_leave)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }, modifier = Modifier.lanternDialogEdge())
}
