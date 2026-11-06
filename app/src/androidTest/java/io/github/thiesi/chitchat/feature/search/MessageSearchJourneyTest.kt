package io.github.thiesi.chitchat.feature.search

import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.app.SettingsViewModel
import io.github.thiesi.chitchat.app.EmptyRoomOperations
import io.github.thiesi.chitchat.app.EmptyConversationOperations
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.search.*
import io.github.thiesi.chitchat.core.server.*
import io.github.thiesi.chitchat.core.session.*
import io.github.thiesi.chitchat.core.settings.*
import io.github.thiesi.chitchat.feature.account.TestChitChatApp
import io.github.thiesi.chitchat.feature.account.AccountViewModel
import io.github.thiesi.chitchat.feature.account.TestAccounts
import io.github.thiesi.chitchat.feature.rooms.RoomsViewModel
import io.github.thiesi.chitchat.feature.conversation.ConversationViewModel
import io.github.thiesi.chitchat.feature.auth.AuthViewModel
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import io.github.thiesi.chitchat.testing.performNativeTap
import io.github.thiesi.chitchat.testing.tapNativeInput
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real root navigation/IME plus one explicitly labelled held component-press check; no live account. */
class MessageSearchJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sessions = TestSessions()
    private val fixtures = NativeChatFixtures(sessions)
    private val api = SearchOperations(sessions)
    private val store = ViewModelStore()
    private val auth = AuthViewModel(sessions).also { store.put("auth", it) }
    private val account = AccountViewModel(TestAccounts(sessions.state)).also { store.put("account", it) }
    private val rooms = RoomsViewModel(EmptyRoomOperations(sessions.state)).also { store.put("rooms", it) }
    private val conversation = ConversationViewModel(EmptyConversationOperations(sessions.state)).also { store.put("conversation", it) }
    private val settings = SettingsViewModel(object : SettingsStore {
        override val settings = MutableStateFlow(AppSettings(server =
            (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address))
        override suspend fun saveServer(server: ServerAddress?) { settings.value = settings.value.copy(server = server) }
        override suspend fun saveTheme(theme: ThemePreference) { settings.value = settings.value.copy(theme = theme) }
    }).also { store.put("settings", it) }
    private lateinit var model: MessageSearchViewModel
    @Volatile private var ime = 0
    private var registry: SaveableStateRegistry? = null
    private var constrained = false

    @Before fun configuration() {
        val args = InstrumentationRegistry.getArguments()
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        args.getString("expectedOrientation")?.let { assertEquals(it.toInt(), config.orientation) }
        args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), config.fontScale, 0.01f) }
    }
    private fun install() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            model = ViewModelProvider(activity, MessageSearchViewModel.factory(api))[MessageSearchViewModel::class.java]
            activity.setContent {
                val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
                val saved = LocalSaveableStateRegistry.current
                SideEffect { ime = bottom; registry = saved }
                Box(Modifier.fillMaxSize()) {
                    Box(if (constrained) Modifier.fillMaxWidth().height(560.dp) else Modifier.fillMaxSize()) {
                        TestChitChatApp(settings, auth, account = account, roomModel = rooms, conversationModel = conversation, search = model)
                    }
                }
            }
        }
    }
    private fun content(privacy: Boolean = true) {
        install()
        compose.waitUntil(5_000) { sessions.state.value.phase == SessionPhase.ANONYMOUS }
        compose.runOnIdle {
            sessions.state.value = sessions.state.value.copy(phase = SessionPhase.AUTHENTICATED,
                user = SessionUser(7, "Viewer", listOf("member"), 1, false), chatPolicy = fixtures.policy,
                privacy = fixtures.privacy.takeIf { privacy })
        }
        compose.onNodeWithTag("message_search_open").performScrollTo().performNativeTap()
        compose.onNodeWithTag("message_search_query").assertIsDisplayed()
    }
    private fun input(text: String) {
        compose.tapNativeInput("message_search_query", { ime }).performTextInput(text)
        assertEquals(text, compose.onNodeWithTag("message_search_query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun search(text: String = "needle") {
        input(text); compose.onNodeWithTag("message_search_submit").performNativeTap()
        compose.waitUntil(5_000) { model.state.value.results != null && !model.state.value.busy }
    }
    @After fun close() {
        Espresso.closeSoftKeyboard()
        compose.activityRule.scenario.onActivity {
            api.gates.values.forEach { gate -> gate.complete(Unit) }
            if (::model.isInitialized) model.state.value.entry?.let(model::background)
            store.clear()
            registry = null
        }
    }

    @Test fun nativeSearchKeepsLiteralExcerptsAndPagesTheCompletedQueryAfterDraftEdits() {
        content(); search()
        compose.onAllNodesWithTag("message_search_excerpt").onFirst().assertTextEquals(SearchOperations.excerpt(1))
        val editing = compose.tapNativeInput("message_search_query", { ime })
        editing.performTextClearance()
        editing.performTextInput("unsubmitted draft")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("message_search_next").performNativeTap()
        compose.waitUntil(5_000) { model.state.value.results?.attempt?.request?.offset == 25 }
        compose.runOnIdle {
            assertEquals("needle", api.calls.last().query)
            assertEquals(MessageSearchScope.ALL, api.calls.last().scope)
            assertEquals("unsubmitted draft", model.state.value.query)
            assertEquals(2, api.calls.size)
        }
        compose.onNodeWithText("Opening the original message is not available yet.").assertExists()
        compose.onNodeWithTag("message_search_previous").performNativeTap()
        compose.waitUntil(5_000) { model.state.value.results?.attempt?.request?.offset == 0 }
        compose.runOnIdle { assertEquals(3, api.calls.size) }
    }
    @Test fun unknownPrivacyBlocksAllAndDirectButExplicitRoomsSearchRemainsAvailable() {
        content(privacy = false); input("needle")
        compose.onNodeWithTag("message_search_submit").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(api.calls.isEmpty()) }
        compose.onNodeWithContentDescription("Search options and privacy").performNativeTap()
        compose.onNodeWithTag("message_search_scope_ROOMS").performNativeTap()
        compose.onNodeWithText("Close").performNativeTap()
        compose.onNodeWithTag("message_search_submit").performNativeTap()
        compose.waitUntil(5_000) { model.state.value.results != null }
        compose.runOnIdle { assertEquals(MessageSearchScope.ROOMS, api.calls.single().scope) }
        compose.onAllNodesWithTag("message_search_excerpt").onFirst().assertTextEquals(SearchOperations.excerpt(1))
    }
    @Test fun actualActivityRecreationKeepsPendingWorkAndComposeSavedStateContainsNoQueryOrExcerpt() {
        api.gates[0] = CompletableDeferred(); content()
        val privateQuery = "PRIVATE_SEARCH_RECREATION_SENTINEL"
        input(privateQuery); compose.onNodeWithTag("message_search_submit").performNativeTap()
        compose.waitUntil(5_000) { api.calls.size == 1 }
        val original = model
        assertNotSaved(privateQuery)
        compose.activityRule.scenario.recreate()
        install()
        compose.onNodeWithTag("message_search_query").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(original, model); assertEquals(privateQuery, model.state.value.query)
            assertEquals(1, api.calls.size); api.gates.getValue(0).complete(Unit)
        }
        compose.waitUntil(5_000) { model.state.value.results != null }
        assertNotSaved(privateQuery, SearchOperations.excerpt(1))
    }
    @Test fun genuineBackgroundAndOwnerReplacementClearPrivateSearchWithoutAutomaticPosts() {
        api.gates[0] = CompletableDeferred(); content()
        input("private background"); compose.onNodeWithTag("message_search_submit").performNativeTap()
        compose.waitUntil(5_000) { api.calls.size == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity {
            assertEquals("", model.state.value.query); assertNull(model.state.value.results)
            api.gates.getValue(0).complete(Unit)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("message_search_query").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, api.calls.size); assertNull(model.state.value.results) }
        search("new explicit query")
        compose.runOnIdle {
            sessions.state.value = sessions.state.value.copy(generation = sessions.state.value.generation + 1,
                user = checkNotNull(sessions.state.value.user).copy(id = 9))
        }
        compose.onNodeWithTag("message_search_open").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("message_search_query").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", model.state.value.query); assertNull(model.state.value.results); assertEquals(2, api.calls.size) }
    }
    @Test fun keyedCancelRetiresAHeldComponentPressInsteadOfCancellingItsSuccessor() {
        api.gates[0] = CompletableDeferred(); api.gates[1] = CompletableDeferred()
        content(); input("needle"); compose.onNodeWithTag("message_search_submit").performNativeTap()
        compose.waitUntil(5_000) { api.calls.size == 1 }
        Espresso.closeSoftKeyboard()
        val old = checkNotNull(model.state.value.pending)
        // Direct root-view touch dispatch intentionally tests Compose node identity, not OS input routing.
        compose.onNodeWithTag("message_search_cancel").performTouchInput { down(center) }
        var successor: MessageSearchAttempt? = null
        compose.runOnIdle {
            model.cancel(old)
            successor = model.search(checkNotNull(model.state.value.entry))
        }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle {
            assertNotNull(successor); assertSame(successor, model.state.value.pending)
            assertEquals(2, api.calls.size)
            api.gates.values.forEach { it.complete(Unit) }
        }
        compose.waitUntil(5_000) { model.state.value.results?.attempt === successor }
    }
    @Test fun constrainedKeyboardViewportKeepsQueryAndActionTargetsUsableAndCeilingExplicit() {
        constrained = true; content(); search()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue(compose.onNodeWithTag("message_search_query").fetchSemanticsNode().boundsInRoot.height >= 48 * density)
        assertTrue(compose.onNodeWithTag("message_search_submit").fetchSemanticsNode().boundsInRoot.height >= 48 * density)
        Espresso.closeSoftKeyboard()
        // Programmatic explicit actor actions cover the final bounded-page rendering; no tap/IME repair.
        compose.runOnIdle { repeat(200) {
            checkNotNull(model.next(checkNotNull(model.state.value.entry), checkNotNull(model.state.value.results)))
        } }
        val ceiling = "The search page limit has been reached. Refine your terms to find more specific results."
        compose.onNodeWithTag("message_search_results").performScrollToNode(hasText(ceiling))
        compose.onNodeWithText(ceiling).assertIsDisplayed()
        compose.onNodeWithTag("message_search_next").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(5000, model.state.value.results!!.attempt.request.offset); assertEquals(201, api.calls.size) }
    }

    /** Observe the real Compose registry without invoking Activity.onSaveInstanceState/lifecycle ourselves. */
    private fun assertNotSaved(vararg privateText: String) {
        compose.runOnIdle {
            val parcel = Parcel.obtain()
            val bytes = try { parcel.writeMap(checkNotNull(registry).performSave()); parcel.marshall() } finally { parcel.recycle() }
            privateText.forEach { text ->
                for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                    val needle = text.toByteArray(encoding)
                    assertFalse("Private search text entered Compose saved state",
                        bytes.indices.any { start -> start + needle.size <= bytes.size && needle.indices.all { bytes[start + it] == needle[it] } })
                }
            }
        }
    }
    private class SearchOperations(private val sessions: TestSessions) : MessageSearchOperations {
        override val session = sessions.state
        val calls = CopyOnWriteArrayList<MessageSearchRequest>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        override suspend fun search(expected: SessionState, request: MessageSearchRequest): MessageSearchPage {
            val index = calls.size; calls += request
            gates[index]?.let { withContext(NonCancellable) { it.await() } }
            val rows = (1L..25L).map { indexInPage ->
                val id = request.offset + indexInPage
                if (request.scope == MessageSearchScope.DIRECT || request.scope == MessageSearchScope.ALL && id % 2L == 0L)
                    MessageSearchHit.Direct(id, excerpt(id), SearchParticipant(7, "Viewer"), SearchParticipant(8, "Peer"),
                        true, "2026-11-06T12:00:00Z", null)
                else MessageSearchHit.Room(id, excerpt(id), null, SearchRoom(42, "fixture", "Fixture room"),
                    "2026-11-06T12:00:00Z", null)
            }
            return MessageSearchPage(rows, true, request.offset + rows.size)
        }
        companion object { fun excerpt(id: Long) = "<b>Plain fixture excerpt $id</b> @Viewer https://example.test/" }
    }
}
