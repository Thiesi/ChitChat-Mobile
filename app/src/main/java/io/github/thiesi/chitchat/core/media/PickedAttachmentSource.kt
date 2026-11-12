package io.github.thiesi.chitchat.core.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Freezes one picker result into a one-shot read. The URI is never persisted, logged or
 * reopened: the returned read opens it at most once, inside the owner's preparation task.
 * Unreadable metadata falls back to a neutral name and an unknown type; an unreadable file
 * fails later as a local read failure, never as a server refusal.
 */
internal suspend fun pickedAttachmentRead(resolver: ContentResolver, uri: Uri): PickedAttachmentRead =
    withContext(Dispatchers.IO) {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
        val type = try { resolver.getType(uri) } catch (_: Exception) { null }
        PickedAttachmentRead(pickedAttachmentFilename(name), pickedAttachmentMimeType(type)) {
            resolver.openInputStream(uri) ?: throw IOException("Provider returned no stream")
        }
    }
