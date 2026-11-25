package io.github.thiesi.chitchat.feature.search

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.search.MessageSearchHit
import io.github.thiesi.chitchat.core.search.MessageSearchOperations
import io.github.thiesi.chitchat.core.search.MessageSearchPage
import io.github.thiesi.chitchat.core.search.MessageSearchRequest
import io.github.thiesi.chitchat.core.search.MessageSearchScope
import io.github.thiesi.chitchat.core.search.SearchRoom
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.RateBudget
import io.github.thiesi.chitchat.core.session.RateWait
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.ui.LocalMonotonicClock
import io.github.thiesi.chitchat.feature.auth.TestSessions
import io.github.thiesi.chitchat.feature.direct.DirectInboxScreen
import io.github.thiesi.chitchat.feature.direct.DirectInboxViewModel
import io.github.thiesi.chitchat.feature.direct.DirectThreadViewModel
import io.github.thiesi.chitchat.testing.FontScaleTestActivity
import io.github.thiesi.chitchat.testing.NativeChatFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #323b3b (PL list 9–10): the search waits on their real screens, in place of the old rate line: message search with
 * the line under its scope, the earlier results still shown; the DM people search with the line under the search field.
 * Captures `rate-search-*` (light, default scale), collected into `rate-presentation`, taken before the asserts.
 * The behaviour (definite, no request during a known wait, the query kept) is covered by the JVM tests; no network.
 */
class RateSearchPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun reset() { FontScaleTestActivity.requestedFontScale = 1f }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_000_000L
    private val full get() = context.getString(R.string.rate_wait_search,
        context.resources.getQuantityString(R.plurals.wait_about_minutes, 5, 5))

    private class SearchOps(wait: Long) : MessageSearchOperations {
        override val session = MutableStateFlow(SessionState(
            server = (ServerAddress.parse("https://fixture.example.test") as AddressResult.Valid).address,
            phase = SessionPhase.AUTHENTICATED, generation = 1, user = SessionUser(7, "Viewer", listOf("member"), 1, false),
            rateWaits = mapOf(RateBudget.MESSAGE_SEARCH to RateWait(wait, 1, 7))))
        var failure: ApiFailure? = null
        override suspend fun search(expected: SessionState, request: MessageSearchRequest): MessageSearchPage {
            failure?.let { throw it }
            return MessageSearchPage((1L..3L).map { MessageSearchHit.Room(it, "Fixture result $it", null,
                SearchRoom(43, "fixture", "Fixture room"), "2026-11-25T12:00:00Z", null) }, false, null)
        }
    }

    /** 9: message search after a refused search: the line in place of the old rate line, the results kept. */
    @Test fun messageSearchWithTheLine() {
        val ops = SearchOps(now + 300_000)
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            lateinit var model: MessageSearchViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity, MessageSearchViewModel.factory(ops))[MessageSearchViewModel::class.java]
                activity.setContent { CompositionLocalProvider(LocalMonotonicClock provides { now }) {
                    ChitChatTheme(dark = false) {
                        val session by ops.session.collectAsState()
                        MessageSearchScreen(model, session, "entry", refreshSession = {}, isCurrentEntry = { true })
                    }
                } }
            }
            compose.waitUntil(5_000) { model.state.value.entry != null }
            val entry = checkNotNull(model.state.value.entry)
            compose.runOnIdle { model.scope(entry, MessageSearchScope.ROOMS); model.query(entry, "fixture"); model.search(entry) }
            compose.waitUntil(5_000) { model.state.value.results != null && !model.state.value.busy }
            compose.runOnIdle { ops.failure = ApiFailure(FailureKind.HTTP, 429, "rate_limited", 300); model.search(entry) }
            compose.waitUntil(5_000) { model.state.value.rateLimit != null && !model.state.value.busy }
            compose.waitUntil(5_000) { description("message_search_rate_line") == full }
            capture("rate-search-messages-light-default")
            assertEquals(full, description("message_search_rate_line"))
            assertTrue("The results stay", model.state.value.results != null)
            assertTrue("No error line beside it", model.state.value.error == null)
        }
    }

    /** 10: the DM people search after a refused lookup: the line under the search field, the query kept. */
    @Test fun peopleSearchWithTheLine() {
        val sessions = TestSessions()
        val api = NativeChatFixtures(sessions)
        ActivityScenario.launch(FontScaleTestActivity::class.java).use { scenario ->
            lateinit var inbox: DirectInboxViewModel
            scenario.onActivity { activity ->
                runBlocking { sessions.signIn("Viewer", "fixture-password") }
                val user = checkNotNull(sessions.state.value.user)
                sessions.state.value = sessions.state.value.copy(chatPolicy = api.policy, privacy = api.privacy,
                    rateWaits = mapOf(RateBudget.DIRECT_MESSAGE_USER_SEARCH to RateWait(now + 300_000, sessions.state.value.generation, user.id)))
                inbox = ViewModelProvider(activity, DirectInboxViewModel.factory(api.directs))[DirectInboxViewModel::class.java]
                val thread = ViewModelProvider(activity, DirectThreadViewModel.factory(api.directs))[DirectThreadViewModel::class.java]
                activity.setContent { CompositionLocalProvider(LocalMonotonicClock provides { now }) {
                    ChitChatTheme(dark = false) {
                        DirectInboxScreen(inbox, thread, onRefreshSession = {}, isCurrentEntry = { true }, onSelect = {})
                    }
                } }
            }
            compose.waitUntil(5_000) { inbox.state.value.inbox != null && !inbox.state.value.stale && !inbox.state.value.busy }
            compose.runOnIdle {
                api.directs.searchFailure = ApiFailure(FailureKind.HTTP, 429, "rate_limited", 300)
                inbox.updateQuery("Pe"); inbox.search()
            }
            compose.waitUntil(5_000) { inbox.state.value.searchLimit != null && !inbox.state.value.searching }
            compose.waitUntil(5_000) { description("dm_search_rate_line") == full }
            capture("rate-search-people-light-default")
            assertEquals(full, description("dm_search_rate_line"))
            assertEquals("The query stays", "Pe", inbox.state.value.query)
        }
    }

    private fun description(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .singleOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: checkNotNull(context.getExternalFilesDir(null))
        val folder = java.io.File(output, "ccm-rate-presentation")
        check(folder.isDirectory || folder.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { java.io.File(folder, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
