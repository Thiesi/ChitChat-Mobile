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
    private val store = ViewModelStore()
    private val images: AttachmentImagesViewModel = ViewModelProvider.create(store, viewModelFactory {
        initializer { AttachmentImagesViewModel(session, FixedNetwork(), source, 32L * 1024 * 1024) }
    })[AttachmentImagesViewModel::class.java]
    private val rooms = ReceivedRooms(session)
    private val room = ConversationViewModel(rooms)
    private val directs = ReceivedDirects(session)
    private val direct = DirectThreadViewModel(directs)
    private val actionsLabel = context.getString(R.string.message_actions)

    @After fun tearDown() { compose.runOnIdle { room.cancel(); direct.background() }; store.clear() }

    private fun roomContent() {
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(room, 42, {}, images = images) } } }
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

    @Test fun aCaptionIsPartOfTheFrameLabel() {
        rooms.messages = listOf(roomMessage(3, "Sunset over the bay", photo(70)))
        roomContent()
        assertTrue(description("attachment_frame:70"), description("attachment_frame:70").endsWith(", Sunset over the bay"))
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
        assertEquals("notes.pdf, PDF, 2.4 MB", description("attachment_card:71"))
        val shown = compose.onAllNodesWithTag("attachment_card_name")[1].fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
        assertTrue(shown, shown.endsWith(".pdf") && shown.contains("…"))
        compose.onNode(clickLabelled(actionsLabel) and hasAnyAncestor(hasTestTag("message:3"))).assertHeightIsAtLeast(48.dp)
        assertTrue("Cards never download", source.calls.isEmpty())
    }

    @Test fun beyondTheCapsTheBoxIsStaticTextAndNothingIsFetched() {
        rooms.messages = listOf(roomMessage(3, null, photo(70, size = 40L * 1024 * 1024)))
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
        directs.rows = listOf(dm(18, null, photo(70)))
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
        directs.rows = listOf(dm(18, null, photo(70)))
        directContent()
        compose.waitUntil(5_000) { 70L in source.calls }
        compose.onNodeWithText(context.getString(R.string.image_not_allowed)).assertExists()
        // The loader schedules nothing after a refusal: once idle, nothing else has been requested.
        compose.waitForIdle()
        assertEquals("No retry loop", listOf(70L), source.calls.toList())
    }
}
