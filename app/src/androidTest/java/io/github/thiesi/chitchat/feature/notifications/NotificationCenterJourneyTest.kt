package io.github.thiesi.chitchat.feature.notifications

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.core.conversation.ChatPolicy
import io.github.thiesi.chitchat.core.conversation.DisplayPreferences
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.notifications.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Native root/list/read journeys with typed fakes only; no live installation or push claim. */
class NotificationCenterJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val api = Notices(sessions)
    private val store = ViewModelStore()
    private val auth = AuthViewModel(sessions).also { store.put("auth", it) }
    private val model = NotificationCenterViewModel(api).also { store.put("notifications", it) }
    @Before fun configuration() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    @After fun close() {
        closeSoftKeyboard()
        compose.runOnIdle { api.postGate?.complete(Unit); model.background(); store.clear() }
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun root() {
        val settings = SettingsViewModel(object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings(server = address))
            override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
            override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
        }).also { store.put("settings", it) }
        compose.setContent { Box(Modifier.fillMaxSize()) { TestChitChatApp(settings, auth, notifications = model) } }
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            runBlocking { sessions.signIn("Viewer", "fixture-password") }
            sessions.state.value = sessions.state.value.copy(chatPolicy = ChatPolicy(emptySet(), null, DisplayPreferences()))
        }
        compose.waitUntil(5_000) { model.state.value.unreadCount == 999 && !model.state.value.busy }
    }
    private fun open() {
        compose.onNodeWithTag("notifications_open").performScrollTo().assertIsDisplayed().performClick()
        fresh()
    }
    private fun fresh() { compose.waitUntil(5_000) { model.state.value.loaded && !model.state.value.busy && !model.state.value.stale } }
    private fun at(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("notification_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun click(tag: String) { at(tag).assertIsEnabled().performClick() }
    private fun back() { compose.onNodeWithText(text(R.string.back)).assertIsDisplayed().performClick() }
    private fun readState(id: Long, read: Boolean) = at("notification:$id").assert(SemanticsMatcher.expectValue(
        SemanticsProperties.StateDescription, text(if (read) R.string.notifications_read else R.string.notifications_unread)))

    @Test fun homeCountListPlainTextExplicitReadAndBackStayIndependentFromChat() {
        root()
        compose.onNodeWithTag("notifications_open").performScrollTo().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "999 unread notifications"))
        compose.runOnIdle { assertFalse(model.state.value.loaded); assertTrue(model.state.value.notifications.isEmpty()) }
        open(); readState(55, false)
        compose.onNodeWithText("<b>Private fixture 55</b>").assertExists()
        compose.onAllNodesWithText(text(R.string.notifications_link_pending)).onFirst().assertExists()
        compose.onNodeWithText("View message").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, api.ones.get()); assertEquals(0, api.alls.get()) }
        click("notification_read:55"); fresh(); readState(55, true)
        compose.runOnIdle { assertEquals(1, api.ones.get()); assertEquals(0, api.alls.get()); assertEquals(7, model.state.value.unreadCount) }
        capture("notification-read.png")
        back()
        compose.waitUntil(5_000) { !model.state.value.busy && !model.state.value.countStale }
        compose.onNodeWithTag("notifications_open").performScrollTo().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "7 unread notifications"))
        compose.onNodeWithTag("notification_list").assertDoesNotExist()
    }
    @Test fun olderPagesKeepDescendingRowsAndOpeningNeverMarksThemRead() {
        root(); open(); click("notifications_more"); fresh()
        compose.runOnIdle {
            assertEquals((6L..55L).reversed().toList(), model.state.value.notifications.map { it.id })
            assertEquals(999, model.state.value.unreadCount); assertEquals(0, api.ones.get()); assertEquals(0, api.alls.get())
        }
        at("notification:30"); click("notifications_more"); fresh()
        at("notification:1"); compose.onNodeWithTag("notifications_more").assertDoesNotExist()
        compose.runOnIdle { assertEquals(55, model.state.value.notifications.size); assertEquals(0, api.ones.get()) }
        capture("notification-older.png")
    }
    @Test fun allConfirmationCanCancelAndExplicitlyIncludesUnloadedAndNewArrivals() {
        root(); open(); click("notifications_all")
        compose.onNodeWithText(text(R.string.notifications_all_body)).assertExists()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, api.alls.get()); assertNull(model.state.value.allChoice) }
        click("notifications_all")
        compose.runOnIdle { api.rows = listOf(notice(77)) + api.rows }
        compose.onNodeWithTag("notifications_all_confirm").assertIsDisplayed().assertIsEnabled().performClick(); fresh()
        compose.runOnIdle {
            assertEquals(1, api.alls.get()); assertTrue(api.rows.all { it.read })
            assertEquals(0, model.state.value.unreadCount); assertEquals(0, api.ones.get())
        }
        readState(77, true)
    }
    @Test fun unknownReadSurvivesPassiveResumeUntilExplicitGetAndNeverReplays() {
        root(); open(); api.postFailure = ApiFailure(FailureKind.TIMEOUT)
        click("notification_read:55"); compose.waitUntil(5_000) { model.state.value.unknownRead && !model.state.value.busy }
        compose.runOnIdle { api.postFailure = null }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED); fresh()
        compose.runOnIdle { assertTrue(model.state.value.unknownRead); assertEquals(1, api.ones.get()); assertEquals(0, api.alls.get()) }
        at("notifications_all").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.notifications_unknown_read)).assertExists()
        click("notifications_more"); fresh()
        compose.runOnIdle { assertTrue(model.state.value.unknownRead); assertEquals(1, api.ones.get()) }
        click("notifications_refresh"); fresh()
        compose.runOnIdle { assertFalse(model.state.value.unknownRead); assertEquals(1, api.ones.get()) }
        at("notification_read:55").assertIsEnabled()
    }
    @Test fun confirmedReadKeepsOverlayAfterGetFailureAndExposesRecoveryAtTheFooter() {
        root(); open(); api.failGetAfterPost = true
        click("notification_read:55"); compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.error != null }
        readState(55, true); compose.onNodeWithTag("notification_read:55").assertDoesNotExist()
        compose.runOnIdle { assertFalse(model.state.value.unknownRead); assertEquals(1, api.ones.get()); assertNull(model.state.value.notifications.first().readAt) }
        at("notifications_footer_refresh").assertIsEnabled()
        compose.runOnIdle { api.failGetAfterPost = false }
        click("notifications_footer_refresh"); fresh(); compose.runOnIdle { assertEquals(1, api.ones.get()) }
    }
    @Test fun revokedAccessClearsPrivateRowsAndCountWithoutReadingOrFallingBack() {
        root(); open(); at("notification:55")
        compose.runOnIdle { api.getFailure = ApiFailure(FailureKind.HTTP, 403, "forbidden") }
        click("notifications_refresh"); compose.waitUntil(5_000) { !model.state.value.busy && !model.state.value.loaded }
        compose.onNodeWithTag("notification:55").assertDoesNotExist()
        compose.onNodeWithText("<b>Private fixture 55</b>").assertDoesNotExist()
        at("notifications_count").assertTextEquals(text(R.string.notifications_count_unknown))
        at("notifications_all").assertIsNotEnabled()
        compose.runOnIdle { assertNull(model.state.value.unreadCount); assertEquals(0, api.ones.get()); assertEquals(0, api.alls.get()) }
    }
    @Test fun retiredEntryCallbacksCannotReadOrRefreshAReopenedCenterForTheSameAccount() {
        root(); open()
        val oldRead = at("notification_read:55").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldRefresh = at("notifications_refresh").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val oldAll = at("notifications_all").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        back(); open(); val calls = api.gets.get()
        compose.runOnIdle { oldRead(); oldRefresh(); oldAll() }
        compose.runOnIdle {
            assertEquals(calls, api.gets.get()); assertEquals(0, api.ones.get()); assertEquals(0, api.alls.get())
            assertNull(model.state.value.allChoice)
        }
    }
    @Test fun accountSwitchDropsDialogAndPrivateRowsAndReturnsToHome() {
        root(); open(); click("notifications_all")
        val oldConfirm = compose.onNodeWithTag("notifications_all_confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
                user = SessionUser(9, "Second fixture", listOf("member"), 1, false))
            oldConfirm()
        }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("notifications_open").assertExists() }.isSuccess }
        compose.onNodeWithTag("notification_list").assertDoesNotExist()
        compose.onNodeWithTag("notifications_all_confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(model.state.value.notifications.isEmpty()); assertEquals(0, api.ones.get()); assertEquals(0, api.alls.get()) }
    }
    @Test fun shortViewportKeepsLongPrivateNoticeAndReadControlsReachable() {
        compose.runOnIdle {
            sessions.state.value = SessionState(server = address, generation = 1, phase = SessionPhase.AUTHENTICATED,
                user = SessionUser(7, "Viewer", listOf("member"), 1, false))
            api.rows = listOf(notice(55, details = List(12) { "Long fixture detail $it" }))
            model.foreground()
        }
        compose.setContent {
            val displayed by sessions.state.collectAsStateWithLifecycle()
            MaterialTheme { Box(Modifier.size(320.dp, 200.dp)) {
                NotificationCenterScreen(model, displayed, {}, isCurrentEntry = { true })
            } }
        }
        fresh(); click("notification_read:55"); fresh(); readState(55, true)
        at("notifications_refresh").assertIsEnabled(); at("notifications_all").assertIsEnabled()
        capture("notification-short.png")
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureNotifications") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir("screenshots"), name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private class Notices(sessions: TestSessions) : NotificationOperations {
        override val session = sessions.state
        val gets = AtomicInteger(); val ones = AtomicInteger(); val alls = AtomicInteger()
        var rows = (1L..55L).reversed().map { notice(it) }
        private var unread = 999
        @Volatile var postFailure: ApiFailure? = null
        @Volatile var getFailure: ApiFailure? = null
        @Volatile var failGetAfterPost = false
        var postGate: CompletableDeferred<Unit>? = null
        override suspend fun page(expected: SessionState, beforeId: Long?, limit: Int): NotificationPage {
            gets.incrementAndGet(); getFailure?.let { throw it }
            if (failGetAfterPost && ones.get() + alls.get() > 0) throw ApiFailure(FailureKind.NETWORK)
            val selected = rows.filter { beforeId == null || it.id < beforeId }.take(limit)
            return NotificationPage(selected, unread, selected.lastOrNull()?.id, selected.size == limit)
        }
        override suspend fun markRead(expected: SessionState, ids: List<Long>): NotificationReadResult {
            ones.incrementAndGet(); postGate?.await(); postFailure?.let { throw it }
            rows = rows.map { if (it.id in ids) notice(it.id, read = true, details = it.details) else it }; unread = 7
            return NotificationReadResult(ids.size, unread)
        }
        override suspend fun markAllRead(expected: SessionState): NotificationReadResult {
            alls.incrementAndGet(); postGate?.await(); postFailure?.let { throw it }
            rows = rows.map { notice(it.id, read = true, details = it.details) }; unread = 0
            return NotificationReadResult(rows.size, unread)
        }
    }
    companion object {
        private val address = (ServerAddress.parse("https://chat.example.test") as AddressResult.Valid).address
        private const val stamp = "2026-10-21T00:00:00Z"
        private fun notice(id: Long, read: Boolean = false, details: List<String> = listOf("Private detail $id")) =
            AccountNotification(id, "security", "Notice $id", "<b>Private fixture $id</b>", details,
                "/?room_id=42&message_id=9", read, stamp.takeIf { read }, stamp)
    }
}
