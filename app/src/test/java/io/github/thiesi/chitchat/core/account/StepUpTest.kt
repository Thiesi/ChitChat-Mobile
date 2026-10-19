package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StepUpTest {
    @Test fun alreadyAuthorizedActionDoesNotAskForCredentials() = runTest {
        assertEquals(7, withStepUp({ 7 }, { error("Must not verify") }))
    }

    @Test fun onlyExplicitRejectionPermitsExactlyOneRetry() = runTest {
        var calls = 0; var verifications = 0
        val failure = try {
            withStepUp({ calls++; throw ApiFailure(FailureKind.HTTP, 403, "step_up_required") }, { verifications++ })
            error("Expected second rejection")
        } catch (failure: ApiFailure) { failure }
        assertEquals("step_up_required", failure.code)
        assertEquals(2, calls)
        assertEquals(1, verifications)
    }

    @Test fun timeoutAndOtherPermissionFailuresNeverReplay() = runTest {
        for (failure in listOf(ApiFailure(FailureKind.TIMEOUT), ApiFailure(FailureKind.HTTP, 403, "not_allowed"),
            ApiFailure(FailureKind.HTTP, 500, "step_up_required"))) {
            var calls = 0
            try { withStepUp({ calls++; throw failure }, { error("Must not verify") }) }
            catch (actual: ApiFailure) { assertSame(failure, actual) }
            assertEquals(1, calls)
        }
    }

    @Test fun failedOrCancelledVerificationNeverRetries() = runTest {
        var calls = 0
        try { withStepUp({ calls++; throw ApiFailure(FailureKind.HTTP, 403, "step_up_required") },
            { throw CancellationException("Cancelled") }) }
        catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun successfulVerificationReturnsRetriedActionResult() = runTest {
        var calls = 0
        val result = withStepUp({ if (++calls == 1) throw ApiFailure(FailureKind.HTTP, 403, "step_up_required"); "export" }, {})
        assertEquals("export", result)
        assertEquals(2, calls)
    }
}
