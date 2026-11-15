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
            Text(stringResource(R.string.a11y_feedback))
            Icon(painterResource(R.drawable.ic_open_external), contentDescription = null)
        }
    }
    if (choosing) AccessibilityFeedbackChoice(url) { choosing = false }
}

@Composable
private fun AccessibilityFeedbackChoice(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mail = url.startsWith("mailto:")
    val destination = if (mail) url.removePrefix("mailto:").substringBefore('?') else url.toUri().host.orEmpty()
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.lanternDialogEdge().testTag("a11y_feedback_dialog"),
        title = { Text(stringResource(R.string.a11y_feedback)) },
        text = { DialogScrollBody(Modifier.testTag("a11y_feedback_body")) {
            Text(stringResource(if (mail) R.string.a11y_feedback_mail else R.string.a11y_feedback_web, destination))
        } },
        confirmButton = { TextButton(onClick = {
            onDismiss()
            val intent = if (mail) Intent(Intent.ACTION_SENDTO, url.toUri())
                else Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
            try { context.startActivity(intent) }
            catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.a11y_feedback_failed, Toast.LENGTH_SHORT).show() }
            catch (_: SecurityException) { Toast.makeText(context, R.string.a11y_feedback_failed, Toast.LENGTH_SHORT).show() }
        }, Modifier.testTag("a11y_feedback_open")) {
            Text(stringResource(if (mail) R.string.a11y_feedback_open_mail else R.string.a11y_feedback_open_web))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
