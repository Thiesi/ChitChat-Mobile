package io.github.thiesi.chitchat.core.media

import java.util.concurrent.atomic.AtomicReference

/** Owned bytes and their charge, never privacy/send authority. READY uploads hold the same
 * reservation independently of the private draft, until the concrete transport operation ends.
 * Other byte users (including future thumbnails) still need an explicit lifetime protocol.
 */
internal class RetainedAttachmentPreparation internal constructor(
    preparation: AttachmentPreparation,
    reservation: AttachmentMemoryBudget.Reservation,
) : AutoCloseable {
    private class Owned(val preparation: AttachmentPreparation, val reservation: AttachmentMemoryBudget.Reservation) {
        private val lock = Any()
        private var draftOpen = true
        private var uploadOpen = false

        fun upload(expected: AttachmentPreparation.Ready): UploadAttachmentAllocation? = synchronized(lock) {
            if (!draftOpen || uploadOpen || preparation !== expected) return@synchronized null
            val allocation = UploadAttachmentAllocation(expected.payload) { finishUpload() }
            uploadOpen = true
            allocation
        }

        fun closeDraft() = synchronized(lock) {
            if (!draftOpen) return@synchronized
            draftOpen = false
            if (!uploadOpen) reservation.close()
        }

        private fun finishUpload() = synchronized(lock) {
            uploadOpen = false
            if (!draftOpen) reservation.close()
        }
    }
    private val owned = AtomicReference<Owned?>(Owned(preparation, reservation))
    val preparation: AttachmentPreparation? get() = owned.get()?.preparation
    val reservedBytes: Long get() = owned.get()?.reservation?.reservedBytes ?: 0

    override fun close() { owned.getAndSet(null)?.closeDraft() }

    /** One original READY reference, one active transport hold; no copied bytes/new charge.
     * The caller must keep this hold until actual transport stop, not cancellation notification.
     * Releasing it grants no permission for another attempt or any delivery inference.
     */
    internal fun holdUpload(expected: AttachmentPreparation.Ready): UploadAttachmentAllocation? =
        owned.get()?.upload(expected)

    /** Exclusive move of this exact private choice. Old close cannot free the new owner's lease. */
    internal fun takeOriginal(expected: AttachmentPreparation.NeedsChoice): OriginalAttachmentAllocation? {
        while (true) {
            val current = owned.get() ?: return null
            if (current.preparation !== expected) return null
            if (owned.compareAndSet(current, null)) {
                try { return OriginalAttachmentAllocation(expected, current.reservation) }
                catch (failure: Throwable) { current.reservation.close(); throw failure }
            }
        }
    }
}

/** Transport-only hold. The concrete repository releases it after its upload call's finally
 * has awaited the actual callback/writer. A stale close cannot affect a newer hold or draft.
 */
internal class UploadAttachmentAllocation internal constructor(payload: PreparedAttachmentPayload, finish: () -> Unit) : AutoCloseable {
    private class Owned(val payload: PreparedAttachmentPayload, val finish: () -> Unit)
    private val owned = AtomicReference<Owned?>(Owned(payload, finish))
    val payload: PreparedAttachmentPayload get() = checkNotNull(owned.get()) { "Upload allocation retired" }.payload
    override fun close() { owned.getAndSet(null)?.finish?.invoke() }
}

/** One task's exclusive input; not an upload-ready payload or photo consent. */
internal class OriginalAttachmentAllocation internal constructor(
    choice: AttachmentPreparation.NeedsChoice,
    reservation: AttachmentMemoryBudget.Reservation,
) : AutoCloseable {
    private class Owned(val choice: AttachmentPreparation.NeedsChoice, val reservation: AttachmentMemoryBudget.Reservation)
    private val owned = AtomicReference<Owned?>(Owned(choice, reservation))
    val original: PickedAttachmentSnapshot get() = checkNotNull(owned.get()) { "Original preparation retired" }.choice.original
    val reservedBytes: Long get() = owned.get()?.reservation?.reservedBytes ?: 0
    fun resize(bytes: Long) = owned.get()?.reservation?.resize(bytes) == true

    fun retain(prepared: AttachmentPreparation.Ready): RetainedAttachmentPreparation {
        val current = checkNotNull(owned.getAndSet(null)) { "Original preparation retired" }
        try { return RetainedAttachmentPreparation(prepared, current.reservation) }
        catch (failure: Throwable) { current.reservation.close(); throw failure }
    }

    override fun close() { owned.getAndSet(null)?.reservation?.close() }
}
