package io.github.thiesi.chitchat.feature.profile

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.profile.*
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.*
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

/** Real root navigation/modal/system input with synthetic facts, not live account/device acceptance. */
class ProfileJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val sessions=TestSessions()
    private val api=NativeChatFixtures(sessions)
    private val profiles=Profiles()
    private lateinit var model: ProfileViewModel
    private lateinit var room: ConversationViewModel
    private lateinit var thread: DirectThreadViewModel
    private lateinit var notices: NotificationCenterViewModel
    private lateinit var targets: MessageTargetViewModel
    @Volatile private var ime=0
    private val settingsStore=object: SettingsStore {
        override val settings=MutableStateFlow(AppSettings(server=(ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value=settings.value.copy(server=server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value=settings.value.copy(theme=theme) }
    }
    @Before fun bind() { compose.activityRule.scenario.onActivity(::install) }
    private fun install(activity: ComponentActivity) {
        val args=InstrumentationRegistry.getArguments(); val config=activity.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(),config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(),config.fontScale,0.01f) }
        activity.enableEdgeToEdge()
        model=ViewModelProvider(activity,ProfileViewModel.factory(profiles))[ProfileViewModel::class.java]
        room=ViewModelProvider(activity,ConversationViewModel.factory(api.rooms))[ConversationViewModel::class.java]
        thread=ViewModelProvider(activity,DirectThreadViewModel.factory(api.directs))[DirectThreadViewModel::class.java]
        val inbox=ViewModelProvider(activity,DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
        notices=ViewModelProvider(activity,NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
        targets=ViewModelProvider(activity,MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
        val settings=ViewModelProvider(activity,SettingsViewModel.factory(settingsStore))[SettingsViewModel::class.java]
        val auth=ViewModelProvider(activity,AuthViewModel.factory(sessions))[AuthViewModel::class.java]
        activity.setContent {
            val bottom=WindowInsets.ime.getBottom(LocalDensity.current); SideEffect { ime=bottom }
            Box(Modifier.fillMaxSize()) { TestChitChatApp(settings,auth,conversationModel=room,directInbox=inbox,directThread=thread,
                notifications=notices,messageTargets=targets,profiles=model) }
        }
    }
    private fun text(id: Int)=InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun enter(roomTarget: Boolean=false, ignored: Boolean=false, blocked: Boolean=false) {
        compose.waitUntil(5_000) { sessions.state.value.phase==SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer","fixture-password") }
            val policy=if (ignored) ChatPolicy(setOf(8),null,api.policy.display) else api.policy
            sessions.state.value=sessions.state.value.copy(chatPolicy=policy,privacy=api.privacy)
            if (blocked) runBlocking { api.directs.setBlocked(8,true) }
            runBlocking { api.directs.send(8,"Profile direct fixture",null) }
        }
        compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("notifications_open").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
        val notice=if (roomTarget) 60 else 55
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag("notification_view:$notice"))
        compose.onNodeWithTag("notification_view:$notice").performNativeTap()
        if (roomTarget) {
            compose.waitUntil(5_000) { room.state.value.let { it.roomId==43L && it.loaded && !it.busy } && targets.state.value.request==null }
            compose.onNodeWithContentDescription(text(R.string.conversation_details)).assertIsDisplayed().performNativeTap()
            compose.onNode(hasText(text(R.string.conversation_latest)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
            compose.waitUntil(5_000) { room.state.value.let { it.latestWindow && !it.busy && !it.reading && !it.stale } }
            compose.runOnIdle { room.updateDraft("Kept room draft") }
        } else {
            compose.waitUntil(5_000) { thread.state.value.let { it.peerId==8L && it.loaded && !it.busy && !it.stale } }
            compose.runOnIdle { thread.updateDraft("Kept direct draft") }
        }
    }
    private fun openRoom() {
        compose.onNodeWithTag("conversation_timeline").performScrollToNode(hasTestTag("room_profile:100"))
        compose.onNodeWithTag("room_profile:100").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).performNativeTap()
        compose.onNodeWithTag("profile_dialog").assertIsDisplayed()
    }
    private fun openDirect() {
        compose.onNodeWithContentDescription(text(R.string.dm_details)).assertIsDisplayed().performNativeTap()
        compose.onNodeWithTag("dm_profile").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performNativeTap()
        compose.onNodeWithTag("profile_dialog").assertIsDisplayed()
    }
    private fun loaded() { compose.waitUntil(5_000) { model.state.value.snapshot!=null && !model.state.value.loading } }
    private fun close() { compose.onNodeWithTag("profile_close").performNativeTap(); compose.onNodeWithTag("profile_dialog").assertDoesNotExist() }
    @After fun cleanup() {
        profiles.gate?.complete(Unit); compose.waitForIdle()
        if (ime>0 || nativeInputWindow().imeVisible!=false) Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { ime==0 }; assertEquals(false,nativeInputWindow().imeVisible)
    }

    @Test fun collapsedIgnoredRoomAuthorCanOpenExactProfileWithoutRevealingBodyOrReading() {
        enter(roomTarget=true,ignored=true); openRoom(); loaded()
        compose.onNodeWithTag("profile_username").assertTextEquals("Profile peer")
        compose.onNodeWithTag("profile_badge").assertTextEquals("Fixture badge")
        compose.onNodeWithText("Room 43 message 100").assertDoesNotExist()
        compose.waitUntil(5_000) { !room.state.value.atLatest }
        compose.runOnIdle {
            assertEquals(8L,profiles.calls.single().second.userId); assertEquals(43L,profiles.calls.single().second.roomId)
            assertEquals(7L,profiles.calls.single().first.user?.id); assertEquals("Kept room draft",room.state.value.draft)
            assertTrue(room.state.value.revealed.isEmpty()); assertEquals(0,api.rooms.sends); assertEquals(0,api.notices.posts)
        }
        close(); compose.runOnIdle { assertEquals("Kept room draft",room.state.value.draft) }
    }
    @Test fun blockedDirectPeerShowsGuestVisitFactsAndUnicodePlaceholderWithoutAccountActions() {
        profiles.guest=true; profiles.name="\uD83D\uDE42 Visitor"; enter(blocked=true); openDirect(); loaded()
        compose.onNodeWithTag("profile_username").assertTextEquals("\uD83D\uDE42 Visitor")
        compose.onNodeWithTag("profile_initial").assertTextEquals("\uD83D\uDE42")
        compose.onNode(hasText("Guest visit started",substring=true)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Member since",substring=true)).assertDoesNotExist()
        compose.runOnIdle {
            assertNull(profiles.calls.single().second.roomId); assertEquals(8L,profiles.calls.single().second.userId)
            assertTrue(thread.state.value.context!!.relationship.blockedByMe)
            assertEquals("Kept direct draft",thread.state.value.draft); assertEquals(1,api.directs.sends)
        }
        compose.onNodeWithTag("author_confirm").assertDoesNotExist(); close()
    }
    @Test fun explicitRefreshDropsOldFactsOnDenialAndRecoversOnlyWithNewChoice() {
        enter(); openDirect(); loaded(); val original=checkNotNull(model.state.value.selection)
        compose.runOnIdle { profiles.failure=ApiFailure(FailureKind.HTTP,404,"user_not_found") }
        compose.onNodeWithTag("profile_refresh").performNativeTap()
        compose.waitUntil(5_000) { model.state.value.error?.status==404 && !model.state.value.loading }
        compose.onNodeWithTag("profile_username").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.profile_unavailable)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(2,profiles.calls.size); model.close(original); profiles.failure=null }
        compose.onNodeWithTag("profile_refresh").performNativeTap(); loaded()
        compose.runOnIdle { assertEquals(3,profiles.calls.size) }; close()
    }
    @Test fun configurationRetainsOneOriginalProfileGetAndTheChatDraft() {
        enter(); compose.runOnIdle { profiles.gate=CompletableDeferred() }; openDirect()
        compose.waitUntil(5_000) { profiles.calls.size==1 && model.state.value.loading }
        val choice=checkNotNull(model.state.value.selection)
        compose.activityRule.scenario.recreate(); compose.activityRule.scenario.onActivity(::install)
        compose.onNodeWithTag("profile_dialog").assertIsDisplayed()
        compose.runOnIdle { assertSame(choice,model.state.value.selection); assertEquals(1,profiles.calls.size); profiles.gate!!.complete(Unit) }
        loaded(); close(); compose.runOnIdle { assertEquals("Kept direct draft",thread.state.value.draft); assertEquals(1,profiles.calls.size) }
    }
    @Test fun genuineBackgroundRetiresPrivateSelectionAndNeverReplaysOnResume() {
        enter(); compose.runOnIdle { profiles.gate=CompletableDeferred(); profiles.ignoreCancellation=true }; openDirect()
        compose.waitUntil(5_000) { profiles.calls.size==1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertNull(model.state.value.selection)
        profiles.gate!!.complete(Unit)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("profile_dialog").assertDoesNotExist()
        compose.runOnIdle { assertNull(model.state.value.snapshot); assertEquals(1,profiles.calls.size) }
    }
    @Test fun replacingOwnerCannotPublishLateProfileFactsIntoTheNewSession() {
        enter(); compose.runOnIdle { profiles.gate=CompletableDeferred(); profiles.ignoreCancellation=true }; openDirect()
        compose.waitUntil(5_000) { profiles.calls.size==1 }
        compose.runOnIdle { sessions.state.value=sessions.state.value.copy(generation=sessions.state.value.generation+1) }
        compose.onNodeWithTag("profile_dialog").assertDoesNotExist()
        compose.runOnIdle { profiles.gate!!.complete(Unit) }
        compose.runOnIdle { assertNull(model.state.value.snapshot); assertEquals(1,profiles.calls.size) }
    }
    @Test fun closeProfileThenOnePhysicalComposerTapAcquiresImeAndSendsCompleteDraft() {
        enter(); openDirect(); loaded(); close()
        compose.runOnIdle { assertEquals("Kept direct draft",thread.state.value.draft) }
        val body="Profile draft\nsecond line"
        compose.tapNativeInput("dm_composer",{ ime }).performTextReplacement(body)
        assertEquals(body,compose.onNodeWithTag("dm_composer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("dm_send").assertIsDisplayed().assertIsEnabled().performNativeTap()
        compose.waitUntil(5_000) { thread.state.value.messages.any { it.body==body } && thread.state.value.draft.isEmpty() }
        compose.runOnIdle { assertEquals(2,api.directs.sends); assertEquals(1,profiles.calls.size); assertFalse(thread.state.value.uncertainSend) }
    }

    private inner class Profiles: ProfileOperations {
        override val session=sessions.state
        val calls=CopyOnWriteArrayList<Pair<SessionState,ProfileRequest>>()
        var gate: CompletableDeferred<Unit>?=null; var failure: ApiFailure?=null
        var ignoreCancellation=false; var guest=false; var name="Profile peer"
        override suspend fun load(expected: SessionState, request: ProfileRequest): ProfileSnapshot {
            calls+=expected to request
            gate?.let { if (ignoreCancellation) withContext(NonCancellable) { it.await() } else it.await() }
            failure?.let { throw it }
            return ProfileSnapshot(BasicProfile(request.userId,name,"2026-01-02T12:00:00Z","Fixture badge",guest,true,0,true),request.roomId,true)
        }
    }
}
