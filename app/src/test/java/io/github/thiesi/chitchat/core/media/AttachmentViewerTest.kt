package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Received files F4a: the viewer session (TL and PL rulings of 2026-11-19), on virtual time with fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentViewerTest {
    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val signedIn = SessionState(server, 3, SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false))
    private val session = MutableStateFlow(signedIn)
    private val network = MutableStateFlow(NetworkState(online = true, metered = false))
    private val budget = AttachmentMemoryBudget(64L * 1024 * 1024)
    private val hash = "a".repeat(64)
    private val job = SupervisorJob()
    @After fun stop() = job.cancel()

    /** Records (id, variant); answers in order, else 1,000 leased bytes. */
    private inner class Source : AttachmentSource {
        val calls = mutableListOf<Pair<Long, AttachmentVariant>>()
        val replies = ArrayDeque<suspend () -> DownloadResult>()
        override suspend fun load(ref: AttachmentRef, variant: AttachmentVariant, expected: SessionState, ifNoneMatch: String?): DownloadResult {
            calls += ref.attachment.id to variant
            return replies.removeFirstOrNull()?.invoke() ?: bytes()
        }
    }
    private fun bytes(size: Int = 1_000) = DownloadResult.Bytes(ByteArray(size), null, budget.reserve(size.toLong())!!)

    /** Like the real decoders: lease the decoded size before keeping it. Transcript frames 32 KB, viewer images 640 KB. */
    private var viewerDecode: () -> DecodeResult? = { null }
    private val decoder = ImageDecoding { _, maxWidth, _, _, lease ->
        val size = if (maxWidth >= 1000) 640_000L else 32_000L
        (if (maxWidth >= 1000) viewerDecode() else null)
            ?: if (lease(size)) DecodeResult.Decoded(DecodedImage(null, maxWidth, maxWidth, size)) else DecodeResult.NoMemory
    }
    private var animatedLease = 0L
    private val stopped = mutableListOf<String>()
    private val animated = AnimatedDecoding { bytes, maxWidth, maxHeight, lease ->
        animatedLease = bytes.size + maxWidth.toLong() * maxHeight * 4 * 2
        if (!lease(animatedLease)) AnimationResult.NoMemory else AnimationResult.Decoded(object : DecodedAnimation {
            override val width = maxWidth; override val height = maxHeight; override val drawable: Any? = null
            override fun start() = Unit
            override fun stop() { stopped += "stop" }
        })
    }

    private inner class Rig(val scope: TestScope, val source: Source, animate: Boolean = true, decodeOn: kotlinx.coroutines.CoroutineDispatcher? = null) {
        val images = AttachmentImages(CoroutineScope(StandardTestDispatcher(scope.testScheduler) + job), session, network, source,
            decoder, 1_000_000, clock = { scope.testScheduler.currentTime }, decodeOn = StandardTestDispatcher(scope.testScheduler))
        val viewer = AttachmentViewer(CoroutineScope(StandardTestDispatcher(scope.testScheduler) + job), session, network, source, images,
            decoder, if (animate) animated else null, decodeOn ?: StandardTestDispatcher(scope.testScheduler))
        val panes = ImagePanes(images, viewer)
    }

    private fun ref(id: Long = 12, size: Long = 3_400_000, preview: Boolean = true, mime: String = "image/jpeg") = AttachmentRef(
        MessageAttachment(id, "photo.jpg", mime, size, hash, previewable = true, width = 4000, height = 3000,
            preview = if (preview) AttachmentPreview("/api/v1/attachments/download.php?id=$id&variant=preview", 1024, 768, "image/webp", 100_000, hash)
            else null), direct = false)

    private fun TestScope.shownOnScreen(rig: Rig, photo: AttachmentRef): ImagePanes.Pane {
        val pane = rig.panes.open(Any(), "entry-a")
        pane.visible(photo, 280, 210); advanceUntilIdle()
        assertTrue(rig.images.states.value[rig.images.key(photo)] is ImageState.Loaded)
        return pane
    }

    @Test fun itOpensOnTheHeldPreviewAndOffersTheOriginalBeyondTheRule() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val photo = ref()
        val pane = shownOnScreen(rig, photo)
        pane.openViewer(photo, 1080, 2000); advanceUntilIdle()
        assertEquals("No second download of the preview", listOf(12L to AttachmentVariant.PREVIEW), source.calls)
        assertEquals(FullImage.OFFER, rig.viewer.state.value!!.full)
        pane.loadFullImage(); advanceUntilIdle()
        assertEquals(12L to AttachmentVariant.ORIGINAL, source.calls.last())
        assertTrue(rig.viewer.state.value!!.original is ViewerImage.Still)
        assertEquals(FullImage.NONE, rig.viewer.state.value!!.full)
        assertEquals("The preview's 32 KB plus the original's 640 KB", 672_000L, budget.reservedBytes)
    }

    @Test fun aSmallOriginalLoadsByItselfOnlyUnmeteredOnlineAndNotWaiting() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val small = ref(size = 1_500_000)
        val pane = shownOnScreen(rig, small)
        pane.openViewer(small, 1080, 2000); advanceUntilIdle()
        assertTrue(rig.viewer.state.value!!.original is ViewerImage.Still)
        pane.closeViewer()
        network.value = NetworkState(online = true, metered = true)
        pane.openViewer(small, 1080, 2000); advanceUntilIdle()
        assertEquals("Metered: offered, not loaded", FullImage.OFFER, rig.viewer.state.value!!.full)
        pane.closeViewer()
        network.value = NetworkState(online = false, metered = true)
        pane.openViewer(small, 1080, 2000); advanceUntilIdle()
        assertEquals(FullImage.OFFLINE, rig.viewer.state.value!!.full)
    }

    @Test fun aFailureOrMismatchKeepsThePreviewAndNothingReloadsByItself() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val photo = ref()
        val pane = shownOnScreen(rig, photo)
        pane.openViewer(photo, 1080, 2000); advanceUntilIdle()
        source.replies += { DownloadResult.IntegrityFailed }
        pane.loadFullImage(); advanceUntilIdle()
        assertEquals(FullImage.FAILED, rig.viewer.state.value!!.full)
        assertNull("Mismatched bytes are never shown", rig.viewer.state.value!!.original)
        assertTrue("The preview stays", rig.images.states.value[rig.images.key(photo)] is ImageState.Loaded)
        source.replies += { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }
        pane.loadFullImage(); advanceUntilIdle()
        val calls = source.calls.size
        advanceTimeBy(60_000); advanceUntilIdle()
        assertEquals("Nothing reloads by itself", calls, source.calls.size)
        assertEquals(FullImage.FAILED, rig.viewer.state.value!!.full)
    }

    @Test fun beyondTheCapsNothingIsFetchedAndTryAgainIsInert() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val big = ref(size = 40_000_000)
        val pane = shownOnScreen(rig, big)
        pane.openViewer(big, 1080, 2000); pane.loadFullImage(); advanceUntilIdle()
        assertEquals(FullImage.TOO_LARGE, rig.viewer.state.value!!.full)
        assertTrue(source.calls.none { it.second == AttachmentVariant.ORIGINAL })
    }

    @Test fun withoutAPreviewTheViewerLoadsTheOriginalOnceAtScreenSize() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val bare = ref(size = 3_400_000, preview = false)
        val pane = rig.panes.open(Any(), "entry-a")
        pane.openViewer(bare, 1080, 2000); advanceUntilIdle()
        assertEquals("One original download, by the viewer only", listOf(12L to AttachmentVariant.ORIGINAL), source.calls)
        assertTrue(rig.viewer.state.value!!.original is ViewerImage.Still)
    }

    @Test fun closingDuringAnInFlightOriginalReleasesItsOneLease() = runTest {
        // TL (F4): one lease, released, nothing held afterwards.
        val decodes = TestCoroutineScheduler()
        val source = Source(); val rig = Rig(this, source, decodeOn = StandardTestDispatcher(decodes)); advanceUntilIdle()
        val photo = ref()
        val pane = shownOnScreen(rig, photo)
        pane.openViewer(photo, 1080, 2000); pane.loadFullImage(); advanceUntilIdle()
        assertEquals("Preview plus the original's raw bytes, mid-decode", 33_000L, budget.reservedBytes)
        pane.closeViewer(); advanceUntilIdle()
        decodes.advanceUntilIdle(); advanceUntilIdle()
        assertNull(rig.viewer.state.value)
        assertEquals("Only the transcript's preview stays", 32_000L, budget.reservedBytes)
    }

    @Test fun aStaleDecodeNeverWritesIntoTheNextViewer() = runTest {
        // Review (#312): a decode for a closed viewer finishes off Main; its outcome must not reach the successor.
        val decodes = TestCoroutineScheduler()
        val source = Source(); val rig = Rig(this, source, decodeOn = StandardTestDispatcher(decodes)); advanceUntilIdle()
        val first = ref(id = 41); val second = ref(id = 42)
        val pane = shownOnScreen(rig, first)
        pane.visible(second, 280, 210); advanceUntilIdle()
        decodes.advanceUntilIdle(); advanceUntilIdle()
        viewerDecode = { DecodeResult.NoMemory }
        pane.openViewer(first, 1080, 2000); pane.loadFullImage(); advanceUntilIdle()
        pane.closeViewer()
        pane.openViewer(second, 1080, 2000); advanceUntilIdle()
        decodes.advanceUntilIdle(); advanceUntilIdle()
        assertEquals(42L, rig.viewer.state.value!!.ref.attachment.id)
        assertEquals("The first photo's refusal never reaches the second", FullImage.OFFER, rig.viewer.state.value!!.full)
        assertEquals("Only the two transcript previews stay charged", 64_000L, budget.reservedBytes)
    }

    @Test fun closeReleasesAHeldOriginalAndASessionChangeClosesTheViewer() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val photo = ref()
        val pane = shownOnScreen(rig, photo)
        pane.openViewer(photo, 1080, 2000); pane.loadFullImage(); advanceUntilIdle()
        pane.closeViewer()
        assertEquals(32_000L, budget.reservedBytes)
        pane.openViewer(photo, 1080, 2000); pane.loadFullImage(); advanceUntilIdle()
        session.value = signedIn.copy(generation = 4); advanceUntilIdle()
        assertNull(rig.viewer.state.value)
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun thePreviewUnderAnOpenViewerIsNeverEvicted() = runTest {
        // TL (F4): while the viewer shows the held preview, it counts as visible.
        val source = Source()
        val rig = Rig(this, source); advanceUntilIdle()
        val small = AttachmentImages(CoroutineScope(StandardTestDispatcher(testScheduler) + job), session, network, source,
            decoder, 40_000, clock = { testScheduler.currentTime }, decodeOn = StandardTestDispatcher(testScheduler))
        val viewer = AttachmentViewer(CoroutineScope(StandardTestDispatcher(testScheduler) + job), session, network, source, small, decoder, animated,
            StandardTestDispatcher(testScheduler))
        val panes = ImagePanes(small, viewer); advanceUntilIdle()
        val viewed = ref(id = 21); val other = ref(id = 22)
        val pane = panes.open(Any(), "entry-a")
        pane.visible(viewed, 280, 210); advanceUntilIdle()
        pane.openViewer(viewed, 1080, 2000); advanceUntilIdle()
        pane.hidden(viewed)
        pane.visible(other, 280, 210); advanceUntilIdle()
        assertTrue("Pinned under the viewer", small.states.value[small.key(viewed)] is ImageState.Loaded)
        pane.closeViewer()
        pane.hidden(other); pane.visible(ref(id = 23), 280, 210); advanceUntilIdle()
        assertNull("Evictable once the viewer closed", small.states.value[small.key(viewed)])
    }

    @Test fun openingAGifFetchesItsOriginalAndKeepsTheSourceBytesLeased() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        network.value = NetworkState(online = true, metered = true)
        val gif = ref(mime = "image/gif")
        val pane = rig.panes.open(Any(), "entry-a")
        pane.visible(gif, 280, 210); pane.show(gif); advanceUntilIdle()
        pane.openViewer(gif, 1080, 2000); advanceUntilIdle()
        assertTrue("Opening is the explicit request, even metered", source.calls.contains(12L to AttachmentVariant.ORIGINAL))
        assertTrue(rig.viewer.state.value!!.original is ViewerImage.Animated)
        assertEquals("Source bytes plus two frame buffers", 32_000L + animatedLease, budget.reservedBytes)
        pane.closeViewer()
        assertEquals(listOf("stop"), stopped)
        assertEquals(32_000L, budget.reservedBytes)
    }

    @Test fun belowApi28AGifIsAStillUnderTheOrdinaryRule() = runTest {
        val source = Source(); val rig = Rig(this, source, animate = false); advanceUntilIdle()
        val gif = ref(mime = "image/gif")
        val pane = shownOnScreen(rig, gif)
        pane.openViewer(gif, 1080, 2000); advanceUntilIdle()
        assertTrue(rig.viewer.state.value!!.animationUnavailable)
        assertEquals(FullImage.OFFER, rig.viewer.state.value!!.full)
    }

    @Test fun aSessionWaitStartsNothingAndNothingStartsAfterIt() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val photo = ref()
        val pane = shownOnScreen(rig, photo)
        pane.openViewer(photo, 1080, 2000); advanceUntilIdle()
        source.replies += { DownloadResult.RateLimited(30) }
        pane.loadFullImage(); advanceUntilIdle()
        assertEquals(FullImage.WAITING, rig.viewer.state.value!!.full)
        val calls = source.calls.size
        advanceTimeBy(120_000); advanceUntilIdle()
        assertEquals("No burst after the wait", calls, source.calls.size)
    }

    @Test fun onlyTheCurrentPaneDrivesTheViewerAndADepartureClosesOnlyItsOwnEntry() = runTest {
        val source = Source(); val rig = Rig(this, source); advanceUntilIdle()
        val photo = ref()
        val old = shownOnScreen(rig, photo)
        old.openViewer(photo, 1080, 2000); advanceUntilIdle()
        val rotated = rig.panes.open(Any(), "entry-a") // A rotation keeps the viewer.
        old.closeViewer(); old.release()
        assertNotNull("A retired pane's late close is a no-op", rig.viewer.state.value)
        old.openViewer(ref(id = 30), 1080, 2000)
        assertEquals("Nor can it replace the viewer", photo.attachment.id, rig.viewer.state.value!!.ref.attachment.id)
        rig.panes.departed("entry-other")
        assertNotNull("Another entry's departure leaves it", rig.viewer.state.value)
        rig.panes.departed("entry-a")
        assertNull(rig.viewer.state.value)
        rotated.release()
    }
}
