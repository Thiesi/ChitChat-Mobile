package io.github.thiesi.chitchat.feature.completion

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.mentions.*
import io.github.thiesi.chitchat.core.network.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.core.ui.MessageInput
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.*
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

/** Original root/VM consumers and one system input tap. Synthetic server facts, no live/device acceptance. */
class CompletionJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val sessions=TestSessions()
    private val api=NativeChatFixtures(sessions)
    private val mentions=Mentions()
    private lateinit var model: CompletionViewModel
    private lateinit var room: ConversationViewModel
    private lateinit var thread: DirectThreadViewModel
    private lateinit var notices: NotificationCenterViewModel
    private lateinit var targets: MessageTargetViewModel
    private val constrained=mutableStateOf(false)
    private val largeFont=mutableStateOf(false)
    private val completionEnabled=mutableStateOf(true)
    @Volatile private var ime=0
    private val settingsStore=object: SettingsStore {
        override val settings=kotlinx.coroutines.flow.MutableStateFlow(AppSettings(server=(ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value=settings.value.copy(server=server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value=settings.value.copy(theme=theme) }
    }
    @Before fun bind() { compose.activityRule.scenario.onActivity(::install) }
    private fun install(activity: ComponentActivity) {
        val args=InstrumentationRegistry.getArguments();val config=activity.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(),config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(),config.fontScale,0.01f) }
        activity.enableEdgeToEdge()
        model=ViewModelProvider(activity,CompletionViewModel.factory(mentions))[CompletionViewModel::class.java]
        room=ViewModelProvider(activity,ConversationViewModel.factory(api.rooms))[ConversationViewModel::class.java]
        thread=ViewModelProvider(activity,DirectThreadViewModel.factory(api.directs))[DirectThreadViewModel::class.java]
        val inbox=ViewModelProvider(activity,DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
        notices=ViewModelProvider(activity,NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
        targets=ViewModelProvider(activity,MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        val settings=ViewModelProvider(activity,SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
        val auth=ViewModelProvider(activity,AuthViewModel.factory(sessions))[AuthViewModel::class.java]
        activity.setContent {
            val density=LocalDensity.current
            val bottom=WindowInsets.ime.getBottom(density);SideEffect { ime=bottom }
            CompositionLocalProvider(LocalDensity provides Density(density.density,if (largeFont.value) 2f else density.fontScale)) {
                // This bounds the whole component viewport, including production IME padding.
                // A240dp total height could be consumed entirely by a normal phone keyboard.
                Box(if (constrained.value) Modifier.widthIn(max=360.dp).fillMaxWidth().height(640.dp) else Modifier.fillMaxSize()) {
                    TestChitChatApp(settings,auth,conversationModel=room,directInbox=inbox,directThread=thread,
                        notifications=notices,messageTargets=targets,completion=if (completionEnabled.value) model else null)
                }
            }
        }
    }
    private fun text(id: Int)=InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun enter(roomTarget: Boolean=true) {
        compose.waitUntil(5_000) { sessions.state.value.phase==SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer","fixture-password") }
            sessions.state.value=sessions.state.value.copy(chatPolicy=api.policy,privacy=api.privacy)
            runBlocking { api.directs.send(8,"Completion fixture",null) }
        }
        compose.onNodeWithText(text(R.string.app_name)).performNativeTap()
        compose.onNodeWithTag("notifications_open").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
        val notice=if (roomTarget) 60 else 55
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:$notice"))
        compose.onNodeWithTag("notification_view:$notice").performNativeTap()
        if (roomTarget) {
            compose.waitUntil(5_000) { room.state.value.let { it.roomId==43L && it.loaded && !it.busy } && targets.state.value.request==null }
            compose.onNodeWithContentDescription(text(R.string.conversation_details)).performNativeTap()
            compose.onNode(hasText(text(R.string.conversation_latest)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
            compose.waitUntil(5_000) { room.state.value.let { it.latestWindow && !it.busy && !it.reading && !it.stale } }
        } else compose.waitUntil(5_000) { thread.state.value.let { it.peerId==8L && it.loaded && !it.busy && !it.stale } }
        compose.waitForIdle();compose.runOnIdle { if (completionEnabled.value) assertNotNull(model.state.value.pane) }
    }
    private fun type(body: String,roomTarget: Boolean=true,caret: Int?=null) {
        val input=compose.tapNativeInput(if (roomTarget) "conversation_composer" else "dm_composer",{ime})
        input.assertHeightIsAtLeast(48.dp).performTextClearance();input.performTextInput(body)
        if (caret!=null) input.performTextInputSelection(TextRange(caret))
    }
    private fun loaded() { compose.waitUntil(5_000) { model.state.value.let { it.selection!=null && !it.loading } } }
    private fun details() {
        compose.onNodeWithContentDescription(text(R.string.completion_details)).assertIsDisplayed().performNativeTap()
        compose.onNode(hasTestTag("completion_choices") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }
    private fun choose(index: Int=0) {
        compose.onNode(hasTestTag("completion_candidate:$index") and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performNativeTap()
        compose.onNode(isDialog()).assertDoesNotExist()
    }
    private fun characters(tag: String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun tab(shift: Boolean=false) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if (!shift) instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
        else {
            val stamp=android.os.SystemClock.uptimeMillis()
            instrumentation.sendKeySync(android.view.KeyEvent(stamp,stamp,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_TAB,0,android.view.KeyEvent.META_SHIFT_ON))
            instrumentation.sendKeySync(android.view.KeyEvent(stamp,stamp,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_TAB,0,android.view.KeyEvent.META_SHIFT_ON))
        }
        compose.waitForIdle()
    }
    @After fun cleanup() {
        mentions.gate?.complete(Unit);compose.waitForIdle()
        if (ime>0 || nativeInputWindow().imeVisible!=false) Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { ime==0 };assertEquals(false,nativeInputWindow().imeVisible)
    }

    @Test fun roomChoiceReplacesOnlyTheCaretTokenAndPreservesEverySuffixCharacter() {
        enter();type("Hi @AnTAIL\nsecond",caret=6);loaded();details()
        compose.waitUntil(5_000) { !room.state.value.atLatest }
        compose.runOnIdle { assertEquals("Hi @AnTAIL\nsecond",room.state.value.draft);assertEquals(0,api.rooms.sends) }
        choose();compose.runOnIdle { assertEquals("Hi @Ann TAIL\nsecond",room.state.value.draft);assertEquals(0,api.rooms.sends) }
        assertEquals("Hi @Ann TAIL\nsecond",characters("conversation_composer"))
    }
    @Test fun roomBroadcastIsAnExplicitLiteralChoiceWithoutSendOrNotificationPosts() {
        enter();type("@ro");loaded()
        compose.runOnIdle { assertTrue(model.state.value.candidates.single() is CompletionCandidate.Broadcast) }
        details();choose();compose.runOnIdle {
            assertEquals("@room ",room.state.value.draft);assertEquals(0,api.rooms.sends);assertEquals(0,api.notices.posts)
        }
    }
    @Test fun directCompletionUsesOnlyTheCurrentPeerAndMakesNoRoomLookup() {
        enter(false);type("@Pe",false);loaded();details();choose()
        compose.runOnIdle {
            assertEquals("@Peer 8 ",thread.state.value.draft);assertTrue(mentions.calls.isEmpty());assertEquals(1,api.directs.sends)
        }
        assertEquals("@Peer 8 ",characters("dm_composer"))
    }
    @Test fun completionKeepsUnknownDirectDeliveryAfterPassiveRefreshWithoutConsent() {
        enter(false);compose.runOnIdle {
            thread.updateDraft("Unconfirmed fixture");api.directs.sendFailure=ApiFailure(FailureKind.NETWORK);thread.send(sessions.state.value,8)
        }
        compose.waitUntil(5_000) { thread.state.value.let { it.uncertainSend && !it.busy } }
        // The failed POST intentionally leaves data stale. A passive invalidation GET
        // refreshes the pane without granting the explicit history-check consent.
        compose.runOnIdle { thread.invalidate() }
        compose.waitUntil(5_000) { thread.state.value.let { it.loaded && !it.stale && !it.busy } }
        compose.runOnIdle { assertTrue(thread.state.value.uncertainSend);assertFalse(thread.state.value.checkedUnknown) }
        type("@PeTAIL",false,3);loaded();details();choose()
        compose.runOnIdle {
            assertEquals("@Peer 8 TAIL",thread.state.value.draft);assertTrue(thread.state.value.uncertainSend)
            assertFalse(thread.state.value.checkedUnknown);assertEquals(2,api.directs.sends)
        }
        compose.onNodeWithTag("dm_send").assertIsNotEnabled()
    }
    @Test fun constrainedLargeFontRoomKeepsTheInputSendAnd48DpDetailsChoicesReachable() {
        compose.runOnIdle { constrained.value=true;largeFont.value=true };enter();type("@An");loaded()
        compose.onNodeWithTag("conversation_composer").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("conversation_send").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag("completion_choices").assertCountEquals(0)
        details();choose();assertEquals("@Ann ",characters("conversation_composer"))
    }
    @Test fun roomReplySelectionSurvivesExplicitCompletionInConstrainedDetails() {
        compose.runOnIdle { constrained.value=true };enter()
        var reply: io.github.thiesi.chitchat.core.conversation.ComposerReply?=null
        compose.runOnIdle {
            val original=room.state.value.messages.first { !it.deleted }
            assertTrue(room.replyTo(sessions.state.value,43,original));reply=room.state.value.composerReply
        }
        type("@An");loaded();details();choose()
        compose.runOnIdle { assertSame(reply,room.state.value.composerReply);assertEquals("@Ann ",room.state.value.draft);assertEquals(0,api.rooms.sends) }
    }
    @Test fun aLongNameAt200PercentKeepsItsFullAccessibleLabelAndBounded48DpChoice() {
        compose.runOnIdle { largeFont.value=true };enter();type("@L");loaded();details()
        val button=compose.onNode(hasTestTag("completion_candidate:0") and hasAnyAncestor(isDialog()))
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertContentDescriptionEquals("@"+longName)
        val bounds=button.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width<=compose.activity.resources.displayMetrics.widthPixels)
        choose();assertEquals("@${longName} ",characters("conversation_composer"))
    }
    @Test fun hardwareTabCyclesLocalNamesWithoutQueryingOrSendingTheDraft() {
        enter();type("An");tab();assertEquals("Ann: ",characters("conversation_composer"))
        tab();assertEquals("Andy: ",characters("conversation_composer"))
        tab();assertEquals("Ann: ",characters("conversation_composer"))
        compose.runOnIdle { assertTrue(mentions.calls.none { it.second.limit==8 });assertEquals(0,api.rooms.sends) }
    }
    @Test fun shiftTabLeavesAMatchingPrefixUntouchedAndKeepsNormalFocusTraversal() {
        enter();type("An");tab(true);assertEquals("An",characters("conversation_composer"))
        compose.onNodeWithTag("conversation_composer").assertIsNotFocused()
        compose.runOnIdle { assertEquals("An",room.state.value.draft);assertEquals(0,api.rooms.sends);assertNull(model.state.value.selection) }
    }
    @Test fun anUnmatchedTabKeepsTheDraftAndItsNormalFocusTraversal() {
        enter();type("NoMatch");tab();assertEquals("NoMatch",characters("conversation_composer"))
        compose.onNodeWithTag("conversation_composer").assertIsNotFocused()
        compose.runOnIdle { assertEquals("NoMatch",room.state.value.draft);assertEquals(0,api.rooms.sends) }
    }
    @Test fun hiddenSuggestionsLeaveEnterAvailableForANewline() {
        compose.runOnIdle { constrained.value=true;largeFont.value=true };enter();type("@An");loaded()
        compose.onAllNodesWithTag("completion_choices").assertCountEquals(0)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        compose.waitForIdle()
        assertEquals("@An\n",characters("conversation_composer"))
        compose.runOnIdle { assertEquals("@An\n",room.state.value.draft);assertNull(model.state.value.selection);assertEquals(0,api.rooms.sends) }
    }
    @Test fun visibleComponentKeysSelectAndDismissWithoutSending() {
        // An explicitly inline component fixture covers key routing independently of the
        // room's adaptive Details layout. This is not full-screen/layout acceptance.
        val draft=mutableStateOf("")
        compose.runOnIdle { runBlocking { sessions.signIn("Viewer","fixture-password") } }
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val density=LocalDensity.current
            val bottom=WindowInsets.ime.getBottom(density);SideEffect { ime=bottom }
            LaunchedEffect(Unit) { model.foreground();model.navigate("hardware-component") }
            androidx.compose.material3.MaterialTheme {
                val owner by sessions.state.collectAsState()
                val composer=rememberComposerCompletion(model,owner,CompletionTarget.Room(43),"hardware-component",draft.value,
                    currentBody={draft.value},canEdit={true},canOffer={true},write={draft.value=it;true},onTextChanged={})
                Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    CompletionSuggestions(composer)
                    MessageInput(composer.value,composer::input,true,"Message",false,
                        Modifier.fillMaxWidth().onPreInterceptKeyBeforeSoftKeyboard(composer::key).testTag("completion_component_composer"))
                }
            }
        } }
        compose.waitUntil(5_000) { model.state.value.pane?.entryId=="hardware-component" }
        val input=compose.tapNativeInput("completion_component_composer",{ime})
        input.performTextInput("@An");loaded()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);compose.waitForIdle()
        compose.onNodeWithTag("completion_candidate:1").assertIsDisplayed().assertIsSelected()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);compose.waitForIdle()
        assertEquals("@Andy ",characters("completion_component_composer"))
        input.assertIsFocused().performTextClearance();input.performTextInput("@An");loaded()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ESCAPE);compose.waitForIdle()
        assertEquals("@An",characters("completion_component_composer"))
        compose.runOnIdle { assertNull(model.state.value.selection);assertEquals(0,api.rooms.sends) }
    }
    @Test fun genuineBackgroundRetiresLateCandidatesWithoutReplayingThePrefixOnResume() {
        mentions.gate=CompletableDeferred();mentions.ignoreCancellation=true;enter();type("@An")
        compose.waitUntil(5_000) { mentions.calls.any { it.second.limit==8 && it.second.prefix=="An" } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);mentions.gate!!.complete(Unit)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.runOnIdle {
            assertNull(model.state.value.selection);assertEquals("@An",room.state.value.draft)
            assertEquals(1,mentions.calls.count { it.second.limit==8 && it.second.prefix=="An" })
        }
    }
    @Test fun failedLookupNeedsOneExplicitFreshGetAndNeverSendsTheDraft() {
        enter();mentions.fail=true;type("@An");loaded();assertTrue(model.state.value.failed);details()
        compose.runOnIdle { mentions.fail=false }
        compose.onNode(hasText(text(R.string.completion_retry)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
        loaded();choose();compose.runOnIdle {
            assertEquals("@Ann ",room.state.value.draft);assertEquals(0,api.rooms.sends)
            assertEquals(2,mentions.calls.count { it.second.limit==8 && it.second.prefix=="An" })
        }
    }
    @Test fun configurationRetainsTheOriginalPendingQueryAndDoesNotDuplicateItsGet() {
        mentions.gate=CompletableDeferred();enter();type("@An")
        compose.waitUntil(5_000) { mentions.calls.any { it.second.limit==8 && it.second.prefix=="An" } }
        val original=checkNotNull(model.state.value.selection)
        compose.activityRule.scenario.recreate();compose.activityRule.scenario.onActivity(::install);compose.waitForIdle()
        compose.runOnIdle { assertSame(original,model.state.value.selection);assertEquals(1,mentions.calls.count { it.second.limit==8 }) }
        mentions.gate!!.complete(Unit);loaded();details();choose();assertEquals("@Ann ",characters("conversation_composer"))
    }
    private fun presentation(dark: Boolean,large: Boolean) {
        compose.runOnIdle {
            runBlocking { settingsStore.saveTheme(if (dark) ThemePreference.DARK else ThemePreference.LIGHT) }
            largeFont.value=large;completionEnabled.value=false
        }
        enter();type("@A")
        val label=(if (dark) "dark" else "light")+if (large) "-font200" else "-default"
        capture("$label-before-disabled-fixture")
        // The optional model was absent at ON_START; activate only this test fixture's
        // coordinator before attaching it to the already-foreground root.
        compose.runOnIdle { model.foreground();completionEnabled.value=true }
        compose.waitUntil(5_000) { model.state.value.pane!=null }
        compose.onNodeWithTag("conversation_composer").performTextInput("n");loaded();details()
        compose.onNode(hasTestTag("completion_candidate:0") and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        capture("$label-after-details")
        compose.onNode(hasText(text(R.string.rooms_close)) and hasAnyAncestor(isDialog())).assertIsDisplayed().performNativeTap()
    }
    private fun capture(label: String) {
        // The pinned Compose library supports Dialog window capture from API28.
        // API26/27 still execute every behavior assertion; no capture/visual pass is claimed.
        if (android.os.Build.VERSION.SDK_INT<28) return
        compose.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=java.io.File(checkNotNull(context.getExternalFilesDir(null)),"ccm-completion-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val target=if (label.endsWith("after-details")) compose.onNode(isDialog()) else compose.onRoot()
        // captureToImage uses PixelCopy for these ordinary Activity/Dialog roots. Its
        // popup fallback takes a whole-display screenshot, so reject that ancestry.
        var ancestor: SemanticsNode?=target.fetchSemanticsNode()
        while (ancestor!=null) {
            check(!ancestor.config.contains(SemanticsProperties.IsPopup)) { "Capture requires an owned Activity or Dialog window" }
            ancestor=ancestor.parent
        }
        val bitmap=target.captureToImage().asAndroidBitmap()
        try {
            java.io.File(folder,"$label.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
        } finally { bitmap.recycle() }
    }
    @Test fun presentationLightDefault()=presentation(false,false)
    @Test fun presentationDarkDefault()=presentation(true,false)
    @Test fun presentationLightFont200()=presentation(false,true)
    @Test fun presentationDarkFont200()=presentation(true,true)
    private inner class Mentions: RoomMentionOperations {
        override val session=sessions.state
        val calls=CopyOnWriteArrayList<Pair<SessionState,RoomMentionRequest>>()
        @Volatile var gate: CompletableDeferred<Unit>?=null
        @Volatile var ignoreCancellation=false
        @Volatile var fail=false
        override suspend fun suggestions(expected: SessionState,request: RoomMentionRequest): RoomMentionBatch {
            calls+=expected to request
            if (request.limit==8) {
                val failed=fail
                gate?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
                if (failed) throw ApiFailure(FailureKind.NETWORK)
            }
            val people=listOf(RoomMentionUser(8,"Ann"),RoomMentionUser(9,"Andy"),RoomMentionUser(10,"Bob"),RoomMentionUser(11,longName))
                .filter { it.username.startsWith(request.prefix,ignoreCase=true) }
            return RoomMentionBatch(request,request.prefix,people)
        }
    }
    private val longName="L"+"a".repeat(79)
}
