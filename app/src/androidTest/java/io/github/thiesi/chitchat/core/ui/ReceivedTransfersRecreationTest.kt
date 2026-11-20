package io.github.thiesi.chitchat.core.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.FileSource
import io.github.thiesi.chitchat.core.media.FileTransfers
import io.github.thiesi.chitchat.core.media.ShareCopies
import io.github.thiesi.chitchat.core.media.ShareProvider
import io.github.thiesi.chitchat.core.media.TransferState
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.file
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.OnCreateContentTestActivity
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * F5b (#318 review): an Activity recreated while the picker or the first-use note is open loses nothing. The host sets
 * its content in onCreate, so `recreate()` is a real configuration change; the test's own result registry stands in for
 * the system picker and outlives the Activity, as the real one does.
 */
class ReceivedTransfersRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = ReceivedFiles.session()
    private val store = ViewModelStore()
    private val images: AttachmentImagesViewModel = ViewModelProvider.create(store, viewModelFactory {
        initializer { AttachmentImagesViewModel(session, FixedNetwork(), ReceivedSource(), 32L * 1024 * 1024) }
    })[AttachmentImagesViewModel::class.java]
    private val rooms = ReceivedRooms(session).apply { messages = listOf(roomMessage(3, null, file(71, "notes.pdf"))) }
    private val room = ConversationViewModel(rooms)
    private val root = Files.createTempDirectory("ccm-transfer-recreation").toFile()
    private val job = SupervisorJob()
    private val streamed = mutableListOf<Long>()
    private val bytes = "the saved file".toByteArray()
    private val source = FileSource { ref, _, open, _ ->
        synchronized(streamed) { streamed += ref.attachment.id }
        open().use { it.write(bytes) }
        DownloadResult.Saved
    }
    private val transfers = FileTransfers(CoroutineScope(Dispatchers.Main + job), session, source, ShareCopies(root), { Long.MAX_VALUE })
    /** Activity-scoped in the app (a ViewModel), so the test keeps the one instance across the recreation too. */
    private val noteSeen = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val ui = FileTransfersUi(transfers, noteSeen) { noteSeen.value = true }

    /** Records the launch; the test delivers the result after the recreation. */
    private val registry = object : ActivityResultRegistry() {
        var launched: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched = requestCode
        }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    @After fun tearDown() { OnCreateContentTestActivity.content = null; room.cancel(); job.cancel(); store.clear(); root.deleteRecursively() }

    private fun host(seen: Boolean) {
        noteSeen.value = seen
        OnCreateContentTestActivity.content = {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                FileTransfersHost(ui) { MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(room, 42, {}, images = images) } } }
            }
        }
    }

    @Test fun aSavePickedAcrossARecreationStillStreamsIntoTheChosenDocument() {
        host(seen = true)
        // A writable content: URI the picker could have returned (our own provider, in our own process).
        val document = java.io.File(ShareProvider.root(context), "picked/notes.pdf").apply { parentFile!!.mkdirs(); writeText("") }
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            compose.onNodeWithTag("attachment_save:71").performClick()
            compose.waitUntil(5_000) { registry.launched != null }
            assertNotNull(transfers.pendingSave)
            scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment_save:71").fetchSemanticsNodes().isNotEmpty() }
            // The picker answers the new Activity.
            scenario.onActivity {
                registry.dispatchResult(registry.launched!!, Activity.RESULT_OK, Intent().setData(ShareProvider.uriFor(context, document)))
            }
            compose.waitUntil(5_000) { transfers.state.value is TransferState.Saved }
            assertEquals(listOf(71L), streamed)
            assertEquals(String(bytes), document.readText())
            assertFalse("Nothing stays busy", transfers.busy)
            assertNull(transfers.pendingSave)
        }
        document.parentFile!!.deleteRecursively()
    }

    @Test fun anOpenNoAppHandlesDeletesTheCopyAndSaysSo() {
        // #318 review: the handoff starts the other app first; with none for the type, the copy goes and the line shows.
        rooms.messages = listOf(roomMessage(3, null, file(74, "data.ccmx", mime = "application/x-ccm-unknown")))
        host(seen = true)
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use {
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            compose.onNodeWithTag("attachment_open:74").performClick()
            compose.waitUntil(5_000) { (transfers.state.value as? TransferState.Stopped)?.reason == io.github.thiesi.chitchat.core.media.TransferStop.NO_APP }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer_level_1").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("The copy is deleted", root.walk().none { it.isFile })
            assertFalse(transfers.busy)
        }
    }

    @Test fun theFirstUseNoteSurvivesARecreationAndItsCancelStillStartsNothing() {
        host(seen = false)
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            compose.onNodeWithTag("attachment_open:71").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer_note").fetchSemanticsNodes().isNotEmpty() }
            scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer_note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("transfer_note_cancel").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("transfer_note").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                assertNull(transfers.state.value)
                assertFalse(transfers.busy)
                assertTrue(streamed.isEmpty() && root.walk().none { it.isFile })
                assertNull(registry.launched)
            }
        }
    }
}
