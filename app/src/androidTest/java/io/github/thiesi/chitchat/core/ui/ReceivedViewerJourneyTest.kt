package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.dm
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadScreen
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.feature.realtime.NativeTypingHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Received files F4b: the full-screen viewer in the real room and DM screens (TL and PL rulings of 2026-11-19). */
class ReceivedViewerJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = ReceivedFiles.session()
    private val source = ReceivedSource()
    private val store = ViewModelStore()
    private val images: AttachmentImagesViewModel = ViewModelProvider.create(store, viewModelFactory {
        initializer { AttachmentImagesViewModel(session, FixedNetwork(), source, 32L * 1024 * 1024) }
    })[AttachmentImagesViewModel::class.java]
    private val rooms = ReceivedRooms(session)
    private val typing = NativeTypingHarness(session)
    private val room = ConversationViewModel(rooms, typing = typing.controller)
    private val directs = ReceivedDirects(session)
    private val direct = DirectThreadViewModel(directs)

    @After fun tearDown() { compose.runOnIdle { room.cancel(); direct.background() }; typing.close(); store.clear() }

    private fun text(id: Int) = context.getString(id)
    private fun clickLabel(action: androidx.compose.ui.semantics.SemanticsPropertyKey<androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>>,
        label: String) = SemanticsMatcher("$label") { it.config.getOrNull(action)?.label == label }
    private val noClick = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
    private fun viewerShown() = compose.onAllNodesWithTag("viewer").fetchSemanticsNodes().isNotEmpty()

    private fun roomContent() {
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(room, 42, {}, images = images) } } }
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
    }
    private fun loaded(id: Long) = compose.waitUntil(5_000) {
        images.images.states.value.entries.any { it.key.attachmentId == id && it.value is io.github.thiesi.chitchat.core.media.ImageState.Loaded }
    }

    @Test fun aTapOpensTheViewerAndALongPressKeepsTheMessageActions() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent(); loaded(70)
        compose.onNodeWithTag("attachment_frame:70")
            .assert(clickLabel(SemanticsActions.OnClick, text(R.string.image_open)))
            .assert(clickLabel(SemanticsActions.OnLongClick, text(R.string.message_actions)))
            .performClick()
        compose.waitUntil(5_000) { viewerShown() }
        compose.onNodeWithTag("viewer_close").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5_000) { !viewerShown() }
        compose.onNodeWithTag("attachment_frame:70").performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Long-press opens actions, not the viewer", !viewerShown())
    }

    @Test fun goneAndNotAllowedFramesOfferNoOpenPhoto() {
        source.reply(70) { DownloadResult.Gone }
        source.reply(71) { DownloadResult.NotAllowed }
        rooms.messages = listOf(roomMessage(3, null, photo(70)), roomMessage(4, null, photo(71)))
        roomContent()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.image_not_allowed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_frame:70").assert(noClick)
        compose.onNodeWithTag("attachment_frame:71").assert(noClick)
    }

    @Test fun tooLargeOpensTheViewerWithItsLineAndNoZoomGroup() {
        rooms.messages = listOf(roomMessage(3, null, photo(70, size = 40_000_000, preview = false)))
        roomContent()
        compose.onNodeWithTag("attachment_frame:70").assert(clickLabel(SemanticsActions.OnClick, text(R.string.image_open))).performClick()
        compose.waitUntil(5_000) { viewerShown() }
        compose.onNodeWithTag("viewer_ground_line").assertTextEquals(text(R.string.viewer_too_large))
        assertTrue(compose.onAllNodesWithTag("viewer_zoom").fetchSemanticsNodes().isEmpty())
        assertTrue("Nothing fetched", source.calls.isEmpty())
    }

    @Test fun theViewerOffersTheFullImageAndShowsNoSaveOrShare() {
        rooms.messages = listOf(roomMessage(3, "Harbour at dusk", photo(70)))
        roomContent(); loaded(70)
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerShown() }
        compose.onNodeWithTag("viewer_load_full").assertTextEquals(context.getString(R.string.viewer_load_full, "3.4 MB"))
        assertTrue("No Save or Share until F5", compose.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("Share").fetchSemanticsNodes().isEmpty())
        val frame = compose.onNodeWithTag("attachment_frame:70").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        val image = compose.onNodeWithTag("viewer_image").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals("The viewer image has the frame's description", frame, image)
        compose.onNodeWithTag("viewer_load_full").performClick()
        compose.waitUntil(5_000) { source.calls.size == 2 && compose.onAllNodesWithTag("viewer_load_full").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun aViewerShowingTheOriginalDescribesThePhotoNotTheFramesFailure() {
        // TL (#313): opened from a failed frame, the loaded original is announced as the photo, not "Couldn't load".
        source.reply(70) { DownloadResult.Failed(io.github.thiesi.chitchat.core.network.ApiFailure(io.github.thiesi.chitchat.core.network.FailureKind.NETWORK)) }
        source.reply(70) { DownloadResult.Failed(io.github.thiesi.chitchat.core.network.ApiFailure(io.github.thiesi.chitchat.core.network.FailureKind.NETWORK)) }
        rooms.messages = listOf(roomMessage(3, null, photo(70, size = 1_500_000)))
        roomContent()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment_retry:70").fetchSemanticsNodes().isNotEmpty() }
        // Try again sits in the frame's centre; tap the frame itself, near its corner.
        compose.onNodeWithTag("attachment_frame:70").performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 12f)) }
        compose.waitUntil(5_000) { images.viewer.state.value?.original != null }
        compose.waitForIdle()
        val label = compose.onNodeWithTag("viewer_image").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf(context.getString(R.string.image_description, "Bob", label!!.single().removePrefix("Photo from Bob, "))), label)
        assertTrue(label.single(), !label.single().contains(". "))
    }

    @Test fun theZoomButtonsAreLabelledAndFollowTheScale() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent(); loaded(70)
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerShown() }
        val zoomIn = compose.onNodeWithTag("viewer_zoom_in").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        compose.onNodeWithTag("viewer_zoom_out").assertIsNotEnabled()
        compose.onNodeWithTag("viewer_zoom_reset").assertIsNotEnabled()
        listOf("viewer_zoom_in" to R.string.viewer_zoom_in, "viewer_zoom_out" to R.string.viewer_zoom_out, "viewer_zoom_reset" to R.string.viewer_zoom_reset)
            .forEach { (tag, label) -> assertEquals(listOf(text(label)),
                compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)) }
        zoomIn.performClick()
        compose.onNodeWithTag("viewer_zoom_out").assertIsEnabled()
        compose.onNodeWithTag("viewer_zoom_reset").assertIsEnabled().performClick()
        compose.onNodeWithTag("viewer_zoom_out").assertIsNotEnabled()
    }

    @Test fun backClosesTheViewer() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent(); loaded(70)
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerShown() }
        Espresso.pressBack()
        compose.waitUntil(5_000) { !viewerShown() }
        assertEquals(null, images.viewer.state.value)
    }

    @Test fun noReadProofOrTypingWhileOpenAndNoneCausedByClosing() {
        // TL (F4): the viewer is a modal for read proof and typing; closing requests no focus and publishes nothing.
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent(); loaded(70)
        compose.waitForIdle()
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerShown() }
        val reads = rooms.reads.get(); val typed = typing.requests.get()
        rooms.messages = rooms.messages + roomMessage(4, "A newer message")
        compose.runOnIdle { room.refresh() }
        compose.waitUntil(5_000) { room.state.value.messages.any { it.id == 4L } && !room.state.value.busy }
        compose.waitForIdle()
        assertEquals("No read proof under the viewer", reads, rooms.reads.get())
        compose.onNodeWithTag("viewer_close").performClick()
        compose.waitUntil(5_000) { !viewerShown() }
        compose.waitForIdle()
        assertEquals("Nothing typed or published", typed, typing.requests.get())
        compose.onNodeWithTag("conversation_composer").assertIsNotFocused()
    }

    @Test fun aSessionVersionBumpKeepsTheViewerOpen() {
        // TL (#313): a re-keyed screen (the room screen keys on sessionVersion) is a dispose, never a departure.
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent(); loaded(70)
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerShown() }
        compose.runOnIdle { session.value = session.value.copy(user = session.value.user!!.copy(sessionVersion = 2)) }
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
        compose.waitForIdle()
        assertEquals(70L, images.viewer.state.value?.ref?.attachment?.id)
        compose.waitUntil(5_000) { viewerShown() }
    }

    private class Owner : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    @Test fun aBackgroundKeepsTheViewerAndTheEntrysDepartureClosesIt() {
        // TL (#313): ON_STOP with the entry still current is a background; with another entry current it's a departure.
        var current = true
        lateinit var owner: Owner
        compose.runOnUiThread { owner = Owner().apply { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED } }
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) {
                    ConversationScreen(room, 42, {}, images = images, isEntryCurrent = { current })
                } }
            }
        }
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
        loaded(70)
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { images.viewer.state.value != null }
        compose.runOnIdle { owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP) }
        compose.waitForIdle()
        assertEquals("A background keeps the viewer", 70L, images.viewer.state.value?.ref?.attachment?.id)
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }
        current = false
        compose.runOnIdle { owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP) }
        compose.waitForIdle()
        assertEquals("Navigating away closes it", null, images.viewer.state.value)
    }

    @Test fun aDisposeAfterTheEntryStopsBeingCurrentClosesTheViewerWithoutAnOnStop() {
        // TL (#313): if Navigation disposes the leaving entry before its ON_STOP, the dispose closes the viewer.
        var current = true
        var shown by androidx.compose.runtime.mutableStateOf(true)
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        compose.setContent {
            MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) {
                if (shown) ConversationScreen(room, 42, {}, images = images, isEntryCurrent = { current })
            } }
        }
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
        loaded(70)
        val previewOnly = source.reserved
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerShown() }
        compose.onNodeWithTag("viewer_load_full").performClick()
        compose.waitUntil(5_000) { images.viewer.state.value?.original != null }
        assertTrue("The original holds a lease", source.reserved > previewOnly)
        current = false
        compose.runOnIdle { shown = false } // Disposed, with no ON_STOP.
        compose.waitForIdle()
        assertEquals(null, images.viewer.state.value)
        assertEquals("Its lease is released", previewOnly, source.reserved)
    }

    @Test fun aDmFrameOpensTheViewerToo() {
        directs.rows = listOf(dm(18, null, photo(70, direct = true)))
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { DirectThreadScreen(direct, 8, {}, images = images) } } }
        compose.waitUntil(5_000) { direct.state.value.loaded && !direct.state.value.busy }
        loaded(70)
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerShown() }
        val opened = images.viewer.state.value!!
        assertTrue("The DM namespace", opened.ref.direct)
        assertEquals("Only the transcript's preview was downloaded", listOf(70L), source.calls.toList())
    }
}
