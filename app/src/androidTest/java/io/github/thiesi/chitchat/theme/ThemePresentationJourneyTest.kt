package io.github.thiesi.chitchat.theme

import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks // THEME_ONLY
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.app.ChitChatApp
import io.github.thiesi.chitchat.core.rooms.*
import io.github.thiesi.chitchat.core.search.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.AccountViewModel
import io.github.thiesi.chitchat.feature.account.TestAccounts
import io.github.thiesi.chitchat.feature.auth.*
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.direct.*
import io.github.thiesi.chitchat.feature.notifications.NotificationCenterViewModel
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.feature.search.MessageSearchViewModel
import io.github.thiesi.chitchat.feature.targets.MessageTargetViewModel
import io.github.thiesi.chitchat.testing.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Owned-window synthetic presentation captures. No input activation, live account or export. */
@OptIn(ExperimentalTestApi::class)
class ThemePresentationJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val sessions=TestSessions()
    private val api=NativeChatFixtures(sessions)
    private val store=object: SettingsStore {
        override val settings=MutableStateFlow(AppSettings())
        override suspend fun saveServer(server: ServerAddress?) { settings.value=settings.value.copy(server=server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value=settings.value.copy(theme=theme) }
        override suspend fun savePureBlack(enabled: Boolean) { settings.value=settings.value.copy(pureBlack=enabled) } // THEME_ONLY
    }
    private lateinit var settings: SettingsViewModel
    private lateinit var room: ConversationViewModel
    private lateinit var directory: RoomsViewModel
    private lateinit var inbox: DirectInboxViewModel
    private lateinit var thread: DirectThreadViewModel
    private lateinit var notices: NotificationCenterViewModel
    private lateinit var search: MessageSearchViewModel
    private var observedFontScale=0f
    private var observedWidth=0f
    private var observedHeight=0f
    private fun text(id: Int)=InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun back() { compose.onNodeWithText(text(R.string.back)).assertIsDisplayed().performNativeTap() }
    private fun action(id: Int) { compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed().performNativeTap() }

    private fun install(mode: Int,large: Boolean) {
        if(Build.VERSION.SDK_INT>=34) compose.enableAccessibilityChecks() // THEME_ONLY
        runBlocking {
            store.saveTheme(if(mode==0) ThemePreference.LIGHT else ThemePreference.DARK)
            store.savePureBlack(mode==2) // THEME_ONLY
        }
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            settings=ViewModelProvider(activity,SettingsViewModel.factory(store))[SettingsViewModel::class.java]
            val auth=ViewModelProvider(activity,AuthViewModel.factory(sessions))[AuthViewModel::class.java]
            val account=ViewModelProvider(activity,AccountViewModel.factory(TestAccounts(auth.state)))[AccountViewModel::class.java]
            val rooms=object: RoomOperations {
                override val session=sessions.state
                private val rows=listOf(RoomInfo(43,"studio","Room 43","A shared room","private",0,0,8,"member",false,"none",12,88),
                    RoomInfo(42,"lounge","Lounge","Conversation and questions","public",0,0,8,"member",false,"none",3,97))
                override suspend fun list()=rows
                override suspend fun detail(roomId: Long)=rows.single { it.id==roomId }
                override suspend fun join(roomId: Long): RoomInfo=error("No membership mutation in a capture")
                override suspend fun leave(roomId: Long) { error("No membership mutation in a capture") }
                override suspend fun create(input: CreateRoom): RoomInfo=error("No room creation in a capture")
            }
            directory=ViewModelProvider(activity,RoomsViewModel.factory(rooms))[RoomsViewModel::class.java]
            room=ViewModelProvider(activity,ConversationViewModel.factory(api.rooms))[ConversationViewModel::class.java]
            inbox=ViewModelProvider(activity,DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
            thread=ViewModelProvider(activity,DirectThreadViewModel.factory(api.directs))[DirectThreadViewModel::class.java]
            notices=ViewModelProvider(activity,NotificationCenterViewModel.factory(api.notices))[NotificationCenterViewModel::class.java]
            val targets=ViewModelProvider(activity,MessageTargetViewModel.factory(api.lookups))[MessageTargetViewModel::class.java]
            val searches=object: MessageSearchOperations {
                override val session=sessions.state
                override suspend fun search(expected: SessionState,request: MessageSearchRequest): MessageSearchPage=
                    error("No search POST in a capture")
            }
            search=ViewModelProvider(activity,MessageSearchViewModel.factory(searches))[MessageSearchViewModel::class.java]
            val context=ContextThemeWrapper(activity,activity.theme)
            context.applyOverrideConfiguration(Configuration(activity.resources.configuration).apply { if(large) fontScale=2f })
            activity.setContentView(ComposeView(context).apply { setContent {
                val density=LocalDensity.current
                BoxWithConstraints(Modifier.fillMaxSize().testTag("theme_capture_root")) {
                    val width=maxWidth.value;val height=maxHeight.value
                    SideEffect { observedFontScale=density.fontScale;observedWidth=width;observedHeight=height }
                    ChitChatApp(settings,auth,account,directory,room,directInbox=inbox,directThread=thread,
                        notifications=notices,messageTargets=targets,search=search,onThemeChanged={ dark ->
                            activity.enableEdgeToEdge(
                                statusBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) { dark },
                                navigationBarStyle=SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT) { dark })
                        })
                }
            } })
        }
        compose.waitUntil(5_000) { sessions.state.value.phase==SessionPhase.NO_SERVER }
        compose.waitUntil(5_000) { settings.state.value.let { it.hasLoadedSettings && it.settings==store.settings.value } }
        compose.waitForIdle()
        assertEquals(if(large) 2f else compose.activity.resources.configuration.fontScale,observedFontScale,0.01f)
        // Only the separate comparison job requests exact dimensions. Ordinary CI
        // retains and records its own window size rather than pretending it is 360dp.
        val args=InstrumentationRegistry.getArguments()
        args.getString("expectedCaptureWidthDp")?.toFloat()?.let { assertEquals(it,observedWidth,0.5f) }
        args.getString("expectedCaptureHeightDp")?.toFloat()?.let { assertEquals(it,observedHeight,0.5f) }
    }

    private fun captures(mode: Int,large: Boolean) {
        install(mode,large)
        val label=(when(mode) { 0 -> "light";1 -> "dark";else -> "black" })+if(large) "-font200" else "-default"
        capture(label,"server")
        compose.runOnIdle { runBlocking { store.saveServer((ServerAddress.parse("chat.example.test") as AddressResult.Valid).address) } }
        compose.waitUntil(5_000) { sessions.state.value.phase==SessionPhase.ANONYMOUS }
        capture(label,"auth")
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer","fixture-password") }
            sessions.state.value=sessions.state.value.copy(chatPolicy=api.policy,privacy=api.privacy)
            runBlocking { api.directs.send(8,"Synthetic presentation fixture",null) }
        }
        compose.onNodeWithTag("notifications_open").assertExists()
        capture(label,"home")
        action(R.string.settings);compose.onNodeWithText(text(R.string.appearance)).assertExists();capture(label,"settings");back()
        action(R.string.rooms_title)
        compose.waitUntil(5_000) { directory.state.value.loaded && !directory.state.value.busy }
        capture(label,"rooms")
        compose.onNodeWithText("Room 43").assertIsDisplayed().performNativeTap()
        compose.onNode(hasText(text(R.string.rooms_open_conversation)) and hasAnyAncestor(isDialog())).performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { room.state.value.let { it.loaded && !it.busy && it.roomId==43L } }
        compose.onNodeWithTag("conversation_composer").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        capture(label,"room");back();back()
        action(R.string.dm_title)
        compose.waitUntil(5_000) { inbox.state.value.let { it.inbox!=null && !it.busy } }
        capture(label,"direct")
        compose.onNodeWithTag("dm_conversation:8").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { thread.state.value.let { it.loaded && !it.busy && it.peerId==8L } }
        compose.onNodeWithTag("dm_composer").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        capture(label,"thread");back();back()
        compose.onNodeWithTag("notifications_open").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { notices.state.value.loaded && !notices.state.value.busy }
        capture(label,"activity");back()
        action(R.string.account);capture(label,"account");back()
        compose.onNodeWithTag("message_search_open").performScrollTo().performNativeTap()
        compose.waitUntil(5_000) { search.state.value.entry!=null }
        capture(label,"search")
        compose.runOnIdle { assertEquals(0,api.rooms.sends);assertEquals(0,api.notices.posts) }
    }

    private fun capture(label: String,scene: String) {
        compose.waitForIdle()
        if(Build.VERSION.SDK_INT>=34) compose.onRoot().tryPerformAccessibilityChecks() // THEME_ONLY
        if(Build.VERSION.SDK_INT<28) return
        val args=InstrumentationRegistry.getArguments()
        val output=args.getString("additionalTestOutputDir")?.let(::File)
            ?: checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val folder=File(output,"ccm-theme-presentation");check(folder.isDirectory || folder.mkdirs())
        val node=compose.onNodeWithTag("theme_capture_root")
        var ancestor: androidx.compose.ui.semantics.SemanticsNode?=node.fetchSemanticsNode()
        while(ancestor!=null) { check(!ancestor.config.contains(SemanticsProperties.IsPopup));ancestor=ancestor.parent }
        val image=node.captureToImage().asAndroidBitmap()
        try { File(folder,"theme-$label-$scene.png").outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        finally { image.recycle() }
        // No text, account or message state: configuration/provenance scalars only.
        File(folder,"theme-$label-configuration.json").writeText("{\"fontScale\":$observedFontScale,\"widthDp\":$observedWidth,\"heightDp\":$observedHeight}")
    }

    @Test fun capturesLightDefault()=captures(0,false)
    @Test fun capturesDarkDefault()=captures(1,false)
    @Test fun capturesPureBlackDefault()=captures(2,false) // THEME_ONLY
    @Test fun capturesLightFont200()=captures(0,true)
    @Test fun capturesDarkFont200()=captures(1,true)
    @Test fun capturesPureBlackFont200()=captures(2,true) // THEME_ONLY
}
