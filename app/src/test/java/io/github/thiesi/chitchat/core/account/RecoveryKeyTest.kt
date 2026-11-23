package io.github.thiesi.chitchat.core.account

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/** Recovery key protocol (O4a1; recovery-key.md P-184/P-188, password-recovery.md). */
class RecoveryKeyTest {
    private val key = "A7K2-B9QX-C4MZ-D8RT-E2WN-F6HP"
    private fun user(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test fun statusIsUnknownWhenMissingNotApplicableWhenNullAndStrictOtherwise() {
        assertEquals(RecoveryKeyStatus.Unknown, recoveryKeyStatus(null))
        assertEquals(RecoveryKeyStatus.Unknown, recoveryKeyStatus(user("""{"id":7}""")))
        assertEquals(RecoveryKeyStatus.NotApplicable, recoveryKeyStatus(user("""{"recovery_key":null}""")))
        assertEquals(RecoveryKeyStatus.Known(true, "2026-11-08T12:00:00+00:00"),
            recoveryKeyStatus(user("""{"recovery_key":{"exists":true,"created_at":"2026-11-08T12:00:00+00:00"}}""")))
        assertEquals(RecoveryKeyStatus.Known(false, null), recoveryKeyStatus(user("""{"recovery_key":{"exists":false,"created_at":null}}""")))
        for (bad in listOf("""{"recovery_key":{"exists":"true","created_at":null}}""", """{"recovery_key":{"created_at":null}}""",
            """{"recovery_key":{"exists":true,"created_at":null}}""", """{"recovery_key":"yes"}""",
            """{"recovery_key":{"exists":true,"created_at":42}}"""))
            assertEquals(bad, RecoveryKeyStatus.Unknown, recoveryKeyStatus(user(bad)))
    }

    @Test fun aKeyIsAcceptedOnlyInTheCanonicalShapeAndNeverPrinted() {
        val parsed = registrationRecoveryKey("""{"csrf_token":"x","user":{},"recovery_key":"$key"}""")
        assertEquals(key, parsed?.value)
        assertFalse("The key never appears in a debug string", parsed.toString().contains("A7K2"))
        assertEquals("RecoveryKey(redacted)", parsed.toString())
        for (bad in listOf("a7k2-b9qx-c4mz-d8rt-e2wn-f6hp", "A7K2B9QXC4MZD8RTE2WNF6HP", "A7K2-B9QX", "\"\"", "42"))
            assertNull(bad, registrationRecoveryKey("""{"recovery_key":${if (bad.startsWith("\"") || bad == "42") bad else "\"$bad\""}}"""))
        assertNull(registrationRecoveryKey("""{"user":{}}"""))
        val created = createdRecoveryKey(user("""{"recovery_key":"$key","created_at":"2026-11-16T10:00:00+00:00"}"""))
        assertFalse(created.toString().contains("A7K2"))
    }

    @Test fun aSuccessReplyWithoutBothFieldsMayHaveCommittedSoItIsIncompatible() {
        for (body in listOf("""{"recovery_key":"$key"}""", """{"created_at":"2026-11-16T10:00:00+00:00"}""", "{}"))
            assertEquals(FailureKind.INCOMPATIBLE, runCatching { createdRecoveryKey(user(body)) }.exceptionOrNull().let { (it as ApiFailure).kind })
    }

    private fun created() = CreatedRecoveryKey(RecoveryKey(key), "2026-11-16T10:00:00+00:00")

    @Test fun aCreatedKeyIsReturnedAndStepUpRetriesExactlyOnce() = runTest {
        var posts = 0; var verifications = 0
        val result = requestRecoveryKey(create = {
            posts++; if (posts == 1) throw ApiFailure(FailureKind.HTTP, 403, "step_up_required"); created()
        }, verify = { verifications++ }, before = null, reread = { error("No read on success") })
        assertTrue(result is RecoveryKeyResult.Created)
        assertEquals(2, posts); assertEquals(1, verifications)
    }

    @Test fun definiteRefusalsStayTypedWithOnePost() = runTest {
        for ((failure, expected) in listOf(
            ApiFailure(FailureKind.HTTP, 409, "password_account_required") to RecoveryKeyResult.PasswordAccountRequired,
            ApiFailure(FailureKind.HTTP, 429, "rate_limited", retryAfterSeconds = 120) to RecoveryKeyResult.RateLimited(120),
            ApiFailure(FailureKind.HTTP, 429, "rate_limited") to RecoveryKeyResult.RateLimited(null),
            // #323a: the local gate reads the same, with the remaining wait.
            ApiFailure(FailureKind.HTTP, 429, "rate_limited", 119, localGate = true) to RecoveryKeyResult.RateLimited(119))) {
            var posts = 0
            assertEquals(expected, requestRecoveryKey({ posts++; throw failure }, { error("No step-up") }, null, { error("No read") }))
            assertEquals(1, posts)
        }
        val forbidden = ApiFailure(FailureKind.HTTP, 403, "forbidden")
        assertEquals(RecoveryKeyResult.Failed(forbidden), requestRecoveryKey({ throw forbidden }, { error("No step-up") }, null, { error("No read") }))
    }

    @Test fun anAmbiguousResultIsResolvedByOneReadComparingCreatedAtNeverByResending() = runTest {
        val before = "2026-11-01T10:00:00+00:00"
        for ((reread, expected) in listOf<Pair<RecoveryKeyStatus?, UnknownResolution>>(
            RecoveryKeyStatus.Known(true, "2026-11-16T10:00:00+00:00") to UnknownResolution.CREATED_ELSEWHERE,
            RecoveryKeyStatus.Known(true, before) to UnknownResolution.UNCHANGED,
            RecoveryKeyStatus.Known(false, null) to UnknownResolution.UNCHANGED,
            null to UnknownResolution.UNRESOLVED,
            RecoveryKeyStatus.Unknown to UnknownResolution.UNRESOLVED)) {
            for (failure in listOf(ApiFailure(FailureKind.TIMEOUT), ApiFailure(FailureKind.HTTP, 503), ApiFailure(FailureKind.INCOMPATIBLE))) {
                var posts = 0; var reads = 0
                val result = requestRecoveryKey({ posts++; throw failure }, { error("No step-up") }, before, { reads++; reread })
                assertEquals(RecoveryKeyResult.Unknown(expected), result)
                assertEquals("Never resent", 1, posts); assertEquals("One session read", 1, reads)
            }
        }
    }
}
