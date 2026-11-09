package io.github.thiesi.chitchat.core.media

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class AttachmentMemoryBudgetTest {
    @Test fun exactCapacityBlocksAnotherFileUntilItsOwnLeaseIsReleased() {
        val budget = AttachmentMemoryBudget(10)
        val first = budget.reserve(6)!!
        val second = budget.reserve(4)!!
        assertEquals(10L, budget.reservedBytes)
        assertNull(budget.reserve(1))
        first.close()
        assertEquals(4L, second.reservedBytes)
        val next = budget.reserve(6)!!
        assertNull(budget.reserve(1))
        next.close(); second.close()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun rejectedGrowthKeepsBothExistingChargesAndBytesAvailable() {
        val budget = AttachmentMemoryBudget(10)
        val preparing = budget.reserve(6)!!
        val retained = budget.reserve(3)!!
        assertFalse(preparing.resize(8))
        assertEquals(6L, preparing.reservedBytes)
        assertEquals(3L, retained.reservedBytes)
        assertEquals(9L, budget.reservedBytes)
        assertTrue(preparing.resize(7))
        assertEquals(10L, budget.reservedBytes)
        preparing.close(); retained.close()
    }

    @Test fun shrinkingAfterReleaseMakesOnlyTheDifferenceAvailable() {
        val budget = AttachmentMemoryBudget(10)
        val lease = budget.reserve(10)!!
        assertTrue(lease.resize(4))
        val other = budget.reserve(6)!!
        assertNull(budget.reserve(1))
        assertTrue(lease.resize(4))
        lease.close(); lease.close()
        assertEquals(0L, lease.reservedBytes)
        assertEquals(6L, budget.reservedBytes)
        other.close()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun retiredLeaseCannotGrowOrReleaseItsSuccessorsMemory() {
        val budget = AttachmentMemoryBudget(8)
        val old = budget.reserve(8)!!
        old.close()
        val successor = budget.reserve(8)!!
        assertFalse(old.resize(1))
        old.close()
        assertEquals(8L, budget.reservedBytes)
        assertEquals(8L, successor.reservedBytes)
        assertNull(budget.reserve(1))
        successor.close()
    }

    @Test fun reservationsInDifferentLedgersCannotAffectEachOther() {
        val first = AttachmentMemoryBudget(8)
        val second = AttachmentMemoryBudget(8)
        val old = first.reserve(8)!!
        val current = second.reserve(8)!!
        old.close()
        assertEquals(0L, first.reservedBytes)
        assertEquals(8L, second.reservedBytes)
        current.close()
    }

    @Test fun arithmeticAtLongCapacityNeverWrapsOrAdmitsAnOversubscription() {
        val budget = AttachmentMemoryBudget(Long.MAX_VALUE)
        val first = budget.reserve(Long.MAX_VALUE - 2)!!
        val second = budget.reserve(2)!!
        assertNull(budget.reserve(1))
        assertFalse(first.resize(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, budget.reservedBytes)
        second.close()
        assertTrue(first.resize(Long.MAX_VALUE))
        assertNull(budget.reserve(Long.MAX_VALUE))
        first.close()
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun invalidCostsDoNotChangeAnyLiveReservation() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            try { AttachmentMemoryBudget(bad); fail() } catch (_: IllegalArgumentException) { }
            val budget = AttachmentMemoryBudget(8)
            val lease = budget.reserve(4)!!
            try { budget.reserve(bad); fail() } catch (_: IllegalArgumentException) { }
            try { lease.resize(bad); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(4L, budget.reservedBytes)
            assertEquals(4L, lease.reservedBytes)
            lease.close()
        }
    }

    @Test fun cancelledProducerKeepsItsChargeUntilItsActualFinallyCompletes() = runTest {
        val budget = AttachmentMemoryBudget(8)
        val lease = budget.reserve(8)!!
        val finishProducer = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable) { finishProducer.await() } }
            finally { lease.close() }
        }
        try {
            job.cancel()
            assertFalse(job.isCompleted)
            assertEquals(8L, budget.reservedBytes)
            assertNull(budget.reserve(1))
        } finally {
            finishProducer.complete(Unit)
            job.join()
        }
        assertEquals(0L, budget.reservedBytes)
        budget.reserve(8)!!.close()
    }

    @Test fun competingPreparationsCannotBothReserveTheLastAvailableCapacity() {
        val budget = AttachmentMemoryBudget(8)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val leases = mutableListOf<AttachmentMemoryBudget.Reservation>()
        try {
            val results = List(2) {
                workers.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    budget.reserve(8)
                })
            }
            start.countDown()
            leases += results.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, leases.size)
            assertEquals(8L, budget.reservedBytes)
        } finally {
            leases.forEach { it.close() }
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(0L, budget.reservedBytes)
    }
}
