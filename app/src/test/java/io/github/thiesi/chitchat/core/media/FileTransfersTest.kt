package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5a: Save, Share and Open state (TL plan and rulings of 2026-11-20). */
class FileTransfersTest {
    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val signedIn = SessionState(server, 3, SessionPhase.AUTHENTICATED, user = SessionUser(7, "Me", listOf("member"), 1, false))
    private val session = MutableStateFlow(signedIn)
    private val root: File = Files.createTempDirectory("ccm-transfers").toFile()
    private val copies = ShareCopies(root)
    private var space = Long.MAX_VALUE
    private val job = SupervisorJob()
    private val bytes = "the received file".toByteArray()

    @After fun cleanUp() { job.cancel(); root.deleteRecursively() }

    /** Writes the body unless told otherwise; [gate] holds the body until completed. */
    private inner class Source : FileSource {
        val calls = mutableListOf<Long>()
        var result: DownloadResult = DownloadResult.Saved
        var writesBody = true
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun stream(ref: AttachmentRef, expected: SessionState, open: () -> OutputStream, ensureCurrent: () -> Unit): DownloadResult {
            calls += ref.attachment.id
            if (!writesBody) return result
            ensureCurrent()
            open().use { out ->
                out.write(bytes, 0, 4)
                gate?.await()
                ensureCurrent()
                out.write(bytes, 4, bytes.size - 4)
            }
            return result
        }
    }

    private fun TestScope.transfers(source: Source) = FileTransfers(CoroutineScope(StandardTestDispatcher(testScheduler) + job),
        session, source, copies, { space }, clock = { testScheduler.currentTime }, io = StandardTestDispatcher(testScheduler))

    private fun ref(id: Long = 12, name: String = "notes.pdf", mime: String = "application/pdf") =
        AttachmentRef(MessageAttachment(id, name, mime, bytes.size.toLong(), "a".repeat(64), previewable = false), direct = false)

    private fun copiesOnDisk() = root.walk().filter { it.isFile }.toList()

    @Test fun oneTransferAtATime() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        assertNotNull(transfers.requestSave(ref()))
        assertTrue(transfers.busy)
        assertNull("A second Save waits", transfers.requestSave(ref(13)))
        transfers.prepare(ref(14), TransferPurpose.SHARE); advanceUntilIdle()
        assertTrue("No Share beside a pending Save", source.calls.isEmpty())
    }

    @Test fun aSaveWritesTheDocumentAndEndsSaved() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        val request = transfers.requestSave(ref())!!
        assertEquals("notes.pdf", request.suggestedTitle)
        assertEquals("application/pdf", request.mimeType)
        val document = ByteArrayOutputStream()
        var opened = 0
        transfers.saveChosen(request, { opened++; document }); advanceUntilIdle()
        assertEquals(TransferState.Saved(request.ref), transfers.state.value)
        assertEquals(1, opened)
        assertEquals(String(bytes), document.toString())
    }

    @Test fun aFailureBeforeTheBodyNeverOpensTheDocumentAndSaysNothingWasWritten() = runTest {
        val source = Source().apply { writesBody = false; result = DownloadResult.Gone }
        val transfers = transfers(source); advanceUntilIdle()
        val request = transfers.requestSave(ref())!!
        var opened = 0
        transfers.saveChosen(request, { opened++; ByteArrayOutputStream() }); advanceUntilIdle()
        assertEquals(0, opened)
        val stopped = transfers.state.value as TransferState.Stopped
        assertEquals(TransferStop.GONE, stopped.reason)
        assertFalse(stopped.wroteBody)
    }

    @Test fun anIntegrityMismatchOnSaveKeepsTheDocumentAndSaysDamaged() = runTest {
        val source = Source().apply { result = DownloadResult.IntegrityFailed }
        val transfers = transfers(source); advanceUntilIdle()
        val request = transfers.requestSave(ref())!!
        val document = ByteArrayOutputStream()
        transfers.saveChosen(request, { document }); advanceUntilIdle()
        val stopped = transfers.state.value as TransferState.Stopped
        assertEquals(TransferStop.DAMAGED, stopped.reason)
        assertTrue(stopped.wroteBody)
        assertEquals("The document keeps what was written", bytes.size, document.size())
    }

    @Test fun aPickerResultAfterASessionChangeOrWithoutItsRequestDoesNothing() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        val request = transfers.requestSave(ref())!!
        session.value = signedIn.copy(generation = 4); advanceUntilIdle()
        var opened = 0
        transfers.saveChosen(request, { opened++; ByteArrayOutputStream() }); advanceUntilIdle()
        assertEquals(0, opened)
        assertTrue(source.calls.isEmpty())
        // A process death: a new owner has no pending request, so an old one starts nothing either.
        val restarted = transfers(source); advanceUntilIdle()
        restarted.saveChosen(request, { opened++; ByteArrayOutputStream() }); advanceUntilIdle()
        assertEquals(0, opened)
        assertNull(restarted.state.value)
    }

    @Test fun aCancelledPickerStartsNothing() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        val request = transfers.requestSave(ref())!!
        transfers.saveChosen(request, null); advanceUntilIdle()
        assertTrue(source.calls.isEmpty())
        assertFalse(transfers.busy)
    }

    @Test fun nothingStartsDuringTheSessionWait() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        session.value = signedIn.copy(retryAtMillis = testScheduler.currentTime + 30_000)
        assertNull(transfers.requestSave(ref()))
        transfers.prepare(ref(), TransferPurpose.SHARE); advanceUntilIdle()
        assertTrue(source.calls.isEmpty())
        assertNull(transfers.state.value)
    }

    @Test fun aShareCopyIsVerifiedBeforeItsHandoffAndThenLivesByTheLifetimeRules() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        transfers.prepare(ref(), TransferPurpose.SHARE); advanceUntilIdle()
        val ready = transfers.state.value as TransferState.Ready
        assertEquals("application/pdf", ready.mimeType)
        assertEquals(String(bytes), ready.copy.file.readText())
        assertEquals("notes.pdf", ready.copy.file.name)
        transfers.handedOff(ready)
        assertNull(transfers.state.value)
        assertTrue("Kept for the receiver after handoff", ready.copy.file.exists())
    }

    @Test fun aMismatchingOrFailedCopyIsDeletedAndNeverOffered() = runTest {
        for (result in listOf(DownloadResult.IntegrityFailed, DownloadResult.NotAllowed, DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)))) {
            val source = Source().apply { this.result = result }
            val transfers = transfers(source); advanceUntilIdle()
            transfers.prepare(ref(), TransferPurpose.OPEN); advanceUntilIdle()
            assertTrue(transfers.state.value is TransferState.Stopped)
            assertTrue("$result leaves no copy", copiesOnDisk().isEmpty())
        }
    }

    @Test fun notEnoughSpaceStartsNothing() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        space = bytes.size.toLong()
        transfers.prepare(ref(), TransferPurpose.SHARE); advanceUntilIdle()
        assertEquals(TransferStop.NO_SPACE, (transfers.state.value as TransferState.Stopped).reason)
        assertTrue(source.calls.isEmpty())
        assertTrue(copiesOnDisk().isEmpty())
    }

    @Test fun anApkIsNeverCopiedForShareOrOpen() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        transfers.prepare(ref(name = "tool.apk"), TransferPurpose.SHARE)
        transfers.prepare(ref(name = "pic.apk", mime = "image/png"), TransferPurpose.OPEN); advanceUntilIdle()
        assertTrue(source.calls.isEmpty())
        // Save stays available.
        assertNotNull(transfers.requestSave(ref(name = "tool.apk")))
    }

    @Test fun noAppForOpenDeletesTheCopy() = runTest {
        val source = Source(); val transfers = transfers(source); advanceUntilIdle()
        transfers.prepare(ref(), TransferPurpose.OPEN); advanceUntilIdle()
        val ready = transfers.state.value as TransferState.Ready
        transfers.noApp(ready)
        assertEquals(TransferStop.NO_APP, (transfers.state.value as TransferState.Stopped).reason)
        assertFalse(ready.copy.file.exists())
    }

    @Test fun aGenuineBackgroundEndsAShareSilentlyAndDeletesItsCopy() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }
        val transfers = transfers(source); advanceUntilIdle()
        transfers.prepare(ref(), TransferPurpose.SHARE); advanceUntilIdle()
        assertTrue(transfers.state.value is TransferState.Running)
        transfers.backgrounded(); advanceUntilIdle()
        assertNull("A backgrounded Share or Open ends silently (PL)", transfers.state.value)
        assertTrue(copiesOnDisk().isEmpty())
    }

    @Test fun aGenuineBackgroundStopsASaveAndSaysWhatsLeft() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }
        val transfers = transfers(source); advanceUntilIdle()
        val request = transfers.requestSave(ref())!!
        val document = ByteArrayOutputStream()
        transfers.saveChosen(request, { document }); advanceUntilIdle()
        transfers.backgrounded(); advanceUntilIdle()
        val stopped = transfers.state.value as TransferState.Stopped
        assertEquals(TransferStop.BACKGROUND, stopped.reason)
        assertTrue("Bytes reached the document", stopped.wroteBody)
        assertEquals("Never deleted", 4, document.size())
    }

    @Test fun aSessionChangeMidShareCancelsItAndDeletesTheCopySilently() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }
        val transfers = transfers(source); advanceUntilIdle()
        transfers.prepare(ref(), TransferPurpose.SHARE); advanceUntilIdle()
        session.value = SessionState(server, 4, SessionPhase.ANONYMOUS); advanceUntilIdle()
        assertNull(transfers.state.value)
        assertTrue(copiesOnDisk().isEmpty())
        assertFalse(transfers.busy)
    }

    @Test fun aRevalidationOfTheSameAccountKeepsTheTransfer() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }
        val transfers = transfers(source); advanceUntilIdle()
        transfers.prepare(ref(), TransferPurpose.SHARE); advanceUntilIdle()
        session.value = signedIn.copy(busy = true, refreshRequired = true); advanceUntilIdle()
        source.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(transfers.state.value is TransferState.Ready)
    }

    @Test fun progressFollowsTheDestinationsWrites() = runTest {
        val source = Source().apply { gate = CompletableDeferred() }
        val transfers = transfers(source); advanceUntilIdle()
        transfers.prepare(ref(), TransferPurpose.SHARE); advanceUntilIdle()
        assertEquals(4L, (transfers.state.value as TransferState.Running).written)
        source.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(transfers.state.value is TransferState.Ready)
    }
}
