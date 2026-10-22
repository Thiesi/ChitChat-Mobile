package io.github.thiesi.chitchat.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.thiesi.chitchat.R

/** Transient UI origin, never saved. Reopening even the same row creates a distinct request. */
internal class MessageActionSelection(val messageId: Long)

@Composable
internal fun MessageActions(available: Boolean, canReply: Boolean, canCopy: Boolean,
    onReply: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null, canEdit: Boolean = false,
    onDelete: (() -> Unit)? = null, canDelete: Boolean = false) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("message_actions"),
        title = { Text(stringResource(R.string.message_actions)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!available) Text(stringResource(R.string.message_actions_unavailable))
            else if (!canReply) Text(stringResource(R.string.message_reply_disabled))
            TextButton(onClick = onReply, enabled = available && canReply,
                modifier = Modifier.fillMaxWidth().testTag("message_reply_action")) {
                Text(stringResource(R.string.message_reply_action))
            }
            TextButton(onClick = onCopy, enabled = available && canCopy,
                modifier = Modifier.fillMaxWidth().testTag("message_copy_action")) {
                Text(stringResource(R.string.message_copy_action))
            }
            onEdit?.let { action ->
                TextButton(onClick = action, enabled = available && canEdit,
                    modifier = Modifier.fillMaxWidth().testTag("message_edit_action")) {
                    Text(stringResource(R.string.message_edit_action))
                }
            }
            onDelete?.let { action ->
                TextButton(onClick = action, enabled = available && canDelete,
                    modifier = Modifier.fillMaxWidth().testTag("message_delete_action")) {
                    Text(stringResource(R.string.message_delete_action))
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Explicit user export only. Do not copy automatically, log contents, or clear another app's clip. */
internal fun copyMessage(context: Context, text: String) {
    val clip = ClipData.newPlainText(context.getString(R.string.app_name), text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    val copied = try {
        val manager = context.getSystemService(ClipboardManager::class.java)
        if (manager == null) false else { manager.setPrimaryClip(clip); true }
    } catch (_: SecurityException) { false }
    if (!copied || Build.VERSION.SDK_INT < 33) Toast.makeText(context,
        if (copied) R.string.message_copied else R.string.message_copy_failed, Toast.LENGTH_SHORT).show()
}
