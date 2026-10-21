package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.thiesi.chitchat.R

/** Consistent 48dp action leaves useful input width even on a narrow, keyboard-resized phone. */
@Composable
internal fun MessageSendButton(label: String, enabled: Boolean, sending: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(onClick = onClick, enabled = enabled && !sending,
        modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.ic_message_send), contentDescription = null, Modifier.size(24.dp))
    }
}
