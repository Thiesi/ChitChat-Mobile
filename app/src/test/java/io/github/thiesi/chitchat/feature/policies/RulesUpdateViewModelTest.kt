package io.github.thiesi.chitchat.feature.policies

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.policies.PolicyDiscovery
import io.github.thiesi.chitchat.core.policies.PolicyDocument
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.policies.PolicyLink
import io.github.thiesi.chitchat.core.policies.PolicyReads
import io.github.thiesi.chitchat.core.policies.RulesAcceptResult
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.policies.RulesStatus
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Re-acceptance (O3b3a): visibility, Not now and its comebacks, one accept per choice, and "What changed". */
@OptIn(ExperimentalCoroutinesApi::class)
class RulesUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private fun session(required: Boolean = true, refused: Boolean = false, refusal: Long = 0, version: Int = 4, generation: Long = 3) =
        SessionState(server = server, generation = generation, phase = SessionPhase.AUTHENTICATED,
            user = SessionUser(7, "Viewer", listOf("member"), 1, false),
            public = PublicSession(true, emptyList(), Lockdown(false),
                policies = PolicyDiscovery(PolicyLink("https://chat.example.test/rules", version, "2026-11-16T10:00:00+00:00"), null, null)),
            rules = RulesStatus(1, null, required), rulesRefused = refused, rulesRefusal = refusal)

    private class Rules(initial: SessionState) : RulesOperations {
        override val state = MutableStateFlow(initial)
        val posts = mutableListOf<Int>()
        var refreshes = 0
        var result: RulesAcceptResult = RulesAcceptResult.Accepted(RulesStatus(4, null, false))
        var afterRefresh: (SessionState) -> SessionState = { it }
        override suspend fun acceptRules(version: Int, expected: SessionState): RulesAcceptResult { posts += version; return result }
        override suspend fun refresh() { refreshes++; state.value = afterRefresh(state.value) }
    }
    private class Reads(var document: PolicyDocument? = null, var failure: ApiFailure? = null) : PolicyReads {
        var calls = 0
        override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState): PolicyDocument {
            calls++; failure?.let { throw it }; return document!!
        }
    }

    @Test fun itShowsOnlyWhileRequiredOrRefusedAndWithAPublishedVersion() {
        val model = RulesUpdateViewModel(Rules(session()), Reads())
        assertTrue(model.visible(session()))
        assertTrue("A refusal shows it even when the status hasn't caught up", model.visible(session(required = false, refused = true)))
        assertFalse(model.visible(session(required = false)))
        assertFalse("Nothing to accept without a published version",
            model.visible(session().copy(public = session().public!!.copy(policies = PolicyDiscovery(null, null, null)))))
        assertFalse("The server-update screen wins", model.visible(session().copy(updateRequired = true)))
    }

    @Test fun notNowStaysDismissedForTheSameVersionAndComesBackOnRefusalReviewOrANewerVersion() {
        val model = RulesUpdateViewModel(Rules(session()), Reads())
        model.notNow(session())
        assertFalse(model.visible(session()))
        assertFalse("A refresh with the same version still required doesn't nag", model.visible(session().copy(bootstrapSequence = 9)))
        assertTrue("A new 403 refusal", model.visible(session(refused = true, refusal = 1)))
        assertTrue("A newer required version", model.visible(session(version = 5)))
        assertTrue("Another session generation", model.visible(session(generation = 4)))
        model.review()
        assertTrue("Review", model.visible(session()))
    }

    @Test fun iAgreePostsTheShownVersionOnceAndNeverResendsTheRefusedWrite() = runTest(dispatcher) {
        val current = session(refused = true, refusal = 1)
        val ops = Rules(current)
        val model = RulesUpdateViewModel(ops, Reads())
        model.agree(current); model.agree(current); advanceUntilIdle()
        assertEquals("One POST of the shown version; the refused write is never resent here", listOf(4), ops.posts)
        assertFalse(model.state.value.saving)
        assertNull(model.state.value.failure)
    }

    @Test fun outdatedReReadsAndAsksAgainUnknownSettlesByTheSessionGet() = runTest(dispatcher) {
        val current = session()
        val outdated = Rules(current).apply { result = RulesAcceptResult.Outdated }
        val a = RulesUpdateViewModel(outdated, Reads())
        a.agree(current); advanceUntilIdle()
        assertEquals(1, outdated.refreshes); assertTrue(a.state.value.outdated); assertEquals(listOf(4), outdated.posts)

        val failure = ApiFailure(FailureKind.TIMEOUT)
        val stillRequired = Rules(current).apply { result = RulesAcceptResult.Unknown(failure) }
        val b = RulesUpdateViewModel(stillRequired, Reads())
        b.agree(current); advanceUntilIdle()
        assertEquals(1, stillRequired.refreshes); assertEquals(failure, b.state.value.failure); assertFalse(b.state.value.saving)

        val committed = Rules(current).apply {
            result = RulesAcceptResult.Unknown(failure)
            afterRefresh = { it.copy(rules = RulesStatus(4, null, false), rulesRefused = false) }
        }
        val c = RulesUpdateViewModel(committed, Reads())
        c.agree(current); advanceUntilIdle()
        assertNull("No longer required after the GET: no failure line, the dialog closes", c.state.value.failure)
        assertEquals(listOf(4), committed.posts)
    }

    @Test fun whatChangedLoadsOncePerVersionAndAFailureOmitsTheCard() = runTest(dispatcher) {
        val current = session()
        val reads = Reads(PolicyDocument.Hosted("# Rules", 4, null, "  Be nicer.  "))
        val model = RulesUpdateViewModel(Rules(current), reads)
        model.loadSummary(current); advanceUntilIdle()
        model.loadSummary(current.copy(rulesRefusal = 2)); advanceUntilIdle()
        assertEquals("Be nicer.", model.state.value.summary)
        assertEquals("One GET per version", 1, reads.calls)
        val failing = RulesUpdateViewModel(Rules(current), Reads(failure = ApiFailure(FailureKind.NETWORK)))
        failing.loadSummary(current); advanceUntilIdle()
        assertNull(failing.state.value.summary)
        val blank = RulesUpdateViewModel(Rules(current), Reads(PolicyDocument.Hosted("# Rules", 4, null, "   ")))
        blank.loadSummary(current); advanceUntilIdle()
        assertNull("An empty summary shows no card", blank.state.value.summary)
    }
}
