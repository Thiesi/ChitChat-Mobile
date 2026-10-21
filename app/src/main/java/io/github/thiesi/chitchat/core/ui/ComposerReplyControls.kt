package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R

/** Inline in constrained windows so a reply never pushes the input or Send below the IME. */
@Composable
internal fun ComposerReplyControl(compact: Boolean, title: String, preview: String, enabled: Boolean,
    onOptions: () -> Unit, onCancel: () -> Unit, warning: Boolean = false) {
    val options = stringResource(R.string.composer_reply_options)
    val remove = stringResource(R.string.composer_reply_remove)
    if (compact) Box(Modifier.testTag("composer_reply")) { IconButton(onClick = onOptions, enabled = enabled,
        modifier = Modifier.testTag("composer_reply_options").semantics {
            contentDescription = options; stateDescription = title
        }) { Text(if (warning) "!" else "\u21a9") } }
    else Surface(Modifier.fillMaxWidth().testTag("composer_reply"), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).testTag("composer_reply_options")
                .clickable(enabled = enabled, onClickLabel = options, onClick = onOptions).padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onCancel, enabled = enabled,
                modifier = Modifier.testTag("composer_reply_remove").semantics { contentDescription = remove }) { Text("\u00d7") }
        }
    }
}

@Composable
internal fun ComposerReplyOptions(title: String, preview: String, canCancel: Boolean,
    onOpenOriginal: (() -> Unit)?, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("composer_reply_dialog"),
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(preview, modifier = Modifier.testTag("composer_reply_preview"))
            if (onOpenOriginal != null) TextButton(onClick = onOpenOriginal, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.message_target_open_quote))
            }
            TextButton(onClick = onCancel, enabled = canCancel,
                modifier = Modifier.fillMaxWidth().testTag("composer_reply_remove")) {
                Text(stringResource(R.string.composer_reply_remove))
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rooms_close)) } })
}
