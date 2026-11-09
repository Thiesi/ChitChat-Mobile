package io.github.thiesi.chitchat.core.media

import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Immutable eventual bytes and their evidence, never authority to send or to skip photo privacy.
 * The later preparation consumer must clean photos (or obtain the original explicit failure
 * consent) BEFORE this factory. No provider, raw snapshot or recovery choice is retained here.
 * Keep this, its filename and digest out of logs, saved state and disk.
 */
internal class PreparedAttachmentPayload private constructor(
    val filename: String,
    val mimeType: String?,
    internal val bytes: ByteString,
    val sha256: String,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    companion object {
        suspend fun create(filename: String, mimeType: String?, bytes: ByteString): PreparedAttachmentPayload =
            withContext(Dispatchers.Default) {
                coroutineContext.ensureActive()
                validateAttachmentMetadata(filename, mimeType)
                if (bytes.size == 0) throw AttachmentPreparationFailure(PreparationFailureKind.EMPTY)
                if (bytes.size.toLong() > MAX_ATTACHMENT_SNAPSHOT_BYTES)
                    throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
                val digest = MessageDigest.getInstance("SHA-256")
                var offset = 0
                while (offset < bytes.size) {
                    coroutineContext.ensureActive()
                    val end = minOf(offset + 8192, bytes.size)
                    // Bounded transient copies; never flatten a segmented snapshot in full.
                    digest.update(bytes.substring(offset, end).toByteArray())
                    offset = end
                }
                coroutineContext.ensureActive()
                PreparedAttachmentPayload(filename, mimeType, bytes, digest.digest().toByteString().hex())
            }
    }
}
