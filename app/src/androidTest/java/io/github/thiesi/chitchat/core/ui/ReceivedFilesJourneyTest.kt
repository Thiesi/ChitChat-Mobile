package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.MessagePerson
import io.github.thiesi.chitchat.core.conversation.MessageReply
import io.github.thiesi.chitchat.core.conversation.ReplyMessage
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.dm
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.file
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadScreen
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Received files F3 in the real room and DM screens: frames and cards are their own non-clickable nodes inside the
 * message-actions target (TL/PL (b)), Show and Try again take their own taps, and only rows inside the viewport load.
 */
class ReceivedFilesJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = ReceivedFiles.session()
    private val source = ReceivedSource()
    private val network = FixedNetwork()
    private val store = ViewModelStore()
    private val images: AttachmentImagesViewModel = ViewModelProvider.create(store, viewModelFactory {
        initializer { AttachmentImagesViewModel(session, network, source, 32L * 1024 * 1024) }
    })[AttachmentImagesViewModel::class.java]
    private val rooms = ReceivedRooms(session)
    private val room = ConversationViewModel(rooms)
    private val directs = ReceivedDirects(session)
    private val direct = DirectThreadViewModel(directs)
    private val actionsLabel = context.getString(R.string.message_actions)

    @After fun tearDown() { compose.runOnIdle { room.cancel(); direct.background() }; store.clear() }

    private fun roomContent(fontScale: Float = 1f) {
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(base.density, fontScale)) {
                MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(room, 42, {}, images = images) } }
            }
        }
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
    }
    private fun directContent() {
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { DirectThreadScreen(direct, 8, {}, images = images) } } }
        compose.waitUntil(5_000) { direct.state.value.loaded && !direct.state.value.busy }
    }

    private fun clickLabelled(label: String) = SemanticsMatcher("click label $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }
    private val noClick = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
    private val noLongClick = SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick)
    private fun description(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

    @Test fun anAttachmentOnlyRoomRowKeepsItsActionsNodeAndTheFrameHasNoClick() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent()
        compose.onNodeWithTag("attachment_frame:70").assert(noClick).assert(noLongClick)
        compose.onNode(clickLabelled(actionsLabel) and hasAnyAncestor(hasTestTag("message:3"))).assertHeightIsAtLeast(48.dp)
        assertTrue(description("attachment_frame:70"), description("attachment_frame:70").startsWith("Photo from Bob, "))
    }

    @Test fun aCaptionIsReadWithTheActionsNodeNotTheFrame() {
        // PL (#310): the caption is heard once, with the message's actions node.
        rooms.messages = listOf(roomMessage(3, "Sunset over the bay", photo(70)))
        roomContent()
        assertFalse(description("attachment_frame:70"), description("attachment_frame:70").contains("Sunset"))
        val actions = compose.onNode(clickLabelled(actionsLabel) and hasAnyAncestor(hasTestTag("message:3"))).fetchSemanticsNode().config
        assertTrue(actions.getOrNull(SemanticsProperties.Text).orEmpty().any { "Sunset over the bay" in it.text })
    }

    @Test fun eachNonLoadedStateFollowsTheLabelInTheFramesDescription() {
        // TL/PL (#310): TalkBack reads contentDescription instead of the merged text, so the state line is in it.
        val cases = listOf(
            Pair<suspend () -> DownloadResult, Int>({ DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }, R.string.image_failed),
            Pair<suspend () -> DownloadResult, Int>({ DownloadResult.NoMemory }, R.string.image_no_memory),
            Pair<suspend () -> DownloadResult, Int>({ DownloadResult.Gone }, R.string.image_gone),
            Pair<suspend () -> DownloadResult, Int>({ DownloadResult.NotAllowed }, R.string.image_not_allowed),
        )
        cases.forEachIndexed { index, (reply, _) -> source.reply(70L + index, reply) }
        rooms.messages = cases.indices.map { roomMessage(3L + it, null, photo(70L + it)) } +
            roomMessage(9, null, photo(79, size = 40_000_000, preview = false))
        roomContent()
        cases.forEachIndexed { index, (_, line) ->
            val tag = "attachment_frame:${70L + index}"
            compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag(tag))
            compose.waitUntil(5_000) { description(tag).endsWith(". " + context.getString(line)) }
            assertTrue(description(tag), Regex("Photo from Bob, [^.]+\\. ").containsMatchIn(description(tag)))
        }
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("attachment_frame:79"))
        assertTrue(description("attachment_frame:79"),
            Regex("^Photo from Bob, [^.]+\\. 40\u00A0MB, ${Regex.escape(context.getString(R.string.image_too_large_part))}$")
                .matches(description("attachment_frame:79")))
    }

    @Test fun notLoadedReadsTheSizeAloneWithoutPhotoOrShow() {
        // PL (#310): the box says "Photo · 3.4 MB" with Show; TalkBack hears the size alone, and Show as its own node.
        network.state.value = io.github.thiesi.chitchat.core.media.NetworkState(online = true, metered = true)
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent()
        compose.waitUntil(5_000) { description("attachment_frame:70").contains(". ") }
        val spoken = description("attachment_frame:70")
        assertTrue(spoken, Regex("^Photo from Bob, [^.]+\\. 3\\.4\u00A0MB$").matches(spoken))
        assertFalse(spoken, spoken.contains(context.getString(R.string.image_show)))
        compose.onNodeWithTag("attachment_show:70").assertHeightIsAtLeast(48.dp)
    }

    @Test fun loadingSpeaksLoadingBeforeAndAfterTheVisibleLine() {
        source.reply(70) { kotlinx.coroutines.awaitCancellation() }
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent()
        compose.waitUntil(5_000) { description("attachment_frame:70").endsWith(". " + context.getString(R.string.image_loading_spoken)) }
        compose.onAllNodesWithText(context.getString(R.string.image_loading)).assertCountEquals(0)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.image_loading)).fetchSemanticsNodes().isNotEmpty() }
        val spoken = description("attachment_frame:70")
        assertTrue("Unchanged after the 2 s line: $spoken", spoken.endsWith(". " + context.getString(R.string.image_loading_spoken)))
        assertFalse(spoken, spoken.contains(context.getString(R.string.image_loading)))
    }

    @Test fun offlineReadsTheSizeAndWhenItCanLoad() {
        network.state.value = io.github.thiesi.chitchat.core.media.NetworkState(online = false, metered = true)
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent()
        compose.waitUntil(5_000) { description("attachment_frame:70").contains(". ") }
        assertTrue(description("attachment_frame:70"),
            description("attachment_frame:70").endsWith(". 3.4\u00A0MB, " + context.getString(R.string.image_offline_part)))
        assertTrue("Offline requests nothing", source.calls.isEmpty())
    }

    @Test fun aLoadedFrameIsTheLabelAloneAndAGifSaysGif() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)), roomMessage(4, null, photo(71, mime = "image/gif")))
        roomContent()
        compose.waitUntil(5_000) {
            images.images.states.value.values.count { it is io.github.thiesi.chitchat.core.media.ImageState.Loaded } == 2
        }
        compose.waitForIdle()
        assertTrue(description("attachment_frame:70"), Regex("^Photo from Bob, [^.]+$").matches(description("attachment_frame:70")))
        assertTrue(description("attachment_frame:71"), Regex("^GIF from Bob, [^.]+$").matches(description("attachment_frame:71")))
    }

    @Test fun showAndTryAgainFitWholeInsideA96dpPanoramaFrame() = shortFrame(1f)
    @Test fun showAndTryAgainFitWholeInsideA96dpPanoramaFrameAt200Percent() = shortFrame(2f)

    /** Review (#310): a short frame never clips its button out of reach. */
    private fun shortFrame(scale: Float) {
        network.state.value = io.github.thiesi.chitchat.core.media.NetworkState(online = true, metered = true)
        source.reply(71) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }
        rooms.messages = listOf(roomMessage(3, null, photo(70, width = 6000, height = 1000)),
            roomMessage(4, null, photo(71, width = 6000, height = 1000)))
        roomContent(scale)
        fun inside(frame: String, button: String) {
            compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag(frame))
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(button).fetchSemanticsNodes().isNotEmpty() }
            val outer = compose.onNodeWithTag(frame).fetchSemanticsNode().boundsInRoot
            val inner = compose.onNodeWithTag(button).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertTrue("$button at ${scale}x: $inner inside $outer", inner.top >= outer.top - 0.5f &&
                inner.bottom <= outer.bottom + 0.5f && inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f)
            assertEquals(96f, with(compose.density) { outer.height.toDp().value }, 1f)
        }
        inside("attachment_frame:70", "attachment_show:70")
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("attachment_frame:71"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment_show:71").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_show:71").performClick()
        inside("attachment_frame:71", "attachment_retry:71")
    }

    @Test fun bothDisclosuresNameWhatIsStillMissing() {
        // PL (#310 J3): the F3 wording; the signed-in form has no development prefix.
        val missing = "Not in the app yet: saving, opening and sharing files and photos, viewing photos full screen, profile pictures, " +
            "and guest invite links."
        assertEquals("This is a development build. $missing", context.getString(R.string.preview_limit))
        assertEquals(missing, context.getString(R.string.auth_preview_limit))
    }

    @Test fun tryAgainRetriesOnceAndNeverOpensMessageActions() {
        source.reply(70) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment_retry:70").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_retry:70").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5_000) { source.calls.size == 2 }
        compose.waitForIdle()
        assertEquals("Exactly one retry", listOf(70L, 70L), source.calls.toList())
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun aFileCardReadsAsOneUnclickableNodeAndKeepsItsExtension() {
        val long = "quarterly-report-for-the-steering-committee-final-version-approved.pdf"
        rooms.messages = listOf(roomMessage(3, null, file(71, "notes.pdf")), roomMessage(4, null, file(72, long)))
        roomContent()
        compose.onNodeWithTag("attachment_card:71").assert(noClick).assert(noLongClick)
        assertEquals("notes.pdf, PDF, 2.4\u00A0MB", description("attachment_card:71"))
        // The card is one merged TalkBack node, so its name text is only in the unmerged tree.
        val shown = compose.onAllNodesWithTag("attachment_card_name", useUnmergedTree = true)[1].fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
        assertTrue(shown, shown.endsWith(".pdf") && shown.contains("…"))
        compose.onNode(clickLabelled(actionsLabel) and hasAnyAncestor(hasTestTag("message:3"))).assertHeightIsAtLeast(48.dp)
        assertTrue("Cards never download", source.calls.isEmpty())
    }

    @Test fun beyondTheCapsTheBoxIsStaticTextAndNothingIsFetched() {
        rooms.messages = listOf(roomMessage(3, null, photo(70, size = 40_000_000, preview = false)))
        roomContent()
        compose.onNodeWithTag("attachment_frame:70").assert(noClick).assert(noLongClick)
        compose.onNodeWithText("too large to show here", substring = true).assertExists()
        compose.onAllNodesWithTag("attachment_show:70").assertCountEquals(0)
        assertTrue(source.calls.isEmpty())
    }

    @Test fun framesOutsideTheViewportLoadNothingUntilScrolledIntoView() {
        rooms.messages = listOf(roomMessage(1, "Top", photo(70))) + (2L..30L).map { roomMessage(it, "Filler message $it") }
        roomContent()
        compose.waitForIdle()
        assertTrue("Above the viewport, so not on screen: ${source.calls}", 70L !in source.calls)
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("attachment_frame:70"))
        compose.waitUntil(5_000) { 70L in source.calls }
    }

    @Test fun aReplyQuotingAPhotoShowsNoFrameInTheQuote() {
        rooms.messages = listOf(roomMessage(2, "Look", photo(70)),
            roomMessage(3, "Nice", reply = MessageReply("room", 2, true, ReplyMessage(2, MessagePerson(8, "Bob"), "text", "Look", false))))
        roomContent()
        compose.onAllNodesWithTag("attachment_frame:70").assertCountEquals(1)
        compose.onNode(hasTestTag("attachment_frame:70") and hasAnyAncestor(hasTestTag("message:3"))).assertDoesNotExist()
    }

    @Test fun anIgnoredAuthorsPhotoLoadsNothingUntilRevealed() {
        rooms.ignored = setOf(8L)
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        roomContent()
        compose.waitForIdle()
        assertTrue(source.calls.isEmpty())
        compose.onNodeWithText(context.getString(R.string.conversation_ignored)).performClick()
        compose.waitUntil(5_000) { 70L in source.calls }
    }

    @Test fun anAttachmentOnlyDmRowKeepsItsActionsNodeAndTheFrameHasNoClick() {
        directs.rows = listOf(dm(18, null, photo(70, direct = true)))
        directContent()
        compose.onNodeWithTag("attachment_frame:70").assert(noClick).assert(noLongClick)
        compose.onNode(clickLabelled(actionsLabel) and hasAnyAncestor(hasTestTag("direct:18"))).assertHeightIsAtLeast(48.dp)
    }

    @Test fun aDmFileCardKeepsTheSameStructure() {
        directs.rows = listOf(dm(18, null, file(71, "notes.pdf")))
        directContent()
        compose.onNodeWithTag("attachment_card:71").assert(noClick).assert(noLongClick)
        compose.onNode(clickLabelled(actionsLabel) and hasAnyAncestor(hasTestTag("direct:18"))).assertHeightIsAtLeast(48.dp)
    }

    @Test fun aBlockedThreadLoadsLikeAnyOtherAndARefusalShowsWithoutARetryLoop() {
        // TL (F3): blocking governs sending, not history; the server decides file access.
        directs.blocked = true
        source.reply(70) { DownloadResult.NotAllowed }
        directs.rows = listOf(dm(18, null, photo(70, direct = true)))
        directContent()
        compose.waitUntil(5_000) { 70L in source.calls }
        compose.onNodeWithText(context.getString(R.string.image_not_allowed)).assertExists()
        // The loader schedules nothing after a refusal: once idle, nothing else has been requested.
        compose.waitForIdle()
        assertEquals("No retry loop", listOf(70L), source.calls.toList())
    }
}
