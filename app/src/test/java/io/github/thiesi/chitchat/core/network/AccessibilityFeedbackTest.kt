package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

/** P-221: presence-gated, strict https or mailto only; anything else is absent, never a fallback address. */
class AccessibilityFeedbackTest {
    private fun url(json: String?) = accessibilityFeedbackUrl(json?.let { Json.parseToJsonElement(it) as JsonElement })
    private fun of(value: String) = url("""{"url":${Json.encodeToString(String.serializer(), value)}}""")

    @Test fun httpsAndMailtoAddressesArePresent() {
        assertEquals("https://chitchat.example/accessibility", of("https://chitchat.example/accessibility"))
        assertEquals("https://example.org/a11y?from=app#form", of("https://example.org/a11y?from=app#form"))
        assertEquals("mailto:access@example.org", of("mailto:access@example.org"))
        assertEquals("mailto:access@example.org?subject=Accessibility", of("mailto:access@example.org?subject=Accessibility"))
        assertEquals("The server's subject and body pass through untouched", "mailto:a@example.org?subject=App%20feedback&body=Hi%2C",
            of("mailto:a@example.org?subject=App%20feedback&body=Hi%2C"))
        assertEquals("Only the scheme's case changes", "https://Example.org/A11y", of("HTTPS://Example.org/A11y"))
        assertEquals("mailto:Access@Example.org", of("MailTo:Access@Example.org"))
    }

    @Test fun missingNullOrMalformedFieldsAreAbsent() {
        assertNull(url(null))
        assertNull("null field", url("null"))
        assertNull("null url", url("""{"url":null}"""))
        assertNull("no url key", url("""{}"""))
        assertNull("not an object", url("\"https://example.org\""))
        assertNull("not a string", url("""{"url":7}"""))
        assertNull("empty", of(""))
    }

    @Test fun otherSchemesAndUnsafeShapesAreAbsent() {
        for (bad in listOf("http://example.org/a11y", "javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x",
            "content://x", "tel:123", "HTTP://example.org", "https:///nohost", "https://:443/a11y", "https://?q=1", "https://user:secret@example.org/a11y",
            "mailto:", "//example.org/a11y", "example.org/a11y"))
            assertNull(bad, of(bad))
    }

    @Test fun whitespaceControlCharactersAndOverlongAddressesAreAbsent() {
        for (bad in listOf("https://example.org/a 11y", " https://example.org", "https://example.org\n", "https://example.org/\tx",
            "https://example.org/\u0000", "https://example.org/\u007F", "mailto:a@b.org ", "https://example.org/ "))
            assertNull(bad.replace("\n", "\\n"), of(bad))
        val longest = "https://example.org/" + "a".repeat(2048 - "https://example.org/".length)
        assertEquals(longest, of(longest))
        assertNull("over 2,048", of(longest + "a"))
    }

    @Test fun theBootstrapCarriesItLenientlyAndOlderServersHaveNone() {
        fun reply(extra: String) = apiJson.decodeFromString<SessionReply>("""{"csrf_token":"${"a".repeat(64)}","user":null,
            "registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false}$extra}""")
        assertEquals("https://chitchat.example/a11y",
            accessibilityFeedbackUrl(reply(""","accessibility_feedback":{"url":"https://chitchat.example/a11y"}""").accessibilityFeedback))
        assertNull("A v3.10–3.13 server sends no field", accessibilityFeedbackUrl(reply("").accessibilityFeedback))
        assertNull("Turned off by the operator", accessibilityFeedbackUrl(reply(""","accessibility_feedback":null""").accessibilityFeedback))
        assertNull("Malformed never fails the bootstrap", accessibilityFeedbackUrl(reply(""","accessibility_feedback":["odd"]""").accessibilityFeedback))
    }
}
