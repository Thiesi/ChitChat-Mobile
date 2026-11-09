package io.github.thiesi.chitchat.core.network

import org.junit.Assert.*
import org.junit.Test

class ErrorHintTest {
    private fun error(fields: String) = apiJson.decodeFromString<ErrorEnvelope>(
        """{"error":{"code":"attachment_too_large","message":"private-body"$fields}}""").error

    @Test fun missingNullAndKnownZeroRemainDifferentFacts() {
        assertNull(error("").maxBytes)
        assertNull(error(",\"max_bytes\":null").maxBytes)
        assertEquals(0L, error(",\"max_bytes\":0").maxBytes)
        assertEquals(17L, error(",\"max_bytes\":17").maxBytes)
        assertEquals(90L, error(",\"retry_after_seconds\":90").retryAfterSeconds)
    }

    @Test fun malformedOptionalHintsDoNotEraseTheRefusalCode() {
        for (value in listOf("\"17\"", "-1", "1.5", "true", "[]", "{}", "9223372036854775808")) {
            val parsed = error(",\"max_bytes\":$value,\"retry_after_seconds\":$value")
            assertEquals("attachment_too_large", parsed.code)
            assertNull(parsed.maxBytes)
            assertNull(parsed.retryAfterSeconds)
        }
    }
}
