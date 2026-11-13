package io.github.thiesi.chitchat.feature.status

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.ChitChatTheme

/**
 * A quiet line at a transcript's live edge while offline (status-presentation.md "Offline"). No loaded-at
 * time: no screen records when it last loaded, and nothing is tracked just for this caption.
 */
@Composable
internal fun OfflineLiveEdgeCaption(show: Boolean) {
    if (!show) return
    Text(stringResource(R.string.status_offline_live_edge), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        .testTag("offline_live_edge"), style = MaterialTheme.typography.bodySmall, color = ChitChatTheme.colors.textMuted,
        textAlign = TextAlign.Center)
}
