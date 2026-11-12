package io.github.thiesi.chitchat.core.ui

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import org.junit.Assert.*
import org.junit.Test

class AttachmentSendFailureTest {
    private fun http(status: Int, code: String? = null, retry: Long? = null, max: Long? = null) =
        ApiFailure(FailureKind.HTTP, status, code, retry, max)

    @Test fun definiteOutcomesKeepTheServersMeaning() {
        assertEquals(5L, (attachmentSendFailure(http(413, "attachment_too_large", max = 5), false) as AttachmentSendFailure.TooLarge).maxBytes)
        // A proxy's non-JSON 413 is still a definite size refusal, with an unknown limit.
        assertNull((attachmentSendFailure(http(413), false) as AttachmentSendFailure.TooLarge).maxBytes)
        assertSame(AttachmentSendFailure.TypeRefused, attachmentSendFailure(http(415, "attachment_type_not_allowed"), false))
        assertSame(AttachmentSendFailure.TypeRefused, attachmentSendFailure(http(415, "attachment_type_unknown"), false))
        assertSame(AttachmentSendFailure.Empty, attachmentSendFailure(http(400, "attachment_empty"), false))
        assertEquals(90L, (attachmentSendFailure(http(429, "rate_limited", retry = 90), false) as AttachmentSendFailure.RateLimited).retryAfterSeconds)
        assertNull((attachmentSendFailure(http(429, "rate_limited"), false) as AttachmentSendFailure.RateLimited).retryAfterSeconds)
        assertSame(AttachmentSendFailure.Membership, attachmentSendFailure(http(403, "membership_required"), false))
        assertSame(AttachmentSendFailure.Rejected, attachmentSendFailure(http(400, "validation_error"), false))
        assertSame(AttachmentSendFailure.Unavailable, attachmentSendFailure(http(403, "direct_message_unavailable"), false))
    }

    @Test fun onlyStatuslessTransportFailuresReadAsNotSent() {
        // These reach the card only when the body was provably incomplete; otherwise the state is UNKNOWN.
        listOf(FailureKind.NETWORK, FailureKind.TIMEOUT, FailureKind.TLS).forEach {
            assertSame(AttachmentSendFailure.NotSent, attachmentSendFailure(ApiFailure(it), false))
        }
        assertSame(AttachmentSendFailure.Rejected, attachmentSendFailure(http(502), false))
    }

    @Test fun policyBlockWinsAndNoErrorMeansNoLine() {
        assertSame(AttachmentSendFailure.PolicyChanged, attachmentSendFailure(http(413), true))
        assertNull(attachmentSendFailure(null, false))
    }

    @Test fun captionLimitCountsTrimmedCodePoints() {
        assertFalse(captionTooLong(" " + "a".repeat(4_000) + "\n"))
        assertTrue(captionTooLong("a".repeat(4_001)))
        assertFalse(captionTooLong("📎".repeat(4_000)))
    }
}
