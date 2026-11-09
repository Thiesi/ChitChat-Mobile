package io.github.thiesi.chitchat.core.media

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One explicit original-bound privacy choice, never a Send request or automatic fallback.
 * The feature owner must validate current entry/owner/slot/choice before start. After start
 * this task owns the source handle, including when its DEFAULT coroutine never enters.
 * No other byte user may exist. Native thumbnails/transport need their own lifetime protocol.
 */
internal class OriginalPhotoPreparationTask(
    original: RetainedAttachmentPreparation,
    expected: AttachmentPreparation.NeedsChoice,
) {
    private val source = AtomicReference<RetainedAttachmentPreparation?>(original)
    private val claimed = AtomicBoolean()
    init { require(original.preparation === expected) { "Original photo choice changed" } }

    fun start(scope: CoroutineScope,
        deliver: (OriginalPhotoPreparationTask, AttachmentPreparationOutcome) -> Boolean,
    ): Job {
        check(claimed.compareAndSet(false, true)) { "Original photo preparation already started" }
        val original = checkNotNull(source.getAndSet(null))
        val pending = try {
            scope.launch(Dispatchers.Main.immediate) {
                var input: OriginalAttachmentAllocation? = null
                var retained: RetainedAttachmentPreparation? = null
                var adopted = false
                try {
                    ensureActive()
                    val outcome = try {
                        // A handle's preparation can only become null, never a different choice.
                        val choice = original.preparation as? AttachmentPreparation.NeedsChoice
                            ?: throw AttachmentPreparationFailure(PreparationFailureKind.ALREADY_USED)
                        val owned = original.takeOriginal(choice)
                            ?: throw AttachmentPreparationFailure(PreparationFailureKind.ALREADY_USED)
                        input = owned
                        val snapshot = owned.original
                        val size = snapshot.bytes.size.toLong()
                        if (size == 0L) throw AttachmentPreparationFailure(PreparationFailureKind.EMPTY)
                        if (size > MAX_ATTACHMENT_SNAPSHOT_BYTES || !owned.resize(rounded(size) * 2 + WORKSPACE_BYTES))
                            throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
                        val payload = PreparedAttachmentPayload.create(snapshot.filename, snapshot.mimeType, snapshot.bytes)
                        ensureActive()
                        // The Default hash worker ended before working space is released.
                        check(owned.resize(rounded(size) * 2 + RETAINED_OVERHEAD_BYTES)) { "Original preparation retired" }
                        val handle = owned.retain(AttachmentPreparation.Ready(payload, AttachmentPhotoPrivacy.UNCHANGED_AFTER_CHOICE))
                        retained = handle
                        AttachmentPreparationOutcome.Prepared(handle)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: AttachmentPreparationFailure) { AttachmentPreparationOutcome.Failed(failure.kind) }
                    catch (_: Exception) { AttachmentPreparationOutcome.Failed(PreparationFailureKind.READ_FAILED) }
                    ensureActive()
                    // One synchronous Main adoption. Throw/reject cannot produce another callback.
                    adopted = deliver(this@OriginalPhotoPreparationTask, outcome) && retained != null
                } finally {
                    if (!adopted) retained?.close()
                    input?.close()
                    original.close()
                }
            }
        } catch (failure: Throwable) { original.close(); throw failure }
        // Terminal completion, not cancellation notification. DEFAULT may skip the body entirely.
        // Only a thread-safe atomic handle/ledger close runs here, never a UI callback or wait.
        pending.invokeOnCompletion { original.close() }
        return pending
    }

    private fun rounded(bytes: Long) = ((bytes + 8191) / 8192) * 8192
    private companion object {
        const val WORKSPACE_BYTES = 4L * 1024 * 1024
        const val RETAINED_OVERHEAD_BYTES = 1024L * 1024
    }
}
