package io.github.thiesi.chitchat.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.ImageState
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.OnCreateContentTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

/**
 * Received files F4b: a real configuration change keeps the viewer open (TL condition 3). The host sets its content in
 * onCreate, so `recreate()` is a genuine recreation with `isChangingConfigurations` set, which is what keeps the screen's
 * departure from closing the viewer (#313: a test-side recomposition alone isn't a configuration change).
 */
class ReceivedViewerRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val session = ReceivedFiles.session()
    private val source = ReceivedSource()
    private val store = ViewModelStore()
    private val images: AttachmentImagesViewModel = ViewModelProvider.create(store, viewModelFactory {
        initializer { AttachmentImagesViewModel(session, FixedNetwork(), source, 32L * 1024 * 1024) }
    })[AttachmentImagesViewModel::class.java]
    private val rooms = ReceivedRooms(session)
    private val room = ConversationViewModel(rooms)

    @After fun tearDown() { OnCreateContentTestActivity.content = null; room.cancel(); store.clear() }

    @Test fun aRecreationKeepsTheViewerOpen() {
        rooms.messages = listOf(roomMessage(3, null, photo(70)))
        OnCreateContentTestActivity.content = {
            MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(room, 42, {}, images = images) } }
        }
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            compose.waitUntil(5_000) { images.images.states.value.values.any { it is ImageState.Loaded } }
            compose.onNodeWithTag("attachment_frame:70").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer").fetchSemanticsNodes().isNotEmpty() }
            var before: OnCreateContentTestActivity? = null
            scenario.onActivity { before = it }
            scenario.recreate()
            scenario.onActivity { assertNotSame("A new Activity instance", before, it) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(70L, images.viewer.state.value?.ref?.attachment?.id)
        }
    }
}
