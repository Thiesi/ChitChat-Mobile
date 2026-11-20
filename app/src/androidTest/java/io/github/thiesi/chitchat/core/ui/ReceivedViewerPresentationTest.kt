package io.github.thiesi.chitchat.core.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.FullImage
import io.github.thiesi.chitchat.core.media.ImageState
import io.github.thiesi.chitchat.core.media.NetworkState
import io.github.thiesi.chitchat.core.media.ViewerState
import io.github.thiesi.chitchat.core.media.platformAnimatedDecoding
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.png
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Received files F4b for J (PL, 2026-11-19): the viewer in the real room screen at a real Configuration font scale, light at
 * default and 200%, plus one dark capture (the viewer doesn't follow the theme). API 26–27 is the owner built without
 * animated decoding, which is exactly what the app does there. No network: a scripted source and the real decoder.
 */
class ReceivedViewerPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private val longCaption = (1..14).joinToString(" ") { "We walked down to the harbour after dinner and stayed until the light went out." }

    private class Shot(
        val name: String,
        val message: RoomMessage,
        val script: ReceivedSource.() -> Unit = { bytes = { png(800, 600) } },
        val animates: Boolean = true,
        /** Offline after the transcript's preview loaded, before opening. */
        val offlineAtOpen: Boolean = false,
        /** The transcript itself is the capture, not the viewer. */
        val frameOnly: Boolean = false,
        /** Landscape, at this one scale only (PL J2, J8). */
        val landscapeAt: String? = null,
        /** Portrait, at this one scale only (F6). */
        val portraitAt: String? = null,
        /** F6: Save and Share in the top bar (a FileTransfersUi is provided). */
        val transfers: Boolean = false,
        /** F6: the assistive-input answer the viewer sees (TalkBack on). */
        val assistive: Boolean = false,
        val ready: (ViewerState) -> Boolean = { true },
        val act: ReceivedViewerPresentationTest.() -> Unit = {},
    )

    private fun shots(): List<Shot> = listOf(
        Shot("viewer-preview", roomMessage(3, null, photo(70)), ready = { it.full == FullImage.OFFER }),
        Shot("viewer-loading", roomMessage(3, null, photo(70)), script = { serve(70); reply(70) { awaitCancellation() } },
            // Opens on the offer (3.4 MB is beyond the automatic rule); the tap then starts the load that never settles.
            ready = { it.full == FullImage.OFFER }, act = { compose.onNodeWithTag("viewer_load_full").performClick(); waitFor { it.full == FullImage.LOADING } }),
        Shot("viewer-original", roomMessage(3, null, photo(70)), act = { compose.onNodeWithTag("viewer_load_full").performClick(); waitFor { it.original != null } }),
        Shot("viewer-failed", roomMessage(3, null, photo(70)), script = { serve(70); reply(70) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) } },
            act = { compose.onNodeWithTag("viewer_load_full").performClick(); waitFor { it.full == FullImage.FAILED } }),
        Shot("viewer-nomemory", roomMessage(3, null, photo(70)), script = { serve(70); reply(70) { DownloadResult.NoMemory } },
            act = { compose.onNodeWithTag("viewer_load_full").performClick(); waitFor { it.full == FullImage.NO_MEMORY } }),
        Shot("viewer-toolarge-preview", roomMessage(3, null, photo(70, size = 48_000_000)), ready = { it.full == FullImage.TOO_LARGE }),
        Shot("viewer-toolarge", roomMessage(3, null, photo(70, size = 48_000_000, preview = false)), ready = { it.full == FullImage.TOO_LARGE }),
        Shot("viewer-gif", roomMessage(3, null, photo(70, width = 480, height = 360, mime = "image/gif")), ready = { it.original != null }),
        Shot("viewer-gif-offline", roomMessage(3, null, photo(70, width = 480, height = 360, mime = "image/gif")), offlineAtOpen = true,
            ready = { it.full == FullImage.OFFLINE }),
        Shot("viewer-caption", roomMessage(3, longCaption, photo(70)), ready = { it.full == FullImage.OFFER }),
        Shot("viewer-caption-open", roomMessage(3, longCaption, photo(70)), ready = { it.full == FullImage.OFFER },
            act = { compose.onNodeWithTag("viewer_caption_toggle").performClick() }),
        Shot("viewer-offline", roomMessage(3, null, photo(70)), offlineAtOpen = true, ready = { it.full == FullImage.OFFLINE }),
        // The server's 150 s wait reads "about 3 minutes" for the whole capture (PL, row 7's line).
        Shot("viewer-waiting", roomMessage(3, null, photo(70)), script = { serve(70); reply(70) { DownloadResult.RateLimited(150) } },
            ready = { it.full == FullImage.OFFER },
            act = { compose.onNodeWithTag("viewer_load_full").performClick(); waitFor { it.full == FullImage.WAITING } }),
        // PL J8: in landscape the pill sits beside the bottom block.
        Shot("viewer-preview-landscape", roomMessage(3, null, photo(70)), landscapeAt = "default", ready = { it.full == FullImage.OFFER }),
        Shot("viewer-caption-landscape", roomMessage(3, longCaption, photo(70)), landscapeAt = "default", ready = { it.full == FullImage.OFFER }),
        // PL J8: a failed original in landscape at 200%: the line above, Less and Try again on one row.
        Shot("viewer-failed-landscape", roomMessage(3, longCaption, photo(70)), landscapeAt = "font200",
            script = { serve(70); reply(70) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) } },
            act = {
                compose.onNodeWithTag("viewer_load_full").performClick(); waitFor { it.full == FullImage.FAILED }
                compose.onNodeWithTag("viewer_caption_toggle").performClick()
            }),
        Shot("viewer-caption-open-landscape", roomMessage(3, longCaption, photo(70)), landscapeAt = "font200", ready = { it.full == FullImage.OFFER },
            act = { compose.onNodeWithTag("viewer_caption_toggle").performClick() }),
        Shot("viewer-zoomed", roomMessage(3, null, photo(70)), ready = { it.full == FullImage.OFFER },
            act = { repeat(2) { compose.onNodeWithTag("viewer_zoom_in").performClick() } }),
        Shot("viewer-longsender", RoomMessage(3, 42, 8, "Maximiliane-Theodora von Hohenberg-Ashworth-Lindqvist", "text", null, false,
            "2026-10-20 12:00:00+00", photo(70)), ready = { it.full == FullImage.OFFER }),
        Shot("viewer-gif-api27", roomMessage(3, null, photo(70, width = 480, height = 360, mime = "image/gif")), animates = false,
            ready = { it.full == FullImage.OFFER && it.animationUnavailable }),
        Shot("frame-gif", roomMessage(3, null, photo(70, width = 480, height = 360, mime = "image/gif")), frameOnly = true),
        Shot("frame-gif-api27", roomMessage(3, null, photo(70, width = 480, height = 360, mime = "image/gif")), animates = false, frameOnly = true),
        // F6 (PL): landscape 200%, the long sender with Save and Share on the offer, without and with a collapsed caption, shown
        // and hidden; hidden by a real tap in the clear band.
        Shot("viewer-chrome-landscape", longSender(null), landscapeAt = "font200", transfers = true, ready = { it.full == FullImage.OFFER }),
        Shot("viewer-chrome-landscape-hidden", longSender(null), landscapeAt = "font200", transfers = true, ready = { it.full == FullImage.OFFER },
            act = { hideByTap() }),
        Shot("viewer-chrome-caption-landscape", longSender(longCaption), landscapeAt = "font200", transfers = true,
            ready = { it.full == FullImage.OFFER }),
        Shot("viewer-chrome-caption-landscape-hidden", longSender(longCaption), landscapeAt = "font200", transfers = true,
            ready = { it.full == FullImage.OFFER }, act = { hideByTap() }),
        // Portrait default: a photo with a caption, hidden; TalkBack on, after a tap (still shown); hidden while the original
        // loads, then it fails (shown with the line). Offline is decided at opening and at Load full (F4), both with the
        // chrome shown, so the line that can appear while hidden is a running load's.
        Shot("viewer-caption-hidden", roomMessage(3, longCaption, photo(70)), portraitAt = "default", ready = { it.full == FullImage.OFFER },
            act = { hideByTap() }),
        Shot("viewer-talkback-tap", roomMessage(3, longCaption, photo(70)), portraitAt = "default", assistive = true,
            ready = { it.full == FullImage.OFFER }, act = { tapClearBand(); pastDoubleTap() }),
        Shot("viewer-hidden-failed", roomMessage(3, null, photo(70)), portraitAt = "default",
            script = { serve(70); reply(70) { failGate.await(); DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) } },
            ready = { it.full == FullImage.OFFER },
            act = {
                compose.onNodeWithTag("viewer_load_full").performClick(); waitFor { it.full == FullImage.LOADING }
                hideByTap()
                failGate.complete(Unit)
                waitFor { it.full == FullImage.FAILED }
                compose.waitUntil(5_000) { present("viewer_line") && present("viewer_close") }
            }),
    )

    private fun longSender(caption: String?) = RoomMessage(3, 42, 8, "Maximiliane-Theodora von Hohenberg-Ashworth-Lindqvist", "text",
        caption, false, "2026-10-20 12:00:00+00", photo(70))

    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * F6: a real tap in the middle of the clear band between the top bar and the bottom row (block and pill). With no band
     * left, the measured edges are the failure: a tap there couldn't hide the chrome either.
     */
    private fun tapClearBand() {
        val top = compose.onNodeWithTag("viewer_top").fetchSemanticsNode().boundsInRoot.bottom
        val bottom = listOf("viewer_bottom", "viewer_zoom").filter(::present)
            .minOf { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        val image = compose.onNodeWithTag("viewer_image").fetchSemanticsNode().boundsInRoot
        if (bottom <= top) throw AssertionError("no clear band: top bar ends at $top, bottom row starts at $bottom")
        compose.onNodeWithTag("viewer_image").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(image.width / 2f, (top + bottom) / 2f - image.top))
        }
    }

    private fun hideByTap() {
        tapClearBand()
        compose.waitUntil(5_000) { !present("viewer_close") }
    }

    /** A fixed wait past the double-tap window, so a toggle that doesn't happen is really absent (never a retry). */
    private fun pastDoubleTap() {
        Thread.sleep(android.view.ViewConfiguration.getDoubleTapTimeout() * 2L)
        compose.waitForIdle()
    }

    private lateinit var images: AttachmentImagesViewModel
    private lateinit var network: FixedNetwork
    /** Holds viewer-hidden-failed's original until the chrome is hidden. */
    private val failGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    /** Evidence only, never a retry: on a timeout, say which viewer state was reached. */
    private fun waitFor(condition: (ViewerState) -> Boolean) = try {
        compose.waitUntil(5_000) { images.viewer.state.value?.let(condition) == true }
    } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
        val v = images.viewer.state.value
        throw AssertionError("viewer state not reached: full=${v?.full} original=${v?.original != null} " +
            "animationUnavailable=${v?.animationUnavailable}", timeout)
    }

    @Test fun presentationLight() = presentation(dark = false, scales = listOf("default" to 1f, "font200" to 2f), only = null)
    @Test fun presentationDark() = presentation(dark = true, scales = listOf("default" to 1f), only = setOf("viewer-preview"))

    private fun presentation(dark: Boolean, scales: List<Pair<String, Float>>, only: Set<String>?) {
        val theme = if (dark) "dark" else "light"
        for ((scaleName, fontScale) in scales) for (shot in shots().filter { (only == null || it.name in only) &&
            (it.landscapeAt ?: it.portraitAt).let { at -> at == null || at == scaleName } }) {
            // Keep one ActivityScenario per shot: setContent again in the same activity reuses the composition, and with
            // identical keys the screen's LaunchedEffect(roomId) never reruns, so the new model is never opened (#310).
            FontScaleTestActivity.requestedFontScale = fontScale
            val session = ReceivedFiles.session()
            val source = ReceivedSource().apply(shot.script)
            network = FixedNetwork()
            val store = ViewModelStore()
            val job = kotlinx.coroutines.SupervisorJob()
            val copies = if (shot.transfers) java.nio.file.Files.createTempDirectory("ccm-viewer-chrome").toFile() else null
            val ui = copies?.let {
                FileTransfersUi(io.github.thiesi.chitchat.core.media.FileTransfers(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + job),
                    session, { _, _, _, _ -> DownloadResult.Gone }, io.github.thiesi.chitchat.core.media.ShareCopies(it), { Long.MAX_VALUE }),
                    kotlinx.coroutines.flow.flowOf(true)) { }
            }
            images = ViewModelProvider.create(store, viewModelFactory {
                initializer { AttachmentImagesViewModel(session, network, source, 32L * 1024 * 1024, if (shot.animates) platformAnimatedDecoding() else null) }
            })[AttachmentImagesViewModel::class.java]
            val rooms = ReceivedRooms(session).apply { messages = listOf(shot.message) }
            val room = ConversationViewModel(rooms)
            try {
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    if (shot.landscapeAt != null) {
                        scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                        compose.waitUntil(5_000) {
                            var orientation = 0
                            scenario.onActivity { orientation = it.resources.configuration.orientation }
                            orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        }
                    }
                    scenario.onActivity { activity ->
                        activity.setContent { ChitChatTheme(dark = dark) {
                            Surface(Modifier.fillMaxSize().testTag("files_host"), color = MaterialTheme.colorScheme.background) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalFileTransfers provides ui,
                                    LocalAssistiveInput provides shot.assistive) {
                                    ConversationScreen(room, 42, {}, images = images)
                                }
                            }
                        } }
                    }
                    compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
                    val label = "files-$theme-$scaleName-${shot.name}"
                    val tooLargeBare = shot.message.attachment?.preview == null
                    if (!tooLargeBare) compose.waitUntil(5_000) { images.images.states.value.values.any { it is ImageState.Loaded } }
                    if (shot.frameOnly) { capture(label, "files_host"); return@use }
                    if (shot.offlineAtOpen) network.state.value = NetworkState(online = false, metered = true)
                    // A tall row (a long caption) starts scrolled to its bottom: bring the frame into view before the tap.
                    compose.onNodeWithTag("conversation_timeline").performScrollToNode(androidx.compose.ui.test.hasTestTag("attachment_frame:70"))
                    compose.onNodeWithTag("attachment_frame:70").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer").fetchSemanticsNodes().isNotEmpty() }
                    waitFor(shot.ready)
                    shot.act(this)
                    capture(label, "viewer")
                }
            } finally { compose.runOnIdle { room.cancel() }; store.clear(); job.cancel(); copies?.deleteRecursively() }
        }
    }

    private fun capture(name: String, node: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-files-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag(node).captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
