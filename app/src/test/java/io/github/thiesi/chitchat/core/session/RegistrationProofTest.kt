package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.RegistrationChallenge
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RegistrationProofTest {
    @Test fun disabledChallengeHasNoInventedNonceOrSolution() = runTest {
        val proof = solveRegistration(null)
        assertNull(proof.nonce)
        assertNull(proof.solution)
    }
    @Test fun zeroDifficultyReturnsDecimalZero() = runTest {
        assertEquals("0", solveRegistration(RegistrationChallenge("a".repeat(32), 0)).solution)
    }
    @Test fun solutionMatchesTheServersSha256DecimalCounterContract() = runTest {
        val nonce = "1234abcd".repeat(4)
        val proof = solveRegistration(RegistrationChallenge(nonce, 12))
        assertTrue(proof.solution!!.matches(Regex("[0-9]{1,15}")))
        assertTrue(hasLeadingZeroBits(MessageDigest.getInstance("SHA-256").digest("$nonce:${proof.solution}".encodeToByteArray()), 12))
        assertFalse(proof.toString().contains(nonce))
    }
    @Test fun bitBoundariesUseUnsignedBytes() {
        assertTrue(hasLeadingZeroBits(byteArrayOf(0, 15), 12))
        assertFalse(hasLeadingZeroBits(byteArrayOf(0, 16), 12))
        assertFalse(hasLeadingZeroBits(byteArrayOf(128.toByte(), 0), 1))
        assertTrue(hasLeadingZeroBits(ByteArray(32), 256))
        assertTrue(hasLeadingZeroBits(byteArrayOf(255.toByte()), 0))
    }
    @Test fun malformedNonceAndOutOfContractDifficultyFailClosed() = runTest {
        for (challenge in listOf(RegistrationChallenge("wrong", 16), RegistrationChallenge("a".repeat(32), -1),
                RegistrationChallenge("a".repeat(32), 23))) {
            val failure = try { solveRegistration(challenge); error("Expected rejection") } catch (e: ApiFailure) { e }
            assertEquals(FailureKind.INCOMPATIBLE, failure.kind)
        }
    }
    @Test fun cancellationDoesNotLeaveAnUnrestrictedCpuWorker() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.Default) {
            started.complete(Unit)
            solveRegistration(RegistrationChallenge("a".repeat(32), 22))
        }
        started.await()
        withTimeout(5_000) { worker.cancelAndJoin() }
        assertTrue(worker.isCompleted)
    }
}
