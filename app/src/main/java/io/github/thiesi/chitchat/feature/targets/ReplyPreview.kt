package io.github.thiesi.chitchat.feature.targets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R

/** Untrusted plain text, never HTML or identity-by-username. No content enters saved state. */
@Composable
internal fun ReplyPreview(text: String, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (onOpen == null) Text(text, modifier, style = MaterialTheme.typography.bodySmall)
    else OutlinedCard(onClick = onOpen, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.message_target_open_quote), style = MaterialTheme.typography.labelMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun RequestedMessageMarker(id: Long) {
    Text(stringResource(R.string.message_target_marker), Modifier.testTag("requested_message:$id")
        .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}
