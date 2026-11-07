package io.github.thiesi.chitchat.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.formatting.MessageFormatting

/** Explicit user export to another app, never session HTTP, preview fetching or notification routing.
 * Callers must retain original selection/session/entry/canonical guards before invoking this.
 */
internal fun openMessageLink(context: Context, target: String) {
    val opened = if (MessageFormatting.safeLinkTarget(target) != target) false else try {
        context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
    if (!opened) Toast.makeText(context, R.string.message_link_failed, Toast.LENGTH_SHORT).show()
}
