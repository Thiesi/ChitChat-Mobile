package io.github.thiesi.chitchat.app

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

/** Forget this server, moved unchanged out of the server card; P-220: the text slot scrolls at large font. */
@Composable
internal fun ForgetServerDialog(onForget: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.forget_title)) },
        text = { DialogScrollBody(Modifier.testTag("forget_body")) { Text(stringResource(R.string.forget_body)) } },
        confirmButton = { TextButton(onClick = onForget) { Text(stringResource(R.string.forget_server)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        modifier = Modifier.lanternDialogEdge())
}
