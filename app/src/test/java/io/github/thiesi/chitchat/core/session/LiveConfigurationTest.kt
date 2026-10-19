package io.github.thiesi.chitchat.core.session

import org.junit.Assert.*
import org.junit.Test

class LiveConfigurationTest {
    private val values = listOf("CHITCHAT_TEST_SERVER=https://chat.example.org/", "CHITCHAT_TEST_USERNAME=fixture", "CHITCHAT_TEST_PASSWORD=fixture")
    @Test fun supportsWindowsBomQuotedValuesAndLiteralEqualsWithoutEvaluation() {
        val parsed = parseLiveConfiguration(listOf("\uFEFF" + values[0], "# comment", values[1], "CHITCHAT_TEST_PASSWORD=' literal=fixture#value '", "IGNORED=value"))
        assertEquals(" literal=fixture#value ", parsed["CHITCHAT_TEST_PASSWORD"])
        assertEquals(3, parsed.size)
    }
    @Test fun rejectsMissingAndDuplicateValuesWithoutDisclosingInputs() {
        listOf(values.dropLast(1), values + "CHITCHAT_TEST_PASSWORD=private-fixture").forEach {
            try { parseLiveConfiguration(it); fail("Expected a configuration error") }
            catch (failure: IllegalStateException) { assertFalse(failure.message.orEmpty().contains("private-fixture")) }
        }
    }
    @Test fun emptyValuesAndShellSyntaxAreNotExecuted() {
        listOf(values.dropLast(1) + "CHITCHAT_TEST_PASSWORD=", values + "not-a-key-value-command").forEach {
            try { parseLiveConfiguration(it); fail("Expected a configuration error") } catch (_: IllegalStateException) { /* Expected. */ }
        }
    }
}
