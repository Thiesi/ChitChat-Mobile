package io.github.thiesi.chitchat.core.media

import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Received files F2: the image loading policy, the memory-only cache and the TL's 429 rule, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentImagesTest {
    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val signedIn = SessionState(server, 3, SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false))
    private val session = MutableStateFlow(signedIn)
    private val network = MutableStateFlow(NetworkState(online = true, metered = false))
    private val budget = AttachmentMemoryBudget(64L * 1024 * 1024)
    private val hash = "a".repeat(64)

    /** Answers in order; each call is recorded as (attachment id, variant, If-None-Match). */
    private inner class Source : AttachmentSource {
        val calls = mutableListOf<Triple<Long, AttachmentVariant, String?>>()
        val replies = ArrayDeque<() -> DownloadResult>()
        override suspend fun load(ref: AttachmentRef, variant: AttachmentVariant, expected: SessionState, ifNoneMatch: String?): DownloadResult {
            calls += Triple(ref.attachment.id, variant, ifNoneMatch)
            return replies.removeFirstOrNull()?.invoke() ?: bytes()
        }
    }
    private fun bytes(size: Int = 1_000, etag: String? = "\"e\"") = DownloadResult.Bytes(ByteArray(size), etag, budget.reserve(size.toLong())!!)

    private var decoded: () -> DecodeResult = { DecodeResult.Decoded(DecodedImage(null, 100, 80, 32_000)) }
    /** Every decode's orient flag, in order. */
    private val oriented = mutableListOf<Boolean>()
    /** Like the real decoder: lease the decoded size before keeping it, and report NoMemory when refused. */
    private val decoder = ImageDecoding { _, _, _, orient, lease ->
        oriented += orient
        when (val result = decoded()) {
            is DecodeResult.Decoded -> if (lease(result.image.byteCount)) result else DecodeResult.NoMemory
            else -> result
        }
    }

    // advanceUntilIdle() skips backgroundScope work, so the loader runs in its own scope on the test scheduler,
    // cancelled after each test (its collectors never finish on their own).
    private val loaderJob = kotlinx.coroutines.SupervisorJob()
    @org.junit.After fun stop() = loaderJob.cancel()
    private fun TestScope.images(source: Source, capacity: Long = 1_000_000,
        decodeOn: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(testScheduler)) = AttachmentImages(
        kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler) + loaderJob), session, network, source,
        decoder, capacity, clock = { testScheduler.currentTime }, decodeOn = decodeOn)

    private fun ref(id: Long = 12, size: Long = 50_000, preview: Long? = 20_000, width: Int? = 400, height: Int? = 300) = AttachmentRef(
        MessageAttachment(id, "photo.jpg", "image/jpeg", size, hash, previewable = true, width = width, height = height,
            preview = preview?.let { AttachmentPreview("/api/v1/attachments/download.php?id=$id&variant=preview", 400, 300, "image/webp", it, hash) }),
        direct = false)

    private fun AttachmentImages.state(ref: AttachmentRef) = states.value[key(ref)]

    @Test fun aSmallPreviewOnScreenLoadsAutomaticallyAndKeepsItsLeaseAsTheDecodedSize() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        val photo = ref()
        images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals(listOf(Triple(12L, AttachmentVariant.PREVIEW, null as String?)), source.calls)
        assertTrue(images.state(photo) is ImageState.Loaded)
        assertEquals("The lease follows the decoded pixels, not the raw bytes", 32_000L, budget.reservedBytes)
    }

    @Test fun meteredOrLargeWaitsForShow() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        network.value = NetworkState(online = true, metered = true)
        val photo = ref()
        images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals(ImageState.NotLoaded(ImageState.Reason.POLICY), images.state(photo))
        val big = ref(id = 13, preview = 3L * 1024 * 1024)
        network.value = NetworkState(online = true, metered = false)
        images.visible(big, 280, 210); advanceUntilIdle()
        assertEquals("Over 2 MiB", ImageState.NotLoaded(ImageState.Reason.POLICY), images.state(big))
        assertTrue("Nothing fetched", source.calls.isEmpty())
        images.show(big); advanceUntilIdle()
        assertTrue(images.state(big) is ImageState.Loaded)
        assertEquals(1, source.calls.size)
    }

    @Test fun offlineDisablesShowAndBackOnlineEvaluatesOnScreenFramesOnce() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        network.value = NetworkState(online = false, metered = true)
        val photo = ref()
        images.visible(photo, 280, 210); images.show(photo); advanceUntilIdle()
        assertEquals(ImageState.NotLoaded(ImageState.Reason.OFFLINE), images.state(photo))
        assertTrue(source.calls.isEmpty())
        network.value = NetworkState(online = true, metered = false); advanceUntilIdle()
        assertTrue(images.state(photo) is ImageState.Loaded)
        assertEquals(1, source.calls.size)
    }

    @Test fun aFailureIsNeverRetriedAutomaticallyOnlyByTryAgain() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        source.replies += { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }
        val photo = ref()
        images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals(ImageState.Failed, images.state(photo))
        images.hidden(photo); images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals("No automatic retry", 1, source.calls.size)
        images.retry(photo); advanceUntilIdle()
        assertTrue(images.state(photo) is ImageState.Loaded)
        assertEquals(2, source.calls.size)
    }

    @Test fun aMismatchOrUnreadableBytesShowTheFailureAndFreeTheLease() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        source.replies += { DownloadResult.IntegrityFailed }
        images.visible(ref(), 280, 210); advanceUntilIdle()
        assertEquals(ImageState.Failed, images.state(ref()))
        decoded = { DecodeResult.Unreadable }
        images.visible(ref(id = 14), 280, 210); advanceUntilIdle()
        assertEquals(ImageState.Failed, images.state(ref(id = 14)))
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun aMissingPreviewFallsBackToTheOriginalUnderTheSameRule() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        source.replies += { DownloadResult.PreviewUnavailable }
        val photo = ref(size = 50_000)
        images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals(listOf(AttachmentVariant.PREVIEW, AttachmentVariant.ORIGINAL), source.calls.map { it.second })
        assertTrue(images.state(photo) is ImageState.Loaded)
        // A large original without a usable preview waits for Show.
        source.replies += { DownloadResult.PreviewUnavailable }
        val large = ref(id = 15, size = 5L * 1024 * 1024)
        images.visible(large, 280, 210); advanceUntilIdle()
        assertEquals(ImageState.NotLoaded(ImageState.Reason.POLICY), images.state(large))
    }

    @Test fun beyondTheCapsNothingIsFetched() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        val huge = ref(size = 48L * 1024 * 1024, preview = null)
        images.visible(huge, 280, 210); images.show(huge); advanceUntilIdle()
        assertEquals(ImageState.TooLarge, images.state(huge))
        val wide = ref(id = 16, preview = null, width = 6000, height = 4000)
        images.visible(wide, 280, 210); advanceUntilIdle()
        assertEquals(ImageState.TooLarge, images.state(wide))
        assertTrue(source.calls.isEmpty())
    }

    @Test fun duringASessionWaitNothingStartsAndAfterItOnScreenFramesLoadOnceEach() = runTest {
        // TL (#306): automatic loads never trigger or prolong the wait, and there's no catch-up burst afterwards.
        val source = Source(); val images = images(source); advanceUntilIdle()
        session.value = signedIn.copy(retryAtMillis = testScheduler.currentTime + 30_000)
        val (a, b, c) = listOf(ref(id = 21), ref(id = 22), ref(id = 23))
        images.visible(a, 280, 210); images.visible(b, 280, 210); images.visible(c, 280, 210); advanceTimeBy(1_000)
        assertTrue("Nothing starts during the wait", source.calls.isEmpty())
        assertEquals(ImageState.NotLoaded(ImageState.Reason.WAITING), images.state(a))
        images.hidden(c) // Scrolled away before the wait ends.
        advanceUntilIdle()
        assertEquals("Only the frames still on screen, once each", listOf(21L, 22L), source.calls.map { it.first }.sorted())
        assertEquals(ImageState.NotLoaded(ImageState.Reason.WAITING), images.state(c))
        advanceTimeBy(60_000); advanceUntilIdle()
        assertEquals("No second pass", 2, source.calls.size)
    }

    @Test fun aRateLimitedLoadWaitsAndLoadsOnceAfterTheWaitIfStillOnScreen() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        source.replies += {
            session.value = signedIn.copy(retryAtMillis = testScheduler.currentTime + 10_000)
            DownloadResult.RateLimited(10)
        }
        val photo = ref()
        images.visible(photo, 280, 210); advanceTimeBy(1_000)
        assertEquals(ImageState.NotLoaded(ImageState.Reason.WAITING), images.state(photo))
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
        assertTrue(images.state(photo) is ImageState.Loaded)
    }

    @Test fun aHeldPreviewIsRevalidatedWhenShownAgain() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        val photo = ref()
        images.visible(photo, 280, 210); advanceUntilIdle()
        images.hidden(photo)
        source.replies += { DownloadResult.NotModified }
        images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals("\"e\"", source.calls.last().third)
        assertTrue("A 304 keeps it", images.state(photo) is ImageState.Loaded)
        images.hidden(photo)
        images.enteredConversation() // A fresh visit may revalidate again.
        source.replies += { DownloadResult.Gone }
        images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals("Deleted meanwhile: the held image goes", ImageState.Gone, images.state(photo))
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun scrollingBackAndForthRevalidatesAtMostOncePerVisit() = runTest {
        // TL (#308): re-shows within one visit never become a stream of conditional GETs.
        val source = Source(); val images = images(source); advanceUntilIdle()
        val photo = ref()
        images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals(1, source.calls.size)
        repeat(3) {
            images.hidden(photo)
            source.replies += { DownloadResult.NotModified }
            images.visible(photo, 280, 210); advanceUntilIdle()
        }
        assertEquals("One conditional GET at most in this visit", 1, source.calls.count { it.third != null })
        images.enteredConversation()
        images.hidden(photo); images.visible(photo, 280, 210); advanceUntilIdle()
        assertEquals("A fresh visit may revalidate once more", 2, source.calls.count { it.third != null })
        assertTrue(images.state(photo) is ImageState.Loaded)
    }

    @Test fun imagesNotOnScreenAreEvictedBeyondCapacityAndFreeTheirLeases() = runTest {
        val source = Source(); val images = images(source, capacity = 50_000); advanceUntilIdle()
        val first = ref(id = 31); val second = ref(id = 32)
        images.visible(first, 280, 210); advanceUntilIdle()
        images.hidden(first)
        images.visible(second, 280, 210); advanceUntilIdle()
        assertNull("The least recently used, not on screen, is gone", images.state(first))
        assertTrue(images.state(second) is ImageState.Loaded)
        assertEquals(32_000L, budget.reservedBytes)
        // Back on screen, an evicted image reloads (review, #308).
        images.hidden(second)
        images.visible(first, 280, 210); advanceUntilIdle()
        assertTrue(images.state(first) is ImageState.Loaded)
        assertEquals(3, source.calls.size)
    }

    @Test fun anImageEvictedDuringASessionWaitReloadsOnlyAfterItAndOnlyIfStillOnScreen() = runTest {
        // TL (#308): a reload after eviction is an automatic load like any other (the wait rule applies).
        val source = Source(); val images = images(source, capacity = 50_000); advanceUntilIdle()
        val evicted = ref(id = 51); val newer = ref(id = 52)
        images.visible(evicted, 280, 210); advanceUntilIdle()
        images.hidden(evicted)
        // The newer image arrives just as the session starts waiting, and pushes the older one out.
        source.replies += { session.value = signedIn.copy(retryAtMillis = testScheduler.currentTime + 20_000); bytes() }
        images.visible(newer, 280, 210); advanceTimeBy(1_000)
        assertNull("Evicted", images.state(evicted))
        images.hidden(newer)
        images.visible(evicted, 280, 210); advanceTimeBy(1_000)
        assertEquals("Nothing during the wait", ImageState.NotLoaded(ImageState.Reason.WAITING), images.state(evicted))
        assertEquals(2, source.calls.size)
        advanceUntilIdle()
        assertTrue("Still on screen when the wait ended: one reload", images.state(evicted) is ImageState.Loaded)
        assertEquals(3, source.calls.size)
    }

    @Test fun anImageEvictedDuringAWaitAndScrolledAwayDoesntReload() = runTest {
        val source = Source(); val images = images(source, capacity = 50_000); advanceUntilIdle()
        val evicted = ref(id = 53); val newer = ref(id = 54)
        images.visible(evicted, 280, 210); advanceUntilIdle()
        images.hidden(evicted)
        source.replies += { session.value = signedIn.copy(retryAtMillis = testScheduler.currentTime + 20_000); bytes() }
        images.visible(newer, 280, 210); advanceTimeBy(1_000)
        images.visible(evicted, 280, 210); advanceTimeBy(1_000)
        images.hidden(evicted)
        advanceUntilIdle()
        assertEquals("Off screen when the wait ended: no catch-up", 2, source.calls.size)
    }

    @Test fun aShownImageEvictedReloadsEvenWhenMetered() = runTest {
        val source = Source(); val images = images(source, capacity = 50_000); advanceUntilIdle()
        network.value = NetworkState(online = true, metered = true)
        val shown = ref(id = 41); val other = ref(id = 42)
        images.visible(shown, 280, 210); images.show(shown); advanceUntilIdle()
        images.hidden(shown)
        images.visible(other, 280, 210); images.show(other); advanceUntilIdle()
        assertNull("Evicted", images.state(shown))
        images.hidden(other)
        images.visible(shown, 280, 210); advanceUntilIdle()
        assertTrue("Once shown, it stays shown for the session", images.state(shown) is ImageState.Loaded)
    }

    @Test fun withoutRoomForTheDecodedImageItCantShowRightNow() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        decoded = { DecodeResult.Decoded(DecodedImage(null, 4000, 4000, 200L * 1024 * 1024)) }
        images.visible(ref(), 280, 210); advanceUntilIdle()
        assertEquals(ImageState.NoMemory, images.state(ref()))
        assertEquals(0L, budget.reservedBytes)
    }

    @Test fun aRefusedLeaseEvictsOnMainThenDecodesOnceMore() = runTest {
        // The decoder leases off the main thread; eviction is main-confined, so a refusal comes back and is retried once.
        val source = Source(); val images = images(source, capacity = 100L * 1024 * 1024); advanceUntilIdle()
        decoded = { DecodeResult.Decoded(DecodedImage(null, 3000, 3000, 40L * 1024 * 1024)) }
        val old = ref(id = 71); val next = ref(id = 72)
        images.visible(old, 280, 210); advanceUntilIdle()
        images.hidden(old)
        oriented.clear()
        images.visible(next, 280, 210); advanceUntilIdle()
        assertEquals("Refused, evicted, decoded again", 2, oriented.size)
        assertTrue(images.state(next) is ImageState.Loaded)
        assertNull("The off-screen image made room", images.state(old))
        assertEquals(40L * 1024 * 1024, budget.reservedBytes)
        // With nothing off screen to drop, there's no second decode: it can't show right now.
        val third = ref(id = 73)
        oriented.clear()
        images.visible(third, 280, 210); advanceUntilIdle()
        assertEquals(1, oriented.size)
        assertEquals(ImageState.NoMemory, images.state(third))
        assertTrue("The image on screen is kept", images.state(next) is ImageState.Loaded)
    }

    @Test fun showAndTryAgainDuringADecodeStartNoSecondLoad() = runTest {
        // Review (#308): the job stays set while the decode runs, so no duplicate load and no leaked lease.
        val decodes = kotlinx.coroutines.test.TestCoroutineScheduler()
        val source = Source(); val images = images(source, decodeOn = StandardTestDispatcher(decodes)); advanceUntilIdle()
        network.value = NetworkState(online = true, metered = true)
        images.visible(ref(), 280, 210); images.show(ref()); advanceUntilIdle()
        assertEquals("Fetched; the decode is suspended", 1, source.calls.size)
        images.show(ref()); images.retry(ref()); advanceUntilIdle()
        assertEquals("No second load while decoding", 1, source.calls.size)
        decodes.advanceUntilIdle(); advanceUntilIdle()
        assertTrue(images.state(ref()) is ImageState.Loaded)
        assertEquals("Exactly one lease, at the decoded size", 32_000L, budget.reservedBytes)
        // A session change during a decode cancels it and frees its lease.
        images.visible(ref(id = 81), 280, 210); images.show(ref(id = 81)); advanceUntilIdle()
        session.value = signedIn.copy(generation = 4); advanceUntilIdle()
        decodes.advanceUntilIdle(); advanceUntilIdle()
        assertEquals("Nothing held after the session changed", 0L, budget.reservedBytes)
    }

    @Test fun onlyOriginalsAreOrientedNeverPreviews() {
        runTest {
            // TL (#308): the server's previews are already in display orientation.
            val source = Source(); val images = images(source); advanceUntilIdle()
            images.visible(ref(), 280, 210); advanceUntilIdle()
            images.visible(ref(id = 61, preview = null), 280, 210); advanceUntilIdle()
            assertEquals(listOf(false, true), oriented)
        }
    }

    @Test fun theSampleSizeNeverDecodesSmallerThanTheFrame() {
        assertEquals(1, AndroidImageDecoding.sampleSize(400, 300, 280, 210))
        assertEquals(4, AndroidImageDecoding.sampleSize(4000, 3000, 900, 700))
        assertEquals("Upscaling never happens in memory", 1, AndroidImageDecoding.sampleSize(100, 80, 280, 210))
    }

    @Test fun anotherAccountOrGenerationDropsEverything() = runTest {
        val source = Source(); val images = images(source); advanceUntilIdle()
        images.visible(ref(), 280, 210); advanceUntilIdle()
        session.value = signedIn.copy(generation = 4); advanceUntilIdle()
        assertTrue(images.states.value.isEmpty())
        assertEquals("Memory only, released", 0L, budget.reservedBytes)
        session.value = signedIn.copy(generation = 4, phase = SessionPhase.SIGNED_OUT, user = null); advanceUntilIdle()
        assertNull("Signed out: nothing to key", images.key(ref()))
    }
}
