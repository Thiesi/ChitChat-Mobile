package io.github.thiesi.chitchat.core.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.conversation.MessageReply
import io.github.thiesi.chitchat.core.conversation.ReplyMessage
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.direct.DirectMessage
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.ImageKey
import io.github.thiesi.chitchat.core.media.ImageState
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.dm
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.file
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.png
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadScreen
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Received files F3 for J (PL, 2026-11-19), in the real room and DM screens at a real Configuration font scale, light and
 * dark at default and 200%, plus black for a loaded frame and the raised box: frame geometry, every image state, the GIF
 * badge, file cards (long name and APK), incoming and outgoing DM bubbles with captions, and a reply quoting a photo.
 * No network: a scripted source and the real decoder.
 */
class ReceivedFilesPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    private class Shot(
        val name: String,
        val rooms: List<RoomMessage> = emptyList(),
        val dms: List<DirectMessage>? = null,
        val metered: Boolean = false,
        val online: Boolean = true,
        val script: ReceivedSource.() -> Unit = {},
        val waitText: Int? = null,
        val ready: (Collection<ImageState>) -> Boolean = { true },
    )

    private val loaded: (Collection<ImageState>) -> Boolean = { states -> states.any { it is ImageState.Loaded } }
    private fun one(id: Long, body: String? = null, attachment: io.github.thiesi.chitchat.core.conversation.MessageAttachment) =
        listOf(roomMessage(id, body, attachment))

    private val shots = listOf(
        // Every image state (files-media.md "Image states").
        Shot("notloaded", one(3, attachment = photo(70)), metered = true, ready = { it.contains(ImageState.NotLoaded(ImageState.Reason.POLICY)) }),
        Shot("loading", one(3, attachment = photo(70)), script = { reply(70) { awaitCancellation() } }, waitText = R.string.image_loading),
        Shot("failed", one(3, attachment = photo(70)), script = { reply(70) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) } },
            ready = { it.contains(ImageState.Failed) }),
        Shot("nomemory", one(3, attachment = photo(70)), script = { reply(70) { DownloadResult.NoMemory } }, ready = { it.contains(ImageState.NoMemory) }),
        Shot("toolarge", one(3, attachment = photo(70, size = 48_000_000, preview = false)), ready = { it.contains(ImageState.TooLarge) }),
        Shot("gone", one(3, attachment = photo(70)), script = { reply(70) { DownloadResult.Gone } }, ready = { it.contains(ImageState.Gone) }),
        Shot("notallowed", one(3, attachment = photo(70)), script = { reply(70) { DownloadResult.NotAllowed } },
            ready = { it.contains(ImageState.NotAllowed) }),
        Shot("offline", one(3, attachment = photo(70)), online = false, ready = { it.contains(ImageState.NotLoaded(ImageState.Reason.OFFLINE)) }),
        // Geometry; "normal" is also the loaded state, with a caption below.
        Shot("normal", one(3, "Sunset over the bay", photo(70)), script = { bytes = { png(800, 600) } }, ready = loaded),
        Shot("panorama", one(3, attachment = photo(70, width = 6000, height = 1000)), script = { bytes = { png(1200, 200) } }, ready = loaded),
        // Short frames in interactive states: the button always fits whole (review, #310).
        Shot("panorama-show", one(3, attachment = photo(70, width = 6000, height = 1000)), metered = true,
            ready = { it.contains(ImageState.NotLoaded(ImageState.Reason.POLICY)) }),
        Shot("panorama-retry", one(3, attachment = photo(70, width = 6000, height = 1000)),
            script = { reply(70) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) } }, ready = { it.contains(ImageState.Failed) }),
        Shot("tall", one(3, attachment = photo(70, width = 1000, height = 6000)), script = { bytes = { png(200, 1200) } }, ready = loaded),
        Shot("small", one(3, attachment = photo(70, width = 160, height = 120)), script = { bytes = { png(160, 120) } }, ready = loaded),
        Shot("nodims", one(3, attachment = photo(70, width = null, height = null)), script = { bytes = { png(400, 300) } }, ready = loaded),
        Shot("gif", one(3, attachment = photo(70, width = 480, height = 360, mime = "image/gif")), script = { bytes = { png(480, 360) } }, ready = loaded),
        // File cards: no buttons until F5.
        Shot("card-short", one(3, attachment = file(71, "notes.pdf"))),
        Shot("card-long", one(3, attachment = file(71, "quarterly-report-for-the-steering-committee-final-version-approved.pdf"))),
        Shot("card-apk", one(3, attachment = file(71, "chitchat-release.apk", "application/vnd.android.package-archive", 18_900_000))),
        // A reply quoting a photo: no frame in the quote.
        Shot("reply", listOf(roomMessage(2, "Look at this", photo(70)), roomMessage(3, "Nice one",
            reply = MessageReply("room", 2, true, ReplyMessage(2, MessagePerson(8, "Bob"), "text", "Look at this", false)))),
            script = { bytes = { png(800, 600) } }, ready = loaded),
        // DM bubbles: incoming with a short caption, outgoing with one that wraps.
        Shot("dm-in-short", dms = listOf(dm(18, "Sunset", photo(70, direct = true))), script = { bytes = { png(800, 600) } }, ready = loaded),
        Shot("dm-out-long", dms = listOf(dm(18, "We walked down to the harbour after dinner and stayed until the last light went out over the water.",
            photo(70, direct = true), outgoing = true)), script = { bytes = { png(800, 600) } }, ready = loaded),
    )

    @Test fun presentationLight() = presentation("light", dark = false, black = false, shots, listOf("default" to 1f, "font200" to 2f))
    @Test fun presentationDark() = presentation("dark", dark = true, black = false, shots, listOf("default" to 1f, "font200" to 2f))
    @Test fun presentationBlack() = presentation("black", dark = true, black = true,
        shots.filter { it.name in setOf("normal", "notloaded") }, listOf("default" to 1f))

    private fun presentation(theme: String, dark: Boolean, black: Boolean, shots: List<Shot>, scales: List<Pair<String, Float>>) {
        for ((scaleName, fontScale) in scales) for (shot in shots) {
            // Keep one ActivityScenario per shot: setContent again in the same activity reuses the composition, and with
            // identical keys the screen's LaunchedEffect(roomId) never reruns, so the new model is never opened (#310).
            FontScaleTestActivity.requestedFontScale = fontScale
            val session = ReceivedFiles.session()
            val source = ReceivedSource().apply(shot.script)
            val store = ViewModelStore()
            val images = ViewModelProvider.create(store, viewModelFactory {
                initializer { AttachmentImagesViewModel(session, FixedNetwork(shot.online, shot.metered), source, 32L * 1024 * 1024) }
            })[AttachmentImagesViewModel::class.java]
            val rooms = ReceivedRooms(session).apply { messages = shot.rooms }
            val directs = ReceivedDirects(session).apply { rows = shot.dms.orEmpty() }
            val room = ConversationViewModel(rooms)
            val direct = DirectThreadViewModel(directs)
            try {
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        assertEquals("A real Configuration font scale", fontScale, activity.resources.configuration.fontScale)
                        activity.setContent { ChitChatTheme(dark = dark, pureBlack = black) {
                            Box(Modifier.fillMaxSize().testTag("files_host")) {
                                if (shot.dms != null) DirectThreadScreen(direct, 8, {}, images = images)
                                else ConversationScreen(room, 42, {}, images = images)
                            }
                        } }
                    }
                    val label = "files-$theme-$scaleName-${shot.name}"
                    // Evidence only, never a retry: on a timeout, say which state was missing.
                    fun await(what: String, condition: () -> Boolean) = try { compose.waitUntil(5_000, condition) }
                    catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                        val r = room.state.value; val d = direct.state.value
                        throw AssertionError("$label: $what not reached (room loaded=${r.loaded} busy=${r.busy} error=${r.error != null}; " +
                            "dm loaded=${d.loaded} busy=${d.busy}; images=${images.images.states.value.values.map { it.javaClass.simpleName }})", timeout)
                    }
                    await("conversation loaded") {
                        if (shot.dms != null) direct.state.value.loaded && !direct.state.value.busy
                        else room.state.value.loaded && !room.state.value.busy
                    }
                    await("image state") { shot.ready(images.images.states.value.values) }
                    shot.waitText?.let { id -> await("text") { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() } }
                    capture(label)
                    compose.runOnIdle { room.cancel(); direct.background() }
                }
            } finally { store.clear() }
        }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-files-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onNodeWithTag("files_host").captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
