package io.github.thiesi.chitchat.feature.account

import android.content.ContentResolver
import android.net.Uri
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only a URI returned by this app's ACTION_CREATE_DOCUMENT request; no persisted URI grant. */
internal class DocumentExportDestination(private val resolver: ContentResolver, private val uri: Uri) : ExportDestination {
    override suspend fun write(bytes: ByteArray, ensureCurrent: () -> Unit) = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (uri.scheme != "content") throw ApiFailure(FailureKind.STORAGE)
            val stream = resolver.openOutputStream(uri, "wt") ?: throw ApiFailure(FailureKind.STORAGE)
            stream.use {
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    ensureCurrent()
                    val size = minOf(32 * 1024, bytes.size - offset)
                    it.write(bytes, offset, size)
                    offset += size
                }
                it.flush()
            }
        } catch (failure: ApiFailure) { throw failure }
        catch (_: IOException) { throw ApiFailure(FailureKind.STORAGE) }
        catch (_: SecurityException) { throw ApiFailure(FailureKind.STORAGE) }
        catch (_: IllegalArgumentException) { throw ApiFailure(FailureKind.STORAGE) }
        catch (_: UnsupportedOperationException) { throw ApiFailure(FailureKind.STORAGE) }
    }
}
