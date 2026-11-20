package io.github.thiesi.chitchat.core.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.compose.ui.platform.AbstractComposeView
import androidx.core.view.children
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.thiesi.chitchat.testing.performNativeTap
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.media.AttachmentImagesViewModel
import io.github.thiesi.chitchat.core.media.DownloadResult
import io.github.thiesi.chitchat.core.media.FileTransfers
import io.github.thiesi.chitchat.core.media.FullImage
import io.github.thiesi.chitchat.core.media.ImageState
import io.github.thiesi.chitchat.core.media.NetworkState
import io.github.thiesi.chitchat.core.media.ShareCopies
import io.github.thiesi.chitchat.core.media.TransferPurpose
import io.github.thiesi.chitchat.core.media.TransferState
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.photo
import io.github.thiesi.chitchat.core.ui.ReceivedFiles.roomMessage
import io.github.thiesi.chitchat.feature.conversation.ConversationScreen
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.testing.OnCreateContentTestActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/**
 * Received files F6: a single tap hides or shows the viewer's chrome (PL spec and TL conditions of 2026-11-20). The host
 * sets its content in onCreate, so `recreate()` is a genuine configuration change.
 */
class ReceivedViewerChromeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val session = ReceivedFiles.session()
    private val source = ReceivedSource()
    private val network = FixedNetwork()
    private val store = ViewModelStore()
    private val images: AttachmentImagesViewModel = ViewModelProvider.create(store, viewModelFactory {
        initializer { AttachmentImagesViewModel(session, network, source, 32L * 1024 * 1024) }
    })[AttachmentImagesViewModel::class.java]
    private val rooms = ReceivedRooms(session)
    private val room = ConversationViewModel(rooms)
    private val assistive = mutableStateOf<Boolean?>(false)
    private var fade: Boolean? = false
    private var transfersUi: FileTransfersUi? = null
    private val chrome = listOf("viewer_close", "viewer_top", "viewer_bottom", "viewer_zoom", "viewer_caption", "viewer_save", "viewer_share")

    @After fun tearDown() {
        OnCreateContentTestActivity.content = null; room.cancel(); store.clear()
    }

    /**
     * Sends [code] (down, then up) straight to the viewer's Compose view and returns whether it was consumed. Never through
     * the instrumentation: a real key event takes the whole device out of touch mode for the rest of the run.
     */
    private fun key(code: Int): Boolean {
        var consumed: Boolean? = null
        Espresso.onView(ViewMatchers.isRoot()).inRoot(RootMatchers.isDialog()).perform(object : ViewAction {
            override fun getConstraints() = ViewMatchers.isRoot()
            override fun getDescription() = "dispatch key $code to the viewer's Compose view"
            override fun perform(uiController: UiController, view: View) {
                val target = generateSequence(listOf(view)) { level -> level.flatMap { (it as? ViewGroup)?.children?.toList().orEmpty() }.takeIf { it.isNotEmpty() } }
                    .flatten().first { it is AbstractComposeView }
                val down = target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                val up = target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                consumed = down || up
            }
        })
        return consumed!!
    }

    private fun text(id: Int) = context.getString(id)
    private fun present(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun viewerOpen() = present("viewer")
    private fun chromeGone() = chrome.none(::present)
    private fun image() = compose.onNodeWithTag("viewer_image")
    private fun clickLabel() = image().fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
    private fun hideAction() = image().fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
        ?.firstOrNull { it.label == text(R.string.viewer_hide_controls) }
    private fun tapImage() = image().performTouchInput { click(center) }
    private fun hide() { tapImage(); compose.waitUntil(5_000) { chromeGone() } }
    /** A fixed wait past the double-tap window, so an absent toggle is really absent (never a retry). */
    private fun pastDoubleTap() {
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() * 2L)
        compose.mainClock.advanceTimeBy(ViewConfiguration.getDoubleTapTimeout() * 2L)
        compose.waitForIdle()
    }

    /** What TalkBack and the accessibility node tree see: a cleared node's children are gone from the merged tree only. */
    private fun mergedChromeGone() = chrome.none { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Keyboard mode for [block] (TL, #320): its real keys leave touch mode, the only mode where Compose gives buttons input
     * focus. A real, system-routed tap in finally re-enters touch mode, on the image's centre, first confirmed to be outside
     * every piece of chrome (a no-op toggle, nothing that launches). Nothing is asserted after it but the per-case
     * unchanged-touch-mode check. A failing restore fails the case, never retried; after a failure it's attached to it.
     */
    private fun withRealKeys(block: () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (t: Throwable) { failure = t; throw t } finally {
            try { restoreTouchMode() } catch (t: Throwable) { failure?.addSuppressed(t) ?: throw t }
        }
    }

    private fun restoreTouchMode() {
        val centre = image().fetchSemanticsNode().boundsInWindow.center
        for (tag in listOf("viewer_top", "viewer_bottom", "viewer_zoom")) compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .forEach { check(!it.boundsInWindow.contains(centre)) { "The restoring tap would land on $tag" } }
        image().performNativeTap()
    }

    /** Returns Unit, so every `@Test fun … = withViewer { }` stays a void method whatever its block ends with. */
    private fun withViewer(caption: String? = "Harbour at dusk", beforeOpen: () -> Unit = {},
        block: (ActivityScenario<OnCreateContentTestActivity>) -> Unit) {
        rooms.messages = listOf(roomMessage(3, caption, photo(70)))
        OnCreateContentTestActivity.content = {
            CompositionLocalProvider(LocalAssistiveInput provides assistive.value, LocalChromeFade provides fade,
                LocalFileTransfers provides transfersUi) {
                MaterialTheme { Box(Modifier.size(320.dp, 640.dp)) { ConversationScreen(room, 42, {}, images = images) } }
            }
        }
        ActivityScenario.launch(OnCreateContentTestActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) { room.state.value.loaded && !room.state.value.busy }
            compose.waitUntil(5_000) { images.images.states.value.values.any { it is ImageState.Loaded } }
            fun touchMode(): Boolean { var mode = false; scenario.onActivity { mode = it.window.decorView.isInTouchMode }; return mode }
            val touchBefore = touchMode()
            beforeOpen()
            compose.onNodeWithTag("attachment_frame:70").performClick()
            compose.waitUntil(5_000) { viewerOpen() && present("viewer_close") }
            block(scenario)
            // TL (#320): no case here may change touch mode (injected keys once broke AuthJourneyTest's keyboard handling
            // later in the run). Fail here, loudly, rather than in another class.
            assertEquals("Touch mode unchanged by this case", touchBefore, touchMode())
        }
    }

    @Test fun aTapHidesTheChromeEntirelyAndTheImageKeepsTheWholeWindow() = withViewer {
        val before = image().fetchSemanticsNode().boundsInWindow
        hide()
        assertTrue("No chrome node in the tree", chromeGone())
        val window = compose.onNodeWithTag("viewer").fetchSemanticsNode().boundsInWindow
        assertEquals("Hidden, the image fills the window", window, image().fetchSemanticsNode().boundsInWindow)
        assertEquals("The image didn't move or rescale", before, image().fetchSemanticsNode().boundsInWindow)
        tapImage()
        compose.waitUntil(5_000) { present("viewer_close") && present("viewer_caption") }
    }

    @Test fun aTapOnAControlKeepsTheChromeAndATapOnTheSenderOrCaptionTextToggles() = withViewer {
        // PL and TL: a tap anywhere that isn't a control toggles; controls keep their own taps.
        compose.onNodeWithTag("viewer_caption_toggle").performTouchInput { click(center) }
        pastDoubleTap()
        assertTrue("More keeps its tap", present("viewer_close"))
        compose.onNodeWithTag("viewer_caption_toggle").performTouchInput { click(center) } // Less
        pastDoubleTap()
        compose.onNodeWithTag("viewer_zoom_in").performTouchInput { click(center) }
        pastDoubleTap()
        assertTrue("Zoom in keeps its tap", present("viewer_close"))
        compose.onNodeWithTag("viewer_zoom_reset").performTouchInput { click(center) }
        pastDoubleTap()
        // The sender and time are plain content on the top bar's scrim: a tap there hides the chrome.
        compose.onNodeWithTag("viewer_top").performTouchInput { click(center) }
        compose.waitUntil(5_000) { chromeGone() }
        tapImage()
        compose.waitUntil(5_000) { present("viewer_close") }
        // So is a collapsed caption's text.
        compose.onNodeWithTag("viewer_caption").performTouchInput { click(center) }
        compose.waitUntil(5_000) { chromeGone() }
    }

    @Test fun aTapOnADisabledSaveDoesNotToggle() {
        // Offline at opening, Save and Share are disabled; a disabled control still keeps its tap (PL and TL).
        val copies = Files.createTempDirectory("ccm-chrome-save").toFile()
        val job = SupervisorJob()
        transfersUi = FileTransfersUi(FileTransfers(CoroutineScope(Dispatchers.Main + job), session, { _, _, _, _ -> DownloadResult.Gone },
            ShareCopies(copies), { Long.MAX_VALUE }), flowOf(true)) { }
        try {
            withViewer(beforeOpen = { network.state.value = NetworkState(online = false, metered = true) }) {
                compose.waitUntil(5_000) { images.viewer.state.value?.full == FullImage.OFFLINE }
                compose.onNodeWithTag("viewer_save").assertIsNotEnabled().performTouchInput { click(center) }
                pastDoubleTap()
                assertTrue("A tap on a disabled Save doesn't toggle", present("viewer_close"))
            }
        } finally { job.cancel(); copies.deleteRecursively(); transfersUi = null }
    }

    @Test fun aDoubleTapZoomsAndPanAndPinchNeverToggle() = withViewer {
        image().performTouchInput { doubleClick(center) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("viewer_zoom_out").assertIsEnabled() }.isSuccess }
        pastDoubleTap()
        assertTrue("A double-tap zooms and never toggles", present("viewer_close"))
        compose.onNodeWithTag("viewer_zoom_reset").performClick()
        image().performTouchInput { swipe(center, center + Offset(120f, 0f), 300) }
        pastDoubleTap()
        assertTrue("A pan at fit scale doesn't toggle", present("viewer_close") && viewerOpen())
        image().performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(160f, 0f), center + Offset(40f, 0f), center + Offset(160f, 0f), 300)
        }
        pastDoubleTap()
        assertTrue("A pinch doesn't toggle", present("viewer_close"))
        compose.onNodeWithTag("viewer_zoom_out").assertIsEnabled()
        compose.onNodeWithTag("viewer_zoom_reset").performClick()
        image().performTouchInput { longClick(center) }
        pastDoubleTap()
        assertTrue("A long-press doesn't toggle (PL)", present("viewer_close"))
    }

    @Test fun showControlsAndHideControlsAreTheImagesOwnActions() = withViewer {
        assertNull("No click label while shown", clickLabel())
        val hideIt = hideAction()
        assertTrue("Shown and hideable: Hide controls", hideIt != null)
        compose.runOnUiThread { hideIt!!.action() }
        compose.waitUntil(5_000) { chromeGone() }
        assertEquals(text(R.string.viewer_show_controls), clickLabel())
        image().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { present("viewer_close") }
        // Touch mode: buttons take no input focus (the platform's rule; a service moves its own accessibility focus). Nothing
        // focused is left among the chrome or on the image, and the image stops being focusable once shown.
        val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        assertTrue("No focus left on the chrome or the image: $focused", focused.none { it in chrome || it == "viewer_image" })
        assertTrue("The image isn't focusable once shown", !image().fetchSemanticsNode().config.contains(SemanticsProperties.Focused))
    }

    @Test fun inKeyboardModeShowControlsPutsFocusOnClose() = withViewer {
        withRealKeys {
            // A keyboard user: a real key while shown leaves touch mode; then Hide and Show through the image's actions.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
            val hideIt = hideAction()!!
            compose.runOnUiThread { hideIt.action() }
            compose.waitUntil(5_000) { chromeGone() }
            image().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { present("viewer_close") }
            compose.onNodeWithTag("viewer_close").assertIsFocused()
        }
    }

    @Test fun assistiveInputKeepsTheChromeShownAndTurningItOnShowsItAtOnce() = withViewer {
        assistive.value = true
        compose.waitForIdle()
        tapImage()
        pastDoubleTap()
        assertTrue("A tap doesn't hide with TalkBack on", present("viewer_close"))
        assertNull("No Hide controls with assistive input", hideAction())
        assistive.value = false
        hide()
        assistive.value = true
        compose.waitUntil(5_000) { present("viewer_close") }
    }

    @Test fun aRunningTransferKeepsTheChromeShownAndItsResultKeepsIt() {
        val gate = CompletableDeferred<Unit>()
        val copies = Files.createTempDirectory("ccm-chrome").toFile()
        val job = SupervisorJob()
        val transfers = FileTransfers(CoroutineScope(Dispatchers.Main + job), session,
            { _, _, _, _ -> runBlocking { gate.await() }; DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) },
            ShareCopies(copies), { Long.MAX_VALUE })
        transfersUi = FileTransfersUi(transfers, flowOf(true)) { }
        try {
            withViewer {
                hide()
                val ref = images.viewer.state.value!!.ref
                compose.runOnIdle { transfersUi!!.hand(ref, TransferPurpose.SHARE) }
                compose.waitUntil(5_000) { transfers.state.value is TransferState.Running }
                compose.waitUntil(5_000) { present("viewer_close") }
                assertNull("No Hide controls while a transfer runs", hideAction())
                tapImage()
                pastDoubleTap()
                assertTrue("A tap doesn't hide while a transfer runs", present("viewer_close"))
                gate.complete(Unit)
                compose.waitUntil(5_000) { transfers.state.value is TransferState.Stopped }
                compose.waitForIdle()
                assertTrue("The result keeps the chrome shown", present("viewer_close"))
                hide()
            }
        } finally { gate.complete(Unit); job.cancel(); copies.deleteRecursively(); transfersUi = null }
    }

    @Test fun aNewStateLineWhileHiddenShowsTheChrome() {
        // Offline is decided at opening or at Load full (F4), so the line that can appear while hidden is a running load's:
        // the original is loading when the chrome hides, then fails.
        val gate = CompletableDeferred<Unit>()
        source.serve(70)
        source.reply(70) { gate.await(); DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }
        withViewer {
            compose.onNodeWithTag("viewer_load_full").performClick()
            compose.waitUntil(5_000) { images.viewer.state.value?.full == FullImage.LOADING }
            hide()
            gate.complete(Unit)
            compose.waitUntil(5_000) { images.viewer.state.value?.full == FullImage.FAILED }
            compose.waitUntil(5_000) { present("viewer_close") && present("viewer_line") }
        }
    }

    @Test fun aKeyWhileHiddenShowsTheChromeWithFocusOnClose() = withViewer {
        hide()
        withRealKeys {
            // A real Tab, as a keyboard user's: it leaves touch mode, where Close can take input focus.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.waitUntil(5_000) { present("viewer_close") }
            compose.onNodeWithTag("viewer_close").assertIsFocused()
            assertTrue("The key only showed the chrome", viewerOpen())
        }
    }

    @Test fun aKeyWhileHiddenIsConsumed() = withViewer {
        hide()
        assertTrue("Tab is consumed while hidden", key(KeyEvent.KEYCODE_TAB))
        compose.waitUntil(5_000) { present("viewer_close") }
    }

    @Test fun aSystemKeyWhileHiddenPassesThroughAndChangesNothing() = withViewer {
        hide()
        // The viewer's own answer, and no system volume changes.
        assertEquals("Volume up isn't consumed", false, key(KeyEvent.KEYCODE_VOLUME_UP))
        compose.waitForIdle()
        assertTrue("The chrome stays hidden", chromeGone())
    }

    @Test fun escWhileHiddenClosesTheViewer() = withViewer {
        hide()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5_000) { !viewerOpen() }
    }

    @Test fun backWhileHiddenClosesTheViewer() = withViewer {
        hide()
        Espresso.pressBack()
        compose.waitUntil(5_000) { !viewerOpen() }
    }

    @Test fun aRotationKeepsHiddenWithNoLineAndShowsWithOne() {
        source.serve(70)
        source.reply(70) { DownloadResult.Failed(ApiFailure(FailureKind.NETWORK)) }
        withViewer { scenario -> rotation(scenario) }
    }

    private fun rotation(scenario: ActivityScenario<OnCreateContentTestActivity>) {
        hide()
        scenario.recreate()
        compose.waitUntil(5_000) { viewerOpen() }
        compose.waitForIdle()
        assertTrue("Hidden survives a rotation with no line", chromeGone())
        tapImage()
        compose.waitUntil(5_000) { present("viewer_close") }
        compose.onNodeWithTag("viewer_load_full").performClick()
        compose.waitUntil(5_000) { images.viewer.state.value?.full == FullImage.FAILED && present("viewer_line") }
        // A line present when hiding doesn't prevent it; a rotation with the line on screen shows the chrome again.
        hide()
        scenario.recreate()
        compose.waitUntil(5_000) { viewerOpen() && present("viewer_close") && present("viewer_line") }
    }

    @Test fun aNewOpeningStartsShown() = withViewer {
        hide()
        Espresso.pressBack()
        compose.waitUntil(5_000) { !viewerOpen() }
        compose.onNodeWithTag("attachment_frame:70").performClick()
        compose.waitUntil(5_000) { viewerOpen() }
        compose.waitForIdle()
        assertTrue(present("viewer_close"))
    }

    @Test fun withTheFadeTheSemanticsAreGoneAsTheExitStarts() {
        // CI runs with animations off (the instant path, every other case here); this one runs the 150 ms fade.
        fade = true
        withViewer {
            val hideIt = hideAction()!!
            // The merged tree is what TalkBack reads; the fading content stays in the unmerged tree until it leaves (#320).
            assertTrue("Close is in the merged tree before the hide", compose.onAllNodesWithTag("viewer_close").fetchSemanticsNodes().isNotEmpty())
            compose.mainClock.autoAdvance = false
            try {
                // Apply the change now, so the paused clock's next frame is the exit's first (#320).
                compose.runOnUiThread { hideIt.action(); Snapshot.sendApplyNotifications() }
                compose.mainClock.advanceTimeByFrame()
                val firstFrame = mergedChromeGone()
                if (!firstFrame) {
                    // Evidence only, still a failure: does the next frame clear it?
                    compose.mainClock.advanceTimeByFrame()
                    throw AssertionError("Not cleared at the exit's first frame (second frame: ${if (mergedChromeGone()) "cleared" else "not cleared"})")
                }
                compose.mainClock.advanceTimeBy(50)
                assertTrue("Still mid-fade, still gone", mergedChromeGone())
                compose.mainClock.advanceTimeBy(200)
                assertTrue(mergedChromeGone())
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForIdle()
            assertTrue(chromeGone())
        }
    }
}
