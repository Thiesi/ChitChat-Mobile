package io.github.thiesi.chitchat.feature.policies

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.policies.PolicyDocument
import io.github.thiesi.chitchat.core.policies.PolicyDocumentName
import io.github.thiesi.chitchat.core.policies.PolicyReads
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The document screen's state: memory only, Retry as a fresh read, no stuck Loading (O3a2; review, PR #267). */
@OptIn(ExperimentalCoroutinesApi::class)
class PolicyDocumentViewModelTest {
    private val session = SessionState(server = (ServerAddress.parse("chat.example.test") as AddressResult.Valid).address, generation = 1)
    private val reads = Reads()
    private lateinit var model: PolicyDocumentViewModel

    @Before fun main() { Dispatchers.setMain(UnconfinedTestDispatcher()); model = PolicyDocumentViewModel(reads) }
    @After fun reset() { Dispatchers.resetMain() }

    private suspend fun settled(): PolicyDocumentStatus {
        repeat(200) { val status = model.state.value?.status; if (status != null && status !is PolicyDocumentStatus.Loading) return status
            kotlinx.coroutines.delay(5) }
        return model.state.value!!.status
    }

    @Test fun reopeningTheSameDocumentAndScopeKeepsItAndANewScopeReloads() = runTest {
        model.open(PolicyDocumentName.RULES, session)
        assertTrue(settled() is PolicyDocumentStatus.NotPublished)
        model.open(PolicyDocumentName.RULES, session)
        assertEquals(1, reads.calls)
        model.open(PolicyDocumentName.RULES, session.copy(generation = 2))
        settled()
        assertEquals(2, reads.calls)
    }

    @Test fun closeDropsTheDocumentAndALateResult() = runTest {
        val gate = CompletableDeferred<PolicyDocument>().also { reads.gate = it }
        model.open(PolicyDocumentName.PRIVACY, session)
        assertTrue(model.state.value?.status is PolicyDocumentStatus.Loading)
        model.close()
        gate.complete(PolicyDocument.External("https://example.org/p"))
        assertNull(model.state.value)
    }

    /** A leaving screen's late close (its exit transition) can't wipe the document a newer screen opened (review, PR #267). */
    @Test fun aLateCloseFromALeavingScreenKeepsTheNewerScreensDocument() = runTest {
        val rules = Any(); val privacy = Any(); val rulesAgain = Any()
        model.open(PolicyDocumentName.RULES, session, rules); settled()
        model.open(PolicyDocumentName.PRIVACY, session, privacy) // Back, then the other document, quickly.
        model.close(rules) // The rules screen's dispose arrives after the transition.
        assertEquals(PolicyDocumentName.PRIVACY, model.state.value?.name)
        assertTrue(settled() is PolicyDocumentStatus.NotPublished)
        model.open(PolicyDocumentName.PRIVACY, session, rulesAgain) // The same document reopened by a newer screen.
        model.close(privacy)
        assertNotNull("Still open for the newer screen", model.state.value)
        model.close(rulesAgain)
        assertNull(model.state.value)
    }

    @Test fun retryIsAFreshReadAfterAFailure() = runTest {
        reads.fail = ApiFailure(FailureKind.TIMEOUT)
        model.open(PolicyDocumentName.PRIVACY, session)
        assertTrue(settled() is PolicyDocumentStatus.Failed)
        reads.fail = null
        model.retry()
        assertTrue(settled() is PolicyDocumentStatus.NotPublished)
        assertEquals(2, reads.calls)
    }

    /** A cancellation that isn't this job's own (e.g. thrown by the read) shows Try again, never a dead spinner. */
    @Test fun aStrayCancellationFromTheReadBecomesFailedWithRetry() = runTest {
        reads.cancel = true
        model.open(PolicyDocumentName.RULES, session)
        val status = settled()
        assertTrue(status is PolicyDocumentStatus.Failed)
        assertEquals(FailureKind.EXPIRED, (status as PolicyDocumentStatus.Failed).failure.kind)
        reads.cancel = false
        model.retry()
        assertTrue(settled() is PolicyDocumentStatus.NotPublished)
    }

    private class Reads : PolicyReads {
        var calls = 0
        var gate: CompletableDeferred<PolicyDocument>? = null
        var fail: ApiFailure? = null
        var cancel = false
        override suspend fun policyDocument(name: PolicyDocumentName, expected: SessionState): PolicyDocument {
            calls++
            gate?.let { gate = null; return it.await() }
            fail?.let { throw it }
            if (cancel) throw CancellationException("Session changed")
            return PolicyDocument.NotPublished
        }
    }
}
