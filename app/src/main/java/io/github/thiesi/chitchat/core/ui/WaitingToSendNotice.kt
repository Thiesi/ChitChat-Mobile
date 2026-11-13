package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R

/** Send when back online: the line above the composer while a draft waits, with Cancel (48dp). */
@Composable
internal fun WaitingToSendNotice(text: String, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("waiting_notice"), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("waiting_cancel")) {
            Text(stringResource(R.string.cancel))
        }
    }
}
