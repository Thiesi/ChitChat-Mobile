package io.github.thiesi.chitchat.core.media

import java.util.concurrent.atomic.AtomicReference

/** Owned bytes and their charge, never privacy/send authority. Keep alive for every byte user;
 * no byte reference may outlive close or exclusive transfer. No external byte users exist for
 * the private original-photo choice consumer; later thumbnail/transport users must coordinate
 * their actual lifetime before exposing or transferring these bytes.
 */
internal class RetainedAttachmentPreparation internal constructor(
    preparation: AttachmentPreparation,
    reservation: AttachmentMemoryBudget.Reservation,
) : AutoCloseable {
    private class Owned(val preparation: AttachmentPreparation, val reservation: AttachmentMemoryBudget.Reservation)
    private val owned = AtomicReference<Owned?>(Owned(preparation, reservation))
    val preparation: AttachmentPreparation? get() = owned.get()?.preparation
    val reservedBytes: Long get() = owned.get()?.reservation?.reservedBytes ?: 0

    override fun close() { owned.getAndSet(null)?.reservation?.close() }

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
