package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.AttachmentRef
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.FileSource
import io.github.thiesi.chitchat.core.media.FileTransfers
import io.github.thiesi.chitchat.core.media.FullImage
import io.github.thiesi.chitchat.core.media.ImageKey
import io.github.thiesi.chitchat.core.media.ShareCopies
import io.github.thiesi.chitchat.core.media.TransferState
import io.github.thiesi.chitchat.core.media.ViewerState
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.file
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Received files F5b: Save, Share and Open in the real room screen (PL and TL rulings of 2026-11-20). */
class ReceivedTransfersJourneyTest {
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
    private val root: File = Files.createTempDirectory("ccm-transfers-journey").toFile()
    private val job = SupervisorJob()

    /** Streams a few bytes; [gate] holds the body so a transfer stays running. */
    private val streamed = mutableListOf<Long>()
    private var gate: CompletableDeferred<Unit>? = null
    /** Holds the stream before its body (a Cancel then lands before any byte); [preBody] ends it there instead. */
    private var preGate: CompletableDeferred<Unit>? = null
    private var preBody: DownloadResult? = null
    private val fileSource = FileSource { ref, _, open, _ ->
        synchronized(streamed) { streamed += ref.attachment.id }
        preGate?.let { runBlocking { it.await() } }
        preBody?.let { return@FileSource it }
        open().use { out -> out.write(ByteArray(4)); gate?.let { runBlocking { it.await() } }; out.write(ByteArray(4)) }
        DownloadResult.Saved
    }
    private val transfers = FileTransfers(CoroutineScope(Dispatchers.Main + job), session, fileSource, ShareCopies(root), { Long.MAX_VALUE })
    private var noteMarked = 0
    private val ui = FileTransfersUi(transfers) { noteMarked++ }
    private var launched: FileTransfers.PendingSave? = null

    @After fun tearDown() { gate?.complete(Unit); preGate?.complete(Unit); compose.runOnIdle { room.cancel() }; job.cancel(); store.clear(); root.deleteRecursively() }

    private fun text(id: Int) = context.getString(id)
    private fun description(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty()
    private fun absent(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    @Composable private fun Provided(content: @Composable () -> Unit) {
        ui.launchSave = { launched = it }
        CompositionLocalProvider(LocalFileTransfers provides ui) { MaterialTheme { content() } }
    }

    private fun roomContent() {
        compose.setContent { Provided { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(room, 42, {}, images = images) } } }
        compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
    }

    /**
     * The labelled "Message actions" node: tapped by touch (file cards), or invoked through its own click action as
     * TalkBack does ([semantics]). A touch at a photo row's centre lands on the frame, whose tap opens the viewer, so a
     * photo's touch route is the frame's long-press ([longPressFrame]) (#318/#319 failed runs 44714352389, 44714400050).
     */
    private fun openActions(messageId: Long, semantics: Boolean = false, longPressFrame: Long? = null) {
        val label = text(R.string.message_actions)
        val node = compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == label } and
            hasAnyAncestor(hasTestTag("message:$messageId")))
        when {
            longPressFrame != null -> compose.onNodeWithTag("attachment_frame:$longPressFrame").performTouchInput { longClick() }
            semantics -> node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            else -> node.performClick()
        }
        compose.waitUntil(5_000) { !absent("message_actions") }
    }

    private fun closeActions() {
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { absent("message_actions") }
    }

    private fun apkTypedPhoto(id: Long) = photo(id).let {
        MessageAttachment(it.id, "pic.apk", it.mimeType, it.sizeBytes, it.sha256, it.previewable, it.width, it.height, it.preview)
    }

    @Test fun aFileCardOffersOpenAndSaveAndAnApkOnlySave() {
        rooms.messages = listOf(roomMessage(3, null, file(71, "notes.pdf")), roomMessage(4, null, file(72, "tool.apk",
            mime = "application/vnd.android.package-archive")))
        roomContent()
        assertEquals("notes.pdf, PDF, 2.4 MB, Open, Save", description("attachment_card:71"))
        compose.onNodeWithTag("attachment_open:71").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        compose.onNodeWithTag("attachment_save:71").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        // APK: Save only, on the card.
        assertTrue(description("attachment_card:72").endsWith(", Save") && !description("attachment_card:72").contains("Open"))
        assertTrue(absent("attachment_open:72"))
        compose.onNodeWithTag("attachment_save:72").assertIsEnabled()
    }

    @Test fun messageActionsOfferSaveAndShareButNoShareForAnApk() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)), roomMessage(4, null, file(71, "notes.pdf")),
            roomMessage(5, null, apkTypedPhoto(80)))
        roomContent()
        // A photo: the touch route (the frame's long-press) and the TalkBack route reach the same choices (TL).
        for (route in listOf("long-press", "semantics")) {
            if (route == "long-press") openActions(3, longPressFrame = 70) else openActions(3, semantics = true)
            compose.onNodeWithTag("message_save_action").assertTextEquals(text(R.string.action_save_photo))
            compose.onNodeWithTag("message_share_action").assertTextEquals(text(R.string.action_share_photo))
            compose.onNodeWithTag("message_save_action").assertHeightIsAtLeast(48.dp)
            assertTrue("$route opened no viewer", absent("viewer"))
            closeActions()
        }
        openActions(4)
        compose.onNodeWithTag("message_save_action").assertTextEquals(text(R.string.action_save_file))
        compose.onNodeWithTag("message_share_action").assertTextEquals(text(R.string.action_share_file))
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { absent("message_actions") }
        // An APK-like name under an image type: a Save-only card (never a frame) and no Share in its actions (TL).
        compose.onNodeWithTag("attachment_card:80").assertExists()
        openActions(5)
        compose.onNodeWithTag("message_save_action").assertTextEquals(text(R.string.action_save_file))
        assertTrue("No Share for an APK", absent("message_share_action"))
    }

    @Test fun theViewerOffersSaveThenShareButNoShareForAnApk() {
        val origin = session.value.server!!.value
        fun viewer(attachment: MessageAttachment, full: FullImage) = ViewerState(AttachmentRef(attachment, false),
            ImageKey(origin, 7, session.value.generation, false, attachment.id), "entry", null, full, animationUnavailable = false)
        var shown by androidx.compose.runtime.mutableStateOf(viewer(photo(70), FullImage.OFFER))
        compose.setContent { Provided {
            ReceivedImageViewer(shown, null, "Bob", "12:00", null, "Photo from Bob", onClose = {}, onLoadFull = {})
        } }
        compose.onNodeWithTag("viewer_save").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        compose.onNodeWithTag("viewer_share").assertIsEnabled()
        val save = compose.onNodeWithTag("viewer_save").fetchSemanticsNode().boundsInRoot
        val share = compose.onNodeWithTag("viewer_share").fetchSemanticsNode().boundsInRoot
        assertTrue("Share is outermost", share.left > save.left)
        // Offline: both disabled. Gone: neither shows.
        compose.runOnIdle { shown = viewer(photo(70), FullImage.OFFLINE) }
        compose.onNodeWithTag("viewer_save").assertIsNotEnabled()
        compose.onNodeWithTag("viewer_share").assertIsNotEnabled()
        compose.runOnIdle { shown = viewer(photo(70), FullImage.GONE) }
        compose.waitForIdle()
        assertTrue(absent("viewer_save") && absent("viewer_share"))
        // An APK-like attachment: Save only, even if a viewer were ever opened for one.
        compose.runOnIdle { shown = viewer(apkTypedPhoto(80), FullImage.OFFER) }
        compose.waitForIdle()
        compose.onNodeWithTag("viewer_save").assertExists()
        assertTrue("No Share for an APK", absent("viewer_share"))
    }

    @Test fun theFirstUseNoteCancelStartsNothingAndContinueStartsTheCopy() {
        rooms.messages = listOf(roomMessage(3, null, file(71, "notes.pdf")))
        roomContent()
        compose.onNodeWithTag("attachment_open:71").performClick()
        compose.waitUntil(5_000) { !absent("transfer_note") }
        assertTrue("A modal: no read proof or typing", ui.dialogOpen)
        compose.onNodeWithTag("transfer_note_cancel").performClick()
        compose.waitUntil(5_000) { absent("transfer_note") }
        compose.runOnIdle {
            assertNull("Cancel creates no transfer", transfers.state.value)
            assertFalse(transfers.busy)
            assertTrue("No download, no copy", streamed.isEmpty() && root.walk().none { it.isFile })
            assertNull("No picker", launched)
            assertEquals(0, noteMarked)
        }
        compose.onNodeWithTag("attachment_open:71").performClick()
        compose.waitUntil(5_000) { !absent("transfer_note") }
        compose.onNodeWithTag("transfer_note_continue").performClick()
        compose.waitUntil(5_000) { transfers.state.value is TransferState.Ready }
        compose.waitUntil(5_000) { noteMarked == 1 }
        assertEquals(listOf(71L), streamed)
    }

    @Test fun whileATransferRunsTheButtonsAreDisabledAndItsNoticeShows() {
        gate = CompletableDeferred()
        rooms.messages = listOf(roomMessage(3, null, file(71, "notes.pdf")), roomMessage(4, null, file(72, "other.pdf")))
        roomContent()
        compose.onNodeWithTag("attachment_save:71").performClick()
        val request = compose.runOnIdle { launched!! }
        compose.runOnIdle { transfers.saveChosen(request) { java.io.ByteArrayOutputStream() } }
        compose.waitUntil(5_000) { transfers.state.value is TransferState.Running }
        compose.onNodeWithTag("transfer_notice").assertExists()
        compose.onNodeWithTag("transfer_cancel").assertHeightIsAtLeast(48.dp)
        // PL J2: one stable spoken line with the progress as range info; Cancel is its own node.
        val progress = compose.onNodeWithTag("transfer_progress").fetchSemanticsNode().config
        assertEquals(context.getString(R.string.transfer_saving_indeterminate, "notes.pdf"),
            progress.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(""))
        assertTrue("Range info", progress.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null)
        assertTrue("No percent spoken", progress.getOrNull(SemanticsProperties.Text) == null)
        compose.onNodeWithTag("attachment_save:72").assertIsNotEnabled()
        compose.onNodeWithTag("attachment_open:72").assertIsNotEnabled()
        gate!!.complete(Unit)
        compose.waitUntil(5_000) { transfers.state.value is TransferState.Saved }
        compose.onNodeWithTag("transfer_line").assertTextEquals(context.getString(R.string.transfer_saved, "notes.pdf"))
        compose.onNodeWithTag("attachment_save:72").assertIsEnabled()
    }

    @Test fun aSaveTheyCancelIsLevelOneAndAFailureIsLevelTwo() {
        // PL (F5b): what the person did is level 1; a failure is level 2.
        preGate = CompletableDeferred()
        rooms.messages = listOf(roomMessage(3, null, file(71, "notes.pdf")))
        roomContent()
        compose.onNodeWithTag("attachment_save:71").performClick()
        val first = compose.runOnIdle { launched!! }
        compose.runOnIdle { transfers.saveChosen(first) { java.io.ByteArrayOutputStream() } }
        compose.waitUntil(5_000) { transfers.state.value is TransferState.Running }
        compose.onNodeWithTag("transfer_cancel").performClick()
        compose.waitUntil(5_000) { !absent("transfer_level_1") }
        compose.onNodeWithTag("transfer_line").assertTextEquals(text(R.string.transfer_nothing_saved_plain))
        // A 410 before any byte: level 2, with its reason.
        preGate!!.complete(Unit); preGate = null; preBody = DownloadResult.Gone
        compose.onNodeWithTag("attachment_save:71").performClick()
        val second = compose.runOnIdle { launched!! }
        compose.runOnIdle { transfers.saveChosen(second) { java.io.ByteArrayOutputStream() } }
        compose.waitUntil(5_000) { !absent("transfer_level_2") }
        assertTrue(absent("transfer_level_1"))
        // PL J3: the notice's text stays within 30% of the window, and the composer keeps its size.
        val window = compose.onRoot().fetchSemanticsNode().size.height
        assertTrue(compose.onNodeWithTag("transfer_notice_text").fetchSemanticsNode().size.height <= window * 0.3f + 1)
        compose.onNodeWithTag("conversation_composer").assertHeightIsAtLeast(48.dp)
    }

    @Test fun aShareDuringTheWaitSaysTheFileIsntReadyAndOffersNoTryAgain() {
        // PL J5: the lead, then row 7's wait sentence; no Try again while the wait runs.
        preBody = DownloadResult.RateLimited(30)
        rooms.messages = listOf(roomMessage(3, null, file(71, "notes.pdf")))
        roomContent()
        compose.runOnIdle { transfers.prepare(AttachmentRef(file(71, "notes.pdf"), false), io.github.thiesi.chitchat.core.media.TransferPurpose.SHARE) }
        compose.waitUntil(5_000) { !absent("transfer_level_2") }
        compose.onNode(androidx.compose.ui.test.hasText(text(R.string.transfer_share_not_ready), substring = true)).assertExists()
        assertTrue("No Try again during the wait", absent("transfer_retry"))
    }

    @Test fun theButtonsComeBackWhenTheSessionWaitEnds() {
        // #318 review: enabling follows observable state, with a one-shot timer to the server's wait; it starts nothing.
        rooms.messages = listOf(roomMessage(3, null, file(71, "notes.pdf")))
        roomContent()
        compose.runOnIdle { session.value = session.value.copy(retryAtMillis = System.currentTimeMillis() + 2_000) }
        compose.onNodeWithTag("attachment_save:71").assertIsNotEnabled()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("attachment_save:71").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("attachment_open:71").assertIsEnabled()
        compose.runOnIdle {
            assertNull("Re-enabling starts nothing", transfers.state.value)
            assertTrue(streamed.isEmpty())
        }
    }

    @Test fun aBidiOverrideNeverReachesTheCardsShownName() {
        rooms.messages = listOf(roomMessage(3, null, file(73, "photo‮gnp.apk", mime = "application/octet-stream")))
        roomContent()
        val shown = compose.onAllNodesWithTag("attachment_card_name", useUnmergedTree = true)[0].fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
        assertFalse(shown.contains('‮'))
        assertTrue(shown, shown.startsWith("photognp"))
        assertFalse(description("attachment_card:73").contains('‮'))
        // The extension check runs on the sanitized name: Save only.
        assertTrue(absent("attachment_open:73"))
    }
}
