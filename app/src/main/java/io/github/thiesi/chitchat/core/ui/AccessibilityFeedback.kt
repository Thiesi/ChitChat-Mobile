package io.github.thiesi.chitchat.core.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge

/**
 * P-221: "Accessibility feedback" for a validated https or mailto [url]; nothing when null (presence gating).
 * Opening is an explicit choice that names the destination; nothing from the session is added.
 */
@Composable
internal fun AccessibilityFeedbackLink(url: String?, modifier: Modifier = Modifier) {
    if (url == null) return
    var choosing by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { choosing = true }, modifier = modifier.heightIn(min = 48.dp).testTag("a11y_feedback")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The label may wrap; the icon always keeps its 24dp (J1 on PR258: without weight it collapsed at 200%).
            Text(stringResource(R.string.a11y_feedback), Modifier.weight(1f, fill = false))
            Icon(painterResource(R.drawable.ic_open_external), contentDescription = null, Modifier.testTag("a11y_feedback_icon"))
        }
    }
    if (choosing) AccessibilityFeedbackChoice(url) { choosing = false }
}

@Composable
private fun AccessibilityFeedbackChoice(url: String, onDismiss: () -> Unit) =
    ExternalLinkChoice(url, stringResource(R.string.a11y_feedback), "a11y_feedback", onDismiss) // Validated https or mailto.
