package io.github.thiesi.chitchat.core.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server prefill P2 (TL conditions, PL rulings): extraction of `chitchat_server` from the Play referrer, acceptance
 * exactly [ServerAddress.parse]'s, and the bounded read: once per process, only while no server is saved and nobody is
 * signed in, settled for good by any definitive answer, a saved server or a sign-in; an invalid value always silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallReferrerTest {
    @Test fun theReferrerGivesItsServerValueOnly() {
        assertEquals("chat.example.org", referrerServer("chitchat_server=chat.example.org"))
        assertEquals("chat.example.org:8443",
            referrerServer("utm_source=web&utm_medium=badge&chitchat_server=chat.example.org%3A8443"))
        assertEquals("bücher.example", referrerServer("chitchat_server=b%C3%BCcher.example"))
    }

    @Test fun anythingButExactlyOneNonBlankValueIsNothing() {
        for (referrer in listOf(null, "", "utm_source=google-play&utm_medium=organic", "chitchat_server=",
            "chitchat_server", "chitchat_server=%20", "chitchat_server=a.org&chitchat_server=b.org", "Chitchat_server=a.org",
            "chitchat_server=" + "a".repeat(2_100))) {
            assertNull(referrer, referrerServer(referrer))
        }
    }

    @Test fun acceptanceIsServerAddressParseExactly() {
        for (value in listOf("http%3A%2F%2Fa.org", "a.org%2Fpath", "user%40a.org", "a%20b.org")) {
            val raw = referrerServer("chitchat_server=$value")!!
            assertTrue(value, ServerAddress.parse(raw) is AddressResult.Invalid)
        }
    }

    @Test fun theAnswerNeverPrintsTheReferrer() {
        assertEquals("Referrer(redacted)", ReferrerAnswer.Referrer("chitchat_server=private.example.org").toString())
    }

    private class Flag : ReferrerFlag {
        override val done = MutableStateFlow(false)
        var marks = 0
        override suspend fun markDone() { marks++; done.value = true }
    }

    private class Reader(private val answer: suspend () -> ReferrerAnswer) : InstallReferrerReads {
        var reads = 0
        override suspend fun read(): ReferrerAnswer { reads++; return answer() }
    }

    private fun TestScope.prefill(reader: Reader, flag: Flag = Flag(), links: PendingServerLink = PendingServerLink()) =
        Triple(ReferrerPrefill(reader, flag, links, this), flag, links)

    @Test fun aValidReferrerIsOfferedOnceAndSettlesTheFlag() = runTest {
        val reader = Reader { ReferrerAnswer.Referrer("utm_source=web&chitchat_server=chat.example.org") }
        val (prefill, flag, links) = prefill(reader)
        prefill.consider { true }
        prefill.consider { true }
        advanceUntilIdle()
        assertEquals("One read per process", 1, reader.reads)
        assertEquals("chat.example.org", links.state.value?.address?.displayHost())
        assertTrue(flag.done.value)
    }

    @Test fun anInvalidOrMissingValueIsSilentAndSettled() = runTest {
        for (referrer in listOf("chitchat_server=not%20a%20host", "utm_source=google-play", null)) {
            val (prefill, flag, links) = prefill(Reader { ReferrerAnswer.Referrer(referrer) })
            prefill.consider { true }
            advanceUntilIdle()
            assertNull("Never offered: $referrer", links.state.value)
            assertTrue("Definitive: $referrer", flag.done.value)
        }
    }

    @Test fun featureNotSupportedAndOtherDefinitiveAnswersEndItSilently() = runTest {
        val (prefill, flag, links) = prefill(Reader { ReferrerAnswer.Definitive })
        prefill.consider { true }
        advanceUntilIdle()
        assertNull(links.state.value)
        assertTrue(flag.done.value)
    }

    @Test fun aTransientAnswerLeavesItForALaterLaunchButNotThisProcess() = runTest {
        val reader = Reader { ReferrerAnswer.Retry }
        val (prefill, flag, links) = prefill(reader)
        prefill.consider { true }
        advanceUntilIdle()
        prefill.consider { true }
        advanceUntilIdle()
        assertEquals(1, reader.reads)
        assertFalse("Not settled: a later launch reads again", flag.done.value)
        assertNull(links.state.value)
        // A later launch (a new process) reads again, with the flag still unset.
        val again = Reader { ReferrerAnswer.Referrer("chitchat_server=chat.example.org") }
        val (next, _, nextLinks) = prefill(again, flag)
        next.consider { true }
        advanceUntilIdle()
        assertEquals(1, again.reads)
        assertEquals("chat.example.org", nextLinks.state.value?.address?.displayHost())
    }

    @Test fun neverReadAfterSignInOrASavedServerAndThatSettlesIt() = runTest {
        val reader = Reader { ReferrerAnswer.Referrer("chitchat_server=chat.example.org") }
        val (prefill, flag, links) = prefill(reader)
        prefill.consider { false }
        advanceUntilIdle()
        assertEquals(0, reader.reads)
        assertTrue(flag.done.value)
        prefill.consider { true }
        advanceUntilIdle()
        assertEquals("Settled for good", 0, reader.reads)
        assertNull(links.state.value)
    }

    @Test fun aSignInOrSavedServerWhileReadingDropsTheAnswer() = runTest {
        val answer = CompletableDeferred<ReferrerAnswer>()
        var eligible = true
        val (prefill, flag, links) = prefill(Reader { answer.await() })
        prefill.consider { eligible }
        advanceUntilIdle()
        eligible = false
        answer.complete(ReferrerAnswer.Referrer("chitchat_server=chat.example.org"))
        advanceUntilIdle()
        assertNull("Re-checked on arrival", links.state.value)
        assertTrue(flag.done.value)
    }

    @Test fun aLinkArrivingAfterTheReferrerWinsUnderThePendingRule() = runTest {
        val (prefill, _, links) = prefill(Reader { ReferrerAnswer.Referrer("chitchat_server=referrer.example.org") })
        prefill.consider { true }
        advanceUntilIdle()
        val link = ServerLink.from("chitchat://connect?server=link.example.org")
        links.offer(link)
        assertEquals("The newest replaces it: one pending link, never two", link, links.state.value)
    }

    @Test fun aSettledFlagMeansNoReadAtAll() = runTest {
        val reader = Reader { ReferrerAnswer.Referrer("chitchat_server=chat.example.org") }
        val flag = Flag().apply { done.value = true }
        val (prefill, _, links) = prefill(reader, flag)
        prefill.consider { true }
        advanceUntilIdle()
        assertEquals(0, reader.reads)
        assertEquals(0, flag.marks)
        assertNull(links.state.value)
    }
}
