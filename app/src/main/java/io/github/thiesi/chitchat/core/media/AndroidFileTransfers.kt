package io.github.thiesi.chitchat.core.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.session.SessionState
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

/**
 * A document this app's own ACTION_CREATE_DOCUMENT returned (F5 Save). Opened with "wt" only at the first body byte, so a
 * failure before the body never truncates an existing file the person picked (TL, 2026-11-20). Only `content:` URIs;
 * no persisted grant is taken; the document is never deleted.
 */
internal class DocumentSaveDestination(private val resolver: ContentResolver, private val uri: Uri) : SaveDestination {
    override fun open(): OutputStream {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw ApiFailure(FailureKind.STORAGE)
        return try { resolver.openOutputStream(uri, "wt") } catch (_: Exception) { null } ?: throw ApiFailure(FailureKind.STORAGE)
    }
}

/** Free space on the cache volume for a share copy (API 26+). */
internal class AndroidCacheSpace(private val context: Context) : CacheSpace {
    override fun allocatableBytes(): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir))
    } catch (_: Exception) { context.cacheDir.usableSpace }
}

/** The share copies' FileProvider (`res/xml/share_paths.xml` exposes only [SHARE_FOLDER]); read-only grants on intents. */
internal object ShareProvider {
    const val SHARE_FOLDER = "shared"

    fun authority(context: Context) = "${context.packageName}.files"

    fun root(context: Context) = File(context.cacheDir, SHARE_FOLDER)

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Share: the system share sheet for a verified copy, with the server's declared MIME type (never sniffed; a missing
     * type is `application/octet-stream`). Only a temporary read grant rides on the intent: never write, never
     * persistable, never a global grant. For DMs no target is preselected (files-media.md).
     */
    fun shareIntent(context: Context, ready: TransferState.Ready): Intent {
        val uri = uriFor(context, ready.copy.file)
        val send = Intent(Intent.ACTION_SEND).setType(ready.mimeType ?: OCTET_STREAM).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(ready.copy.file.name, uri)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Open: ACTION_VIEW with the declared MIME type; with none (or octet-stream) a plain chooser (TL, 2026-11-19). The
     * caller treats ActivityNotFoundException as "no app opens this file type" ([FileTransfers.noApp]).
     */
    fun openIntent(context: Context, ready: TransferState.Ready): Intent {
        val uri = uriFor(context, ready.copy.file)
        val type = ready.mimeType?.takeUnless { it.equals(OCTET_STREAM, ignoreCase = true) }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type ?: OCTET_STREAM).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (type == null) Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else view
    }

    private const val OCTET_STREAM = "application/octet-stream"

    /** Before a copy is deleted: any grant handed out for it ends with it. */
    fun revoke(context: Context, file: File) {
        try { context.revokeUriPermission(uriFor(context, file), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
    }
}

/**
 * The app-scoped owner of Save, Share and Open (F5), in MainActivity like [AttachmentImagesViewModel]: one transfer at a
 * time across rooms and DMs, fenced to the session it started in, surviving navigation and configuration changes.
 */
class FileTransfersViewModel internal constructor(session: StateFlow<SessionState>, source: FileSource, copies: ShareCopies,
    space: CacheSpace) : ViewModel() {
    internal val transfers = FileTransfers(viewModelScope, session, source, copies, space)

    companion object {
        internal fun factory(context: Context, session: StateFlow<SessionState>, downloads: AttachmentDownloads, copies: ShareCopies):
            ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FileTransfersViewModel(session, { ref, expected, open, ensureCurrent -> downloads.stream(ref, expected, open, ensureCurrent) },
                    copies, AndroidCacheSpace(context.applicationContext))
            }
        }
    }
}
