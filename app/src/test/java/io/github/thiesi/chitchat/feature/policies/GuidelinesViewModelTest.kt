package io.github.thiesi.chitchat.feature.policies

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.Lockdown
import io.github.thiesi.chitchat.core.network.SessionUser
import io.github.thiesi.chitchat.core.policies.PolicyDiscovery
import io.github.thiesi.chitchat.core.policies.PolicyLink
import io.github.thiesi.chitchat.core.policies.RulesAcceptResult
import io.github.thiesi.chitchat.core.policies.RulesOperations
import io.github.thiesi.chitchat.core.policies.RulesStatus
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.PublicSession
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionState
import io.github.thiesi.chitchat.core.settings.APP_GUIDELINES_VERSION
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptance
import io.github.thiesi.chitchat.core.settings.GuidelinesAcceptanceStore
import io.github.thiesi.chitchat.core.settings.covers
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The guidelines screen's I agree (play-readiness.md §1, P-170/171): local record, one accept POST, never resent. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidelinesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address
    private val rules = PolicyLink("https://chat.example.test/rules", 4, "2026-11-08T12:00:00+00:00")
    private fun session(required: Boolean?, published: Boolean = true) = SessionState(server = server, generation = 3,
        phase = SessionPhase.AUTHENTICATED, user = SessionUser(7, "Viewer", listOf("member"), 1, false),
        public = PublicSession(true, emptyList(), Lockdown(false),
            policies = PolicyDiscovery(rules.takeIf { published }, null, null)),
        rules = required?.let { RulesStatus(if (it) 1 else 4, null, it) })

    private class Store : GuidelinesAcceptanceStore {
        override val accepted = MutableStateFlow<Set<GuidelinesAcceptance>>(emptySet())
        override suspend fun record(acceptance: GuidelinesAcceptance) { accepted.value = accepted.value + acceptance }
        override suspend fun forgetServer(origin: String) { accepted.value = accepted.value.filterNot { it.origin == origin }.toSet() }
    }

    private class Rules(initial: SessionState) : RulesOperations {
        override val state = MutableStateFlow(initial)
        val posts = mutableListOf<Int>()
        var refreshes = 0
        var result: suspend () -> RulesAcceptResult = { RulesAcceptResult.Accepted(RulesStatus(4, null, false)) }
        var afterRefresh: (SessionState) -> SessionState = { it }
        override suspend fun acceptRules(version: Int, expected: SessionState): RulesAcceptResult { posts += version; return result() }
        override suspend fun refresh() { refreshes++; state.value = afterRefresh(state.value) }
    }

    @Test fun withoutServerRulesIAgreeRecordsOnlyTheLocalAcceptance() = runTest(dispatcher) {
        for (current in listOf(session(required = null, published = false), session(required = false), session(required = null))) {
            val store = Store(); val ops = Rules(current); val model = GuidelinesViewModel(ops, store)
            model.agree(current); advanceUntilIdle()
            assertTrue(store.accepted.value.covers(server.value, 7))
            assertEquals("No POST unless the server requires acceptance", emptyList<Int>(), ops.posts)
        }
    }

    @Test fun aRequiredAcceptancePostsTheShownVersionOnceThenRecords() = runTest(dispatcher) {
        val current = session(required = true)
        val store = Store(); val ops = Rules(current); val gate = CompletableDeferred<Unit>()
        ops.result = { gate.await(); RulesAcceptResult.Accepted(RulesStatus(4, null, false)) }
        val model = GuidelinesViewModel(ops, store)
        model.agree(current); advanceUntilIdle()
        assertTrue(model.state.value.saving)
        assertFalse("Nothing is recorded before the server confirms", store.accepted.value.covers(server.value, 7))
        model.agree(current); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("One POST of the shown version; a second tap while saving sends nothing", listOf(4), ops.posts)
        assertEquals(setOf(GuidelinesAcceptance(server.value, 7, APP_GUIDELINES_VERSION)), store.accepted.value)
        assertFalse(model.state.value.saving)
    }

    @Test fun outdatedReReadsTheSessionAndAsksAgainWithoutRecording() = runTest(dispatcher) {
        val current = session(required = true)
        val store = Store(); val ops = Rules(current).apply { result = { RulesAcceptResult.Outdated } }
        val model = GuidelinesViewModel(ops, store)
        model.agree(current); advanceUntilIdle()
        assertEquals(1, ops.refreshes)
        assertTrue(model.state.value.outdated)
        assertEquals(7L, model.state.value.account)
        assertFalse(store.accepted.value.covers(server.value, 7))
        assertEquals("Never accepted automatically", listOf(4), ops.posts)
    }

    @Test fun unknownMovesOnOnlyWhenTheReReadSessionNoLongerRequiresAcceptance() = runTest(dispatcher) {
        val current = session(required = true)
        val failure = ApiFailure(FailureKind.TIMEOUT)
        val stillRequired = Rules(current).apply { result = { RulesAcceptResult.Unknown(failure) } }
        val storeA = Store(); val modelA = GuidelinesViewModel(stillRequired, storeA)
        modelA.agree(current); advanceUntilIdle()
        assertEquals(1, stillRequired.refreshes)
        assertFalse(storeA.accepted.value.covers(server.value, 7))
        assertEquals(failure, modelA.state.value.failure)
        assertFalse("I agree is enabled again", modelA.state.value.saving)

        val committed = Rules(current).apply {
            result = { RulesAcceptResult.Unknown(failure) }
            afterRefresh = { it.copy(rules = RulesStatus(4, null, false)) }
        }
        val storeB = Store(); val modelB = GuidelinesViewModel(committed, storeB)
        modelB.agree(current); advanceUntilIdle()
        assertTrue(storeB.accepted.value.covers(server.value, 7))
        assertEquals("Settled by the GET, never a resend", listOf(4), committed.posts)
    }

    @Test fun aRefusedAcceptStaysOnTheScreenWithItsFailure() = runTest(dispatcher) {
        val current = session(required = true)
        val failure = ApiFailure(FailureKind.HTTP, 403, "forbidden")
        val store = Store(); val ops = Rules(current).apply { result = { RulesAcceptResult.Failed(failure) } }
        val model = GuidelinesViewModel(ops, store)
        model.agree(current); advanceUntilIdle()
        assertEquals(failure, model.state.value.failure)
        assertEquals(0, ops.refreshes)
        assertFalse(store.accepted.value.covers(server.value, 7))
    }

    /** PR #273 review: after a failed store read the flow completes; a later successful I agree must still open the app. */
    @Test fun aSuccessfulRecordCountsEvenAfterTheStoreReadFailed() = runTest(dispatcher) {
        val current = session(required = null, published = false)
        val written = mutableListOf<GuidelinesAcceptance>()
        val store = object : GuidelinesAcceptanceStore {
            override val accepted = kotlinx.coroutines.flow.flow<Set<GuidelinesAcceptance>> { throw java.io.IOException("read failed") }
            override suspend fun record(acceptance: GuidelinesAcceptance) { written += acceptance }
            override suspend fun forgetServer(origin: String) = Unit
        }
        val model = GuidelinesViewModel(Rules(current), store)
        advanceUntilIdle()
        assertEquals("A failed read shows the screen", emptySet<GuidelinesAcceptance>(), model.accepted.value)
        model.agree(current); advanceUntilIdle()
        assertEquals(1, written.size)
        assertTrue(model.accepted.value!!.covers(server.value, 7))
    }
}
