package io.github.thiesi.chitchat.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StepUpTimestampTest {
    @Test fun dateAtomUtcTimestampsValidateOnAndroid() {
        requireActiveStepUp(status("+00:00"), "password")
    }

    @Test fun zuluTimestampsValidateOnAndroid() {
        requireActiveStepUp(status("Z"), "password")
    }

    @Test fun malformedTimestampsFailClosedOnAndroid() {
        for (invalid in listOf(
            StepUpStatus(true, "password", "invalid", "2026-10-20T00:10:00+00:00", 600),
            StepUpStatus(true, "password", "2026-10-20T00:00:00+00:00", "invalid", 600),
            status(""),
            StepUpStatus(true, "password", null, null, 600),
        )) {
            try {
                requireActiveStepUp(invalid, "password")
                fail("Malformed timestamps must reject step-up success")
            } catch (failure: ApiFailure) {
                assertEquals(FailureKind.INCOMPATIBLE, failure.kind)
            }
        }
    }

    private fun status(offset: String) = StepUpStatus(
        active = true,
        method = "password",
        verifiedAt = "2026-10-20T00:00:00$offset",
        expiresAt = "2026-10-20T00:10:00$offset",
        maxAgeSeconds = 600,
    )
}
