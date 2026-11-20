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
    )

    private lateinit var images: AttachmentImagesViewModel
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
        for ((scaleName, fontScale) in scales) for (shot in shots().filter { (only == null || it.name in only) && (it.landscapeAt == null || it.landscapeAt == scaleName) }) {
            // Keep one ActivityScenario per shot: setContent again in the same activity reuses the composition, and with
            // identical keys the screen's LaunchedEffect(roomId) never reruns, so the new model is never opened (#310).
            FontScaleTestActivity.requestedFontScale = fontScale
            val session = ReceivedFiles.session()
            val source = ReceivedSource().apply(shot.script)
            val network = FixedNetwork()
            val store = ViewModelStore()
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
                                ConversationScreen(room, 42, {}, images = images)
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
            } finally { compose.runOnIdle { room.cancel() }; store.clear() }
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
