package io.github.thiesi.chitchat.core.media

/** Shared allocation accounting for later attachment workers and retained drafts.
 * Charges are client memory costs, not server limits or necessarily file byte counts.
 * The process owner must share this ledger across session retirement. A cancellation
 * request does not free memory: close only after its producer and retained bytes end.
 * This primitive holds no content, jobs, owners or send/consent authority.
 */
internal class AttachmentMemoryBudget(val capacityBytes: Long) {
    private val lock = Any()
    private var used = 0L

    init { require(capacityBytes > 0) { "Invalid attachment memory capacity" } }

    val reservedBytes: Long get() = synchronized(lock) { used }

    /** Reserve before allocation. Failure has no effect on any existing reservation. */
    fun reserve(bytes: Long): Reservation? = synchronized(lock) {
        require(bytes > 0) { "Invalid attachment memory reservation" }
        if (bytes > capacityBytes - used) return@synchronized null
        used += bytes
        Lease(bytes)
    }

    interface Reservation : AutoCloseable {
        val reservedBytes: Long

        /** Grow before allocating, shrink only after the actual memory is released.
         * False means no change: insufficient capacity or an already closed lease.
         */
        fun resize(bytes: Long): Boolean
        override fun close()
    }

    private inner class Lease(private var amount: Long) : Reservation {
        override val reservedBytes: Long get() = synchronized(lock) { amount }

        override fun resize(bytes: Long): Boolean = synchronized(lock) {
            require(bytes > 0) { "Invalid attachment memory reservation" }
            if (amount == 0L) return@synchronized false
            if (bytes > amount && bytes - amount > capacityBytes - used) return@synchronized false
            used = used - amount + bytes
            amount = bytes
            true
        }

        /** Idempotent; a stale close can release only this lease, never a successor. */
        override fun close() = synchronized(lock) {
            used -= amount
            amount = 0L
        }
    }
}
