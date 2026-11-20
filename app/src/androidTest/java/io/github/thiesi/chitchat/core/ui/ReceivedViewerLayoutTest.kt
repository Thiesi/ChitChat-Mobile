package io.github.thiesi.chitchat.core.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import java.nio.file.Files
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
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
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.FullImage
import io.github.thiesi.chitchat.core.media.ImageState
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Received files F4b: the viewer's bottom layout at a real 200% font scale (PL J2, J8). The Dialog's window takes its
 * density from the Activity's Configuration, so the font scale comes from [FontScaleTestActivity], never from a
 * LocalDensity override in the host composition, which the viewer's window doesn't see.
 */
class ReceivedViewerLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val session = ReceivedFiles.session()
    private val source = ReceivedSource()
    private val store = ViewModelStore()
    private val images: AttachmentImagesViewModel = ViewModelProvider.create(store, viewModelFactory {
        initializer { AttachmentImagesViewModel(session, FixedNetwork(), source, 32L * 1024 * 1024) }
    })[AttachmentImagesViewModel::class.java]
    private val rooms = ReceivedRooms(session)
    private val room = ConversationViewModel(rooms)
    private val longCaption = (1..30).joinToString(" ") { "We walked down to the harbour after dinner and stayed until the light went out." }

    @After fun tearDown() { FontScaleTestActivity.requestedFontScale = 1f; room.cancel(); store.clear() }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun Rect.overlaps(other: Rect) = left < other.right - 0.5f && other.left < right - 0.5f &&
        top < other.bottom - 0.5f && other.top < bottom - 0.5f

    /** Opens the viewer on a long caption at 200%, in the given orientation, and expands the caption. */
    private fun openExpanded(landscape: Boolean, failed: Boolean = false, check: (collapsedCaptionHeight: Float) -> Unit) {
        FontScaleTestActivity.requestedFontScale = 2f
        rooms.messages = listOf(roomMessage(3, longCaption, photo(70)))
        if (failed) {
            source.serve(70)
            source.reply(70) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }
        }
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            val wanted = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            scenario.onActivity {
                it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(5_000) {
                var orientation = 0
                scenario.onActivity { orientation = it.resources.configuration.orientation }
                orientation == wanted
            }
            scenario.onActivity { activity ->
                assertEquals(2f, activity.resources.configuration.fontScale)
                activity.setContent { ChitChatTheme(dark = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}, images = images) }
                } }
            }
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            compose.waitUntil(5_000) { images.images.states.value.values.any { it is ImageState.Loaded } }
            // The tall row starts scrolled to its bottom: bring the frame into view before the tap.
            compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("attachment_frame:70"))
            compose.onNodeWithTag("attachment_frame:70").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer").fetchSemanticsNodes().isNotEmpty() }
            if (failed) {
                compose.onNodeWithTag("viewer_load_full").performClick()
                compose.waitUntil(5_000) { images.viewer.state.value?.full == FullImage.FAILED }
            }
            compose.waitForIdle()
            val collapsed = bounds("viewer_caption").height
            compose.onNodeWithTag("viewer_caption_toggle").performClick()
            compose.waitForIdle()
            check(collapsed)
        }
    }

    @Test fun portraitAt200PercentThePillStaysAboveTheBottomBlockAndBelowTheTopBar() = openExpanded(landscape = false) { _ ->
        val top = bounds("viewer_top"); val pill = bounds("viewer_zoom"); val bottom = bounds("viewer_bottom")
        assertTrue("Pill $pill below the top bar $top", pill.top >= top.bottom - 0.5f)
        assertTrue("Pill $pill above the bottom block $bottom", pill.bottom <= bottom.top + 0.5f)
        assertTrue("Bottom block $bottom below the top bar $top", bottom.top >= top.bottom - 0.5f)
    }

    @Test fun landscapeAt200PercentThePillSitsBesideTheBottomBlockAndTheCaptionKeepsTwoLines() = openExpanded(landscape = true) { collapsed ->
        // PL J8: beside, not above, so the expanded caption keeps at least the two lines it shows collapsed.
        val top = bounds("viewer_top"); val pill = bounds("viewer_zoom"); val bottom = bounds("viewer_bottom")
        val caption = bounds("viewer_caption")
        assertTrue("Pill $pill clear of the top bar $top", !pill.overlaps(top))
        assertTrue("Pill $pill clear of the bottom block $bottom", !pill.overlaps(bottom))
        assertTrue("Bottom block $bottom below the top bar $top", bottom.top >= top.bottom - 0.5f)
        assertTrue("Pill $pill at the end of the bottom block $bottom", pill.left >= bottom.right - 0.5f)
        assertTrue("Expanded caption ${caption.height} keeps two lines ($collapsed)", caption.height >= collapsed - 0.5f)
        // PL J8: More/Less and Load full image share one row, at its two ends.
        val toggle = bounds("viewer_caption_toggle"); val load = bounds("viewer_load_full")
        assertTrue("One row: $toggle, $load", toggle.top < load.bottom && load.top < toggle.bottom)
        assertTrue("Less at the start, the action at the far end", toggle.right < load.left)
        // TalkBack: the zoom group before the bottom bar, as in portrait.
        val pillIndex = compose.onNodeWithTag("viewer_zoom").fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex)
        assertEquals(0f, pillIndex)
        // Reading order in the block: caption, More/Less, the action (no state line here).
        assertEquals(listOf(0f, 1f, 3f), listOf("viewer_caption", "viewer_caption_toggle", "viewer_load_full").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex)
        })
    }

    @Test fun landscapeAt200PercentAFailedOriginalKeepsItsLineAboveAndOneCaptionLine() =
        openExpanded(landscape = true, failed = true) { collapsed ->
            // PL J8: with a state line, the expanded caption keeps at least one full line; the line sits above the shared
            // row of Less and Try again.
            val top = bounds("viewer_top"); val bottom = bounds("viewer_bottom"); val caption = bounds("viewer_caption")
            val line = bounds("viewer_line"); val toggle = bounds("viewer_caption_toggle"); val retry = bounds("viewer_retry")
            assertTrue("Bottom block $bottom below the top bar $top", bottom.top >= top.bottom - 0.5f)
            assertTrue("Expanded caption ${caption.height} keeps one line (${collapsed / 2})", caption.height >= collapsed / 2 - 0.5f)
            assertTrue("The line $line above the row $toggle", line.bottom <= minOf(toggle.top, retry.top) + 0.5f)
            assertTrue("One row: $toggle, $retry", toggle.top < retry.bottom && retry.top < toggle.bottom)
            assertTrue("Less at the start, Try again at the far end", toggle.right < retry.left)
            assertEquals(listOf(0f, 1f, 2f, 3f), listOf("viewer_caption", "viewer_caption_toggle", "viewer_line", "viewer_retry").map {
                compose.onNodeWithTag(it).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex)
            })
        }

    @Test fun at200PercentTheProgressStatusWrapsWithoutOverflow() {
        // PL J1: the status line wraps at a real 200%; only the name keeps to one middle-elided line.
        FontScaleTestActivity.requestedFontScale = 2f
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val copies = Files.createTempDirectory("ccm-progress-200").toFile()
        val job = kotlinx.coroutines.SupervisorJob()
        val transfers = io.github.thiesi.chitchat.core.media.FileTransfers(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + job),
            session, { _, _, open, _ -> open().use { it.write(ByteArray(10)); kotlinx.coroutines.runBlocking { gate.await() } }; DownloadResult.Saved },
            io.github.thiesi.chitchat.core.media.ShareCopies(copies), { Long.MAX_VALUE })
        val ui = FileTransfersUi(transfers, kotlinx.coroutines.flow.flowOf(true)) { }
        val report = ReceivedFiles.file(71, "quarterly-report-for-the-steering-committee-final.pdf")
        rooms.messages = listOf(roomMessage(3, null, report))
        try {
            ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { ChitChatTheme(dark = false) { CompositionLocalProvider(LocalFileTransfers provides ui) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ConversationScreen(room, 42, {}, images = images) }
                    } } }
                }
                compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
                compose.runOnIdle { ui.hand(io.github.thiesi.chitchat.core.media.AttachmentRef(report, false), io.github.thiesi.chitchat.core.media.TransferPurpose.SHARE) }
                compose.waitUntil(5_000) { transfers.state.value is io.github.thiesi.chitchat.core.media.TransferState.Running }
                compose.waitForIdle()
                fun layout(tag: String): androidx.compose.ui.text.TextLayoutResult {
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    // Fetched on the test thread; only the layout action itself runs on the UI thread.
                    val action = compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!
                    compose.runOnIdle { action(results) }
                    return results.single()
                }
                assertFalse("The status wraps, never clips", layout("transfer_line").hasVisualOverflow)
                assertEquals("The name keeps to one line", 1, layout("transfer_name").lineCount)
            }
        } finally { gate.complete(Unit); job.cancel(); copies.deleteRecursively() }
    }
}
