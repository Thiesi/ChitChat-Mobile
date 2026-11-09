package io.github.thiesi.chitchat.core.media

import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OriginalAttachmentOwnershipTest {
    @Before fun start() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun stop() { Dispatchers.resetMain() }

    @Test fun rejectedOriginalProducerCannotFreeTheAllocationMovedByItsCallback() = runTest {
        val budget = AttachmentMemoryBudget(8L * 1024 * 1024)
        val read = PickedAttachmentRead("private-file", null) { ByteArrayInputStream("GIF89a".toByteArray()) }
        val task = AttachmentPreparationTask(read, AttachmentPickSource.FILE, budget, 8192)
        var moved: OriginalAttachmentAllocation? = null
        var old: RetainedAttachmentPreparation? = null
        try {
            task.start(this) { _, result ->
                old = (result as AttachmentPreparationOutcome.Prepared).retained
                moved = old!!.takeOriginal(old!!.preparation as AttachmentPreparation.NeedsChoice)
                assertNotNull(moved)
                false // Original cleanup owns no lease after the exclusive move.
            }.join()
            old!!.close()
            assertNull(old!!.preparation)
            assertEquals(0L, old!!.reservedBytes)
            assertTrue("Moved bytes must remain charged after old cleanup", budget.reservedBytes > 0)
            assertEquals(budget.reservedBytes, moved!!.reservedBytes)
        } finally { moved?.close(); old?.close() }
        assertEquals(0L, budget.reservedBytes)
    }
}
