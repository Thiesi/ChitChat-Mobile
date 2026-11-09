package io.github.thiesi.chitchat.core.media

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal sealed interface AttachmentPreparationOutcome {
    class Prepared(val retained: RetainedAttachmentPreparation) : AttachmentPreparationOutcome
    class Failed(val kind: PreparationFailureKind) : AttachmentPreparationOutcome
}

/** One frozen source, one bounded producer, one synchronous Main-thread adoption decision.
 * The feature owner must recheck the original session/slot/selection in deliver and return
 * true only after atomically retaining the handle. Explicitly started local work may finish
 * into that private slot across navigation/background; visible-pane adoption and actions
 * still require a fresh actual-entry check. False discards the result.
 * Never store a handle and then suspend before deciding. This task grants no consent,
 * draft-slot capacity, upload or retry authority, and never reopens a provider.
 */
internal class AttachmentPreparationTask(
    private val read: PickedAttachmentRead,
    private val source: AttachmentPickSource,
    private val budget: AttachmentMemoryBudget,
    private val readLimitBytes: Long,
) {
    private val claimed = AtomicBoolean()

    init { require(readLimitBytes in 1..MAX_ATTACHMENT_SNAPSHOT_BYTES) { "Invalid attachment read budget" } }

    fun start(scope: CoroutineScope,
        deliver: (AttachmentPreparationTask, AttachmentPreparationOutcome) -> Boolean,
    ): Job {
        check(claimed.compareAndSet(false, true)) { "Attachment preparation already started" }
        return scope.launch(Dispatchers.Main.immediate) {
            var reservation: AttachmentMemoryBudget.Reservation? = null
            var retained: RetainedAttachmentPreparation? = null
            var adopted = false
            try {
                ensureActive()
                val outcome = try {
                    // Both possible byte sets plus bounded parser/hash/segment working space.
                    // Client allocation costs never use provider size hints or server limits.
                    val peak = readLimitBytes * 2 + WORKSPACE_BYTES
                    reservation = budget.reserve(peak)
                        ?: throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
                    val original = read.snapshot(readLimitBytes)
                    ensureActive()
                    val prepared = prepareAttachment(original, source)
                    ensureActive()
                    val outputBytes = (prepared as? AttachmentPreparation.Ready)?.payload?.sizeBytes ?: 0
                    // Keep the source allocation charge even when cleaned views share its backing
                    // segments. Only the now-finished producer's working charge is released.
                    val charge = rounded(original.bytes.size.toLong()) + rounded(outputBytes) + RETAINED_OVERHEAD_BYTES
                    if (charge > peak) throw AttachmentPreparationFailure(PreparationFailureKind.CLIENT_BUDGET)
                    check(reservation!!.resize(charge)) { "Attachment reservation retired" }
                    val handle = RetainedAttachmentPreparation(prepared, reservation!!)
                    retained = handle
                    AttachmentPreparationOutcome.Prepared(handle)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AttachmentPreparationFailure) {
                    AttachmentPreparationOutcome.Failed(failure.kind)
                } catch (_: Exception) {
                    // Provider/parser implementation messages can contain private input.
                    AttachmentPreparationOutcome.Failed(PreparationFailureKind.READ_FAILED)
                }
                ensureActive()
                // No suspension after adoption: a cancelled dispatcher handoff cannot lose a
                // resource that only the receiving caller knew it was supposed to close.
                adopted = deliver(this@AttachmentPreparationTask, outcome) && retained != null
            } finally {
                // withContext children have ended before this finally, even if provider IO ignored
                // cancellation temporarily. Never free a producer's charge from Job.cancel().
                if (!adopted) {
                    // Once wrapped, the handle owns the lease. Its exclusive successor may
                    // already own it; the old reservation alias must not free that successor.
                    val owner = retained
                    if (owner == null) reservation?.close() else owner.close()
                }
            }
        }
    }

    private fun rounded(bytes: Long) = ((bytes + 8191) / 8192) * 8192

    companion object {
        /** Freeze a conservative read ceiling from currently free process memory. The later
         * atomic reservation can still lose a race; denial never opens or retries the source.
         */
        fun forAvailableMemory(read: PickedAttachmentRead, source: AttachmentPickSource,
            budget: AttachmentMemoryBudget): AttachmentPreparationTask {
            val available = budget.capacityBytes - budget.reservedBytes
            val limit = ((available - WORKSPACE_BYTES).coerceAtLeast(0) / 2)
                .coerceIn(1, MAX_ATTACHMENT_SNAPSHOT_BYTES)
            return AttachmentPreparationTask(read, source, budget, limit)
        }

        private const val WORKSPACE_BYTES = 4L * 1024 * 1024
        // Padding for backing-array headers, directories and the bounded chunk/segment tables;
        // logical bytes alone undercount sliced, segmented photo outputs.
        private const val RETAINED_OVERHEAD_BYTES = 1024L * 1024
    }
}
