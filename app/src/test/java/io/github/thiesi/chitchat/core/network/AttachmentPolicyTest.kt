package io.github.thiesi.chitchat.core.network

import io.github.thiesi.chitchat.core.media.AttachmentPolicy
import io.github.thiesi.chitchat.core.media.UploadRateLimit
import org.junit.Assert.*
import org.junit.Test

class AttachmentPolicyTest {
    private val policy = """{"max_bytes":2097152,"allowed_mime_types":["image/png","application/pdf"],
        "previewable_mime_types":["image/png"],"rate_limits":{
        "room":{"maximum_attempts":10,"window_seconds":3600},
        "direct_message":{"maximum_attempts":4,"window_seconds":900}}}"""
    private fun session(field: String) = apiJson.decodeFromString<SessionReply>(
        """{"csrf_token":"${"a".repeat(64)}","user":null,"registration_enabled":true,
            "sign_in_providers":[],"lockdown":{"enabled":false}$field}""").attachmentPolicy

    @Test fun missingNullAndZeroStayDistinctWithoutGuessedDefaults() {
        assertSame(AttachmentPolicy.Unknown, session(""))
        assertSame(AttachmentPolicy.Unavailable, session(",\"attachments\":null"))
        val zero = session(",\"attachments\":" + policy.replace("2097152", "0")) as AttachmentPolicy.Known
        assertEquals(0L, zero.maxBytes)
        val small = session(",\"attachments\":" + policy.replace("2097152", "17")) as AttachmentPolicy.Known
        assertEquals(17L, small.maxBytes) // No assumed 1KiB/10MiB minimum.
    }

    @Test fun operatorValuesAndIndependentAttemptQuotasSurviveBootstrap() {
        val value = session(",\"attachments\":$policy") as AttachmentPolicy.Known
        assertEquals(2097152L, value.maxBytes)
        assertEquals(setOf("image/png", "application/pdf"), value.allowedMimeTypes)
        assertEquals(setOf("image/png"), value.previewableMimeTypes)
        assertEquals(UploadRateLimit(10, 3600), value.rateLimits!!.room)
        assertEquals(UploadRateLimit(4, 900), value.rateLimits.directMessage)
    }

    @Test fun absentQuotaStaysUnknownAndFutureFieldsAreIgnored() {
        val value = session(",\"attachments\":" +
            """{"max_bytes":9,"allowed_mime_types":[],"previewable_mime_types":[],"future":true}""") as AttachmentPolicy.Known
        assertNull(value.rateLimits)
        assertTrue(value.allowedMimeTypes.isEmpty())
        assertTrue(value.previewableMimeTypes.isEmpty())
    }

    @Test fun mediaTypeTokensAreCaseInsensitiveWithoutGuessingAdditionalTypes() {
        val value = session(",\"attachments\":" + policy.replace("image/png", "IMAGE/PNG")
            .replace("application/pdf", "Application/PDF")) as AttachmentPolicy.Known
        assertEquals(setOf("image/png", "application/pdf"), value.allowedMimeTypes)
        assertEquals(setOf("image/png"), value.previewableMimeTypes)
    }

    @Test fun unusablePolicyDisablesOnlyUploadsWithoutGuessedDefaults() {
        val invalid = listOf("true", "[]", "{}", "\"policy\"",
            policy.replace("2097152", "\"2097152\""), policy.replace("2097152", "-1"),
            policy.replace("2097152", "1.5"), policy.replace("2097152", "9223372036854775808"),
            policy.replace("[\"image/png\"]", "[\"image/gif\"]"),
            policy.replace("\"maximum_attempts\":10", "\"maximum_attempts\":0"),
            policy.replace("\"window_seconds\":900", "\"window_seconds\":\"900\""),
            policy.replace("\"image/png\"", "false"),
            policy.replace("\"image/png\"", "\"image/png;secret=private\""))
        for (body in invalid) {
            assertSame(AttachmentPolicy.Invalid, session(",\"attachments\":$body"))
        }
    }
}
