package io.github.thiesi.chitchat.core.session

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import org.junit.Assert.*
import org.junit.Test

class LiveFailureMetadataTest {
    @Test fun directHttpFailureReportsOnlyItsScalarsEvenWhenSessionErrorDiffers() {
        val line = liveFailureMetadata(LiveStep.HISTORY,
            ApiFailure(FailureKind.HTTP, 403, "rules_acceptance_required"),
            ApiFailure(FailureKind.HTTP, 429, "rate_limited"))
        assertEquals("Live failure: step=HISTORY source=thrown kind=HTTP status=403 code=rules_acceptance_required", line)
    }

    @Test fun sessionFailureBehindAnAssertionIsExplicitlyLabelled() {
        val line = liveFailureMetadata(LiveStep.LOGIN, AssertionError("private assertion content"),
            ApiFailure(FailureKind.HTTP, 401, "invalid_credentials"))
        assertEquals("Live failure: step=LOGIN source=session kind=HTTP status=401 code=invalid_credentials", line)
    }

    @Test fun unknownCodesAndThrowableMessagesCannotLeakEvenWhenTokenShaped() {
        for (code in listOf("private_account_name", "123456789", "body and credentials", "session_cookie_secret")) {
            val line = liveFailureMetadata(LiveStep.ROOM_LIST,
                ApiFailure(FailureKind.HTTP, 403, code), null)
            assertTrue(line.endsWith("code=unknown"))
            assertFalse(line.contains(code))
        }
        val line = liveFailureMetadata(LiveStep.REVALIDATION, IllegalStateException("private response body"), null)
        assertEquals("Live failure: step=REVALIDATION source=unavailable kind=unknown status=unknown code=unknown", line)
    }

    @Test fun missingOrInvalidStatusStaysUnknown() {
        for (status in listOf(null, 0, 999)) {
            assertTrue(liveFailureMetadata(LiveStep.ROOM_DETAIL,
                ApiFailure(FailureKind.NETWORK, status), null).contains("kind=NETWORK status=unknown code=unknown"))
        }
    }
}
