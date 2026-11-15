package io.github.thiesi.chitchat.core.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.theme.lanternDialogEdge

/**
 * The explicit external choice (P-176, P-221): it names the destination before anything opens, and adds nothing from the
 * session. `https` opens the browser and `mailto` the mail app; anything else (an `http` link included) gets the
 * insecure-address line and no open button.
 */
@Composable
internal fun ExternalLinkChoice(url: String, title: String, tagPrefix: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = url.substringBefore(':').lowercase()
    val mail = scheme == "mailto"
    val secure = scheme == "https" || mail
    val destination = if (mail) url.substringAfter(':').substringBefore('?') else runCatching { url.toUri().host }.getOrNull().orEmpty()
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.lanternDialogEdge().testTag("${tagPrefix}_dialog"),
        title = { Text(if (secure) title else stringResource(R.string.external_link_insecure_title)) },
        text = { DialogScrollBody(Modifier.testTag("${tagPrefix}_body")) {
            Text(when {
                !secure -> stringResource(R.string.external_link_insecure, destination)
                mail -> stringResource(R.string.a11y_feedback_mail, destination)
                else -> stringResource(R.string.a11y_feedback_web, destination)
            })
        } },
        confirmButton = {
            if (secure) TextButton(onClick = {
                onDismiss()
                val intent = if (mail) Intent(Intent.ACTION_SENDTO, url.toUri())
                    else Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
                try { context.startActivity(intent) }
                catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.a11y_feedback_failed, Toast.LENGTH_SHORT).show() }
                catch (_: SecurityException) { Toast.makeText(context, R.string.a11y_feedback_failed, Toast.LENGTH_SHORT).show() }
            }, Modifier.testTag("${tagPrefix}_open")) {
                Text(stringResource(if (mail) R.string.a11y_feedback_open_mail else R.string.a11y_feedback_open_web))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, Modifier.testTag("${tagPrefix}_cancel")) {
            Text(stringResource(if (secure) R.string.cancel else R.string.close))
        } })
}
