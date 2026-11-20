package io.github.thiesi.chitchat.core.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.conversation.MessageAttachment
import io.github.thiesi.chitchat.core.conversation.RoomMessage
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.AttachmentRef
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.FileSource
import io.github.thiesi.chitchat.core.media.FileTransfers
import io.github.thiesi.chitchat.core.media.FullImage
import io.github.thiesi.chitchat.core.media.ImageKey
import io.github.thiesi.chitchat.core.media.ShareCopies
import io.github.thiesi.chitchat.core.media.TransferPurpose
import io.github.thiesi.chitchat.core.media.TransferState
import io.github.thiesi.chitchat.core.media.ViewerState
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.file
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Received files F5c for J (PL, 2026-11-20): Save, Share and Open in the real room screen and viewer at a real
 * Configuration font scale, light at default and 200%, plus one dark card capture. No network: a scripted stream.
 */
class ReceivedTransfersPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }

    /** What the scripted stream does: [result] after writing, or before the body when [preBody]; [hold] keeps it running. */
    private class Script(val result: DownloadResult = DownloadResult.Saved, val preBody: Boolean = false, val hold: Boolean = false)

    private class Shot(val name: String, val messages: List<RoomMessage>, val script: Script = Script(), val space: Long = Long.MAX_VALUE,
        val act: ReceivedTransfersPresentationTest.(FileTransfersUi) -> Unit = {}, val viewer: FullImage? = null, val node: String = "transfer_host")

    private val pdf = file(71, "quarterly-report.pdf")
    private val longSender = "Bartholomew-the-very-long-display-name-of-the-sender"

    private fun shots(): List<Shot> = listOf(
        Shot("transfer-cards", listOf(roomMessage(3, null, pdf), roomMessage(4, null, file(72, "tool.apk",
            "application/vnd.android.package-archive")), roomMessage(5, null, file(73, "photo‮gnp.apk", "application/octet-stream"))),
            // PL: scrolled so the PDF card's Open and Save show, at 200% too.
            act = { compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("attachment_card:71")) }),
        Shot("transfer-save-progress", listOf(roomMessage(3, null, pdf)), Script(hold = true), act = { save(it) }),
        Shot("transfer-share-progress", listOf(roomMessage(3, null, pdf)), Script(hold = true), act = { prepare(it, TransferPurpose.SHARE) }),
        Shot("transfer-saved", listOf(roomMessage(3, null, pdf)), act = { save(it) }),
        Shot("transfer-save-nothing", listOf(roomMessage(3, null, pdf)), Script(DownloadResult.Gone, preBody = true), act = { save(it) }),
        Shot("transfer-save-incomplete", listOf(roomMessage(3, null, pdf)), Script(DownloadResult.Failed(ApiFailure(FailureKind.STORAGE))),
            act = { save(it) }),
        Shot("transfer-save-background", listOf(roomMessage(3, null, pdf)), Script(hold = true), act = {
            save(it); compose.runOnIdle { it.transfers.backgrounded() } }),
        Shot("transfer-no-space", listOf(roomMessage(3, null, pdf)), space = 0, act = { prepare(it, TransferPurpose.OPEN) }),
        Shot("transfer-share-gone", listOf(roomMessage(3, null, pdf)), Script(DownloadResult.Gone, preBody = true),
            act = { prepare(it, TransferPurpose.SHARE) }),
        Shot("transfer-actions-photo", listOf(roomMessage(3, null, photo(70))), act = { actions(3) }, node = "message_actions"),
        Shot("transfer-actions-file", listOf(roomMessage(3, null, pdf)), act = { actions(3) }, node = "message_actions"),
        Shot("transfer-actions-apk", listOf(roomMessage(3, null, file(72, "tool.apk", "application/vnd.android.package-archive"))),
            act = { actions(3) }, node = "message_actions"),
        Shot("transfer-note", listOf(roomMessage(3, null, pdf)), act = {
            compose.onNodeWithTag("attachment_open:71").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer_note").fetchSemanticsNodes().isNotEmpty() }
        }, node = "transfer_note"),
        Shot("transfer-viewer-bar", emptyList(), viewer = FullImage.OFFER, node = "viewer"),
        Shot("transfer-viewer-offline", emptyList(), viewer = FullImage.OFFLINE, node = "viewer"),
        Shot("transfer-viewer-progress", emptyList(), Script(hold = true), viewer = FullImage.NONE, node = "viewer", act = {
            compose.runOnIdle { it.hand(AttachmentRef(photo(70), false), TransferPurpose.SHARE) }
            compose.waitUntil(5_000) { it.transfers.state.value is TransferState.Running }
        }),
    )

    private fun save(ui: FileTransfersUi) {
        var launched: FileTransfers.PendingSave? = null
        compose.runOnIdle { ui.launchSave = { launched = it } }
        compose.onNodeWithTag("attachment_save:71").performClick()
        compose.runOnIdle { ui.transfers.saveChosen(launched!!) { ByteArrayOutputStream() } }
        compose.waitUntil(5_000) { ui.transfers.state.value !is TransferState.Running || hold }
    }

    private fun prepare(ui: FileTransfersUi, purpose: TransferPurpose) {
        compose.runOnIdle { ui.hand(AttachmentRef(pdf, false), purpose) }
        compose.waitUntil(5_000) { ui.transfers.state.value != null && (ui.transfers.state.value !is TransferState.Running || hold) }
    }

    private fun actions(messageId: Long) {
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.message_actions)
        compose.onNode(SemanticsMatcher("actions") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label } and
            hasAnyAncestor(hasTestTag("message:$messageId"))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("message_actions").fetchSemanticsNodes().isNotEmpty() }
    }

    private var hold = false

    @Test fun presentationLight() = presentation(dark = false, scales = listOf("default" to 1f, "font200" to 2f), only = null)
    @Test fun presentationDark() = presentation(dark = true, scales = listOf("default" to 1f), only = setOf("transfer-cards"))

    private fun presentation(dark: Boolean, scales: List<Pair<String, Float>>, only: Set<String>?) {
        val theme = if (dark) "dark" else "light"
        for ((scaleName, fontScale) in scales) for (shot in shots().filter { only == null || it.name in only }) {
            FontScaleTestActivity.requestedFontScale = fontScale
            hold = shot.script.hold
            val session = ReceivedFiles.session()
            val store = ViewModelStore()
            val images = ViewModelProvider.create(store, viewModelFactory {
                initializer { AttachmentImagesViewModel(session, FixedNetwork(), ReceivedSource(), 32L * 1024 * 1024) }
            })[AttachmentImagesViewModel::class.java]
            val rooms = ReceivedRooms(session).apply { messages = shot.messages }
            val room = ConversationViewModel(rooms)
            val root = Files.createTempDirectory("ccm-transfer-captures").toFile()
            val job = SupervisorJob()
            val gate = CompletableDeferred<Unit>()
            val source = FileSource { _, _, open, _ ->
                if (shot.script.preBody) return@FileSource shot.script.result
                open().use { out -> out.write(ByteArray(1_000_000)); if (shot.script.hold) runBlocking { gate.await() } }
                shot.script.result
            }
            val transfers = FileTransfers(CoroutineScope(Dispatchers.Main + job), session, source, ShareCopies(root), { shot.space })
            // The note shows only where it's the subject; everywhere else it was already continued past.
            val ui = FileTransfersUi(transfers, kotlinx.coroutines.flow.flowOf(shot.name != "transfer-note")) { }
            val viewerKey = ImageKey(session.value.server!!.value, 7, session.value.generation, false, 70)
            try {
                ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent { ChitChatTheme(dark = dark) { CompositionLocalProvider(LocalFileTransfers provides ui) {
                            Surface(Modifier.fillMaxSize().testTag("transfer_host"), color = MaterialTheme.colorScheme.background) {
                                val viewer = shot.viewer
                                if (viewer == null) ConversationScreen(room, 42, {}, images = images)
                                else ReceivedImageViewer(ViewerState(AttachmentRef(photo(70), false), viewerKey, "entry", null, viewer, animationUnavailable = false), null,
                                    longSender, "10/20/26 12:00 PM", null, "Photo from Bob", onClose = {}, onLoadFull = {})
                            }
                        } } }
                    }
                    if (shot.viewer == null) compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
                    shot.act(this, ui)
                    capture("files-$theme-$scaleName-${shot.name}", shot.node)
                }
            } finally { gate.complete(Unit); compose.runOnIdle { room.cancel() }; job.cancel(); store.clear(); root.deleteRecursively() }
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
