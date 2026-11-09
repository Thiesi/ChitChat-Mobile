package io.github.thiesi.chitchat.core.media

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString

/** Raw selected bytes only. Not a cleaned photo, reconciliation digest or upload attempt.
 * The later picker/owner adapter must freeze the original source and cancel/retire its job.
 * Nothing here persists a URI/grant, opens HTTP or gives permission to send.
 */
internal class PickedAttachmentRead(
    internal val filename: String,
    internal val mimeType: String?,
    open: () -> InputStream,
) {
    private val source = AtomicReference<(() -> InputStream)?>(open)
    init { validateAttachmentMetadata(filename, mimeType) }

    /** Client allocation budget, not the server's post-cleaning byte limit. */
    suspend fun snapshot(clientBudgetBytes: Long): PickedAttachmentSnapshot = withContext(Dispatchers.IO) {
        require(clientBudgetBytes in 1..MAX_ATTACHMENT_SNAPSHOT_BYTES) { "Invalid attachment memory budget" }
        coroutineContext.ensureActive()
        val open = source.getAndSet(null) ?: throw AttachmentPreparationFailure(PreparationFailureKind.ALREADY_USED)
        try {
            open().use { stream ->
                coroutineContext.ensureActive()
                val buffer = Buffer()
                val chunk = ByteArray(8192)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    // Read one extra byte to distinguish exact size from overflow; never buffer it.
                    val wanted = minOf(chunk.size.toLong(), clientBudgetBytes - total + 1).toInt()
                    var count = stream.read(chunk, 0, wanted)
                    coroutineContext.ensureActive()
                    if (count < 0) break
                    if (count == 0) {
                        val one = stream.read()
                        coroutineContext.ensureActive()
                        if (one < 0) break
                        chunk[0] = one.toByte(); count = 1
                    }
                    if (count > wanted) throw AttachmentPreparationFailure(PreparationFailureKind.READ_FAILED)
                    total += count
                    if (total > clientBudgetBytes) throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
                    buffer.write(chunk, 0, count)
                }
                if (total == 0L) throw AttachmentPreparationFailure(PreparationFailureKind.EMPTY)
                PickedAttachmentSnapshot(filename, mimeType, buffer.snapshot())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            coroutineContext.ensureActive() // Cancellation wins over a failing provider read/close.
            if (failure is AttachmentPreparationFailure) throw failure
            // Provider exceptions may contain URIs, names or private paths; retain no cause/message.
            throw AttachmentPreparationFailure(PreparationFailureKind.READ_FAILED)
        }
    }
}

/** Per-snapshot hard ceiling; the future consumer may select a smaller device budget. */
internal const val MAX_ATTACHMENT_SNAPSHOT_BYTES = 32L * 1024 * 1024

/** Internal raw bytes must pass photo cleaning/explicit failure consent before an upload. */
internal class PickedAttachmentSnapshot internal constructor(val filename: String, val mimeType: String?, val bytes: ByteString)
internal enum class PreparationFailureKind { ALREADY_USED, CLIENT_BUDGET, EMPTY, READ_FAILED }
internal class AttachmentPreparationFailure(val kind: PreparationFailureKind) : IOException(kind.name)
