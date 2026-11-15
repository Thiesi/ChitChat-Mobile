package io.github.thiesi.chitchat.core.session

import org.junit.Assert.*
import org.junit.Test

/** server-update.md Detection (P-180) and the operator note (P-183). */
class ServerUpdateTest {
    private fun facts(schema: Boolean = true, version: String? = "3.10.0", policies: Boolean = false, recoveryKey: Boolean = false) =
        ServerDiscovery(schema, version, policies, recoveryKey)

    @Test fun theRuleAsATable() {
        // (facts, member, needs update, why)
        val rows = listOf(
            Triple(facts(schema = false), false, true) to "no capabilities.schema_version: older than 3.3.0",
            Triple(facts(schema = false, version = "3.12.0", policies = true), true, true) to "schema_version decides first",
            Triple(facts(version = "3.9.0"), false, true) to "below the minimum",
            Triple(facts(version = "3.9.12"), false, true) to "numeric, not text: 3.9.12 < 3.10.0",
            Triple(facts(version = "2.99.99"), false, true) to "older major",
            Triple(facts(version = "3.10.0"), false, false) to "the minimum itself",
            Triple(facts(version = "3.10.1"), false, false) to "newer patch",
            Triple(facts(version = "3.14.0"), true, false) to "newer minor",
            Triple(facts(version = "10.0.0"), false, false) to "numeric, not text: 10 > 3",
            Triple(facts(version = "3.10.0-rc.1+build"), false, false) to "leading major.minor.patch",
            Triple(facts(version = "3.9.0", policies = true, recoveryKey = true), true, true) to "a parsed old version ignores feature keys",
            // Unknown versions: only when every observable session-level key is missing.
            Triple(facts(version = null), false, true) to "unknown, anonymous, no policies",
            Triple(facts(version = null, policies = true), false, false) to "unknown, policies present",
            Triple(facts(version = "custom-build"), false, true) to "unparseable is unknown",
            Triple(facts(version = "v3.10.0"), false, true) to "leading v doesn't parse: unknown, no keys",
            Triple(facts(version = "99999999999999999999.0.0"), false, true) to "overflow is unknown, not a number",
            Triple(facts(version = null, recoveryKey = true), false, true) to "recovery_key counts only when signed in",
            Triple(facts(version = null, recoveryKey = true), true, false) to "signed in: recovery_key present",
            Triple(facts(version = null, policies = true), true, false) to "signed in: policies present",
            Triple(facts(version = null), true, true) to "signed in: both missing",
            // The technical lead's contradictory case: schema_version present, application (or its version) missing.
            Triple(facts(schema = true, version = null, policies = true), true, false) to "schema present, no application, keys present",
            Triple(facts(schema = true, version = null), false, true) to "schema present, no application, keys missing",
            // An explicit "schema_version": null is present (key presence), so the version decides, not a technicality.
            Triple(facts(schema = true, version = "3.12.0"), false, false) to "schema_version null but present, new version",
            Triple(facts(schema = true, version = null, policies = true), false, false) to "schema_version null but present, unknown, keys present",
        )
        for ((row, why) in rows) assertEquals(why, row.third, needsServerUpdate(row.first, member = row.second))
    }

    @Test fun factsComeFromKeyPresenceInTheBody() {
        val body = """{"csrf_token":"x","user":{"id":7,"recovery_key":null},"application":{"name":"Room","version":"3.10.2"},
            "capabilities":{"schema_version":1,"signed_in_sessions":true},"policies":{"rules":null,"privacy":null}}"""
        assertEquals(ServerDiscovery(schemaVersion = true, version = "3.10.2", policies = true, recoveryKey = true), serverDiscovery(body))
        assertEquals("An explicit null schema_version is present", true, serverDiscovery("""{"capabilities":{"schema_version":null}}""")!!.schemaVersion)
        assertEquals("A non-string version is unknown", null, serverDiscovery("""{"capabilities":{"schema_version":1},"application":{"version":3.10}}""")!!.version)
        assertEquals(ServerDiscovery(false, null, false, false), serverDiscovery("""{"csrf_token":"x","user":null}"""))
        assertNull("Not JSON: no facts, no verdict", serverDiscovery("not json"))
        assertNull("Not an object: no facts, no verdict", serverDiscovery("[1]"))
    }

    @Test fun theDisplayedVersionIsBoundedAndPrintable() {
        assertEquals("3.9.0", displayedServerVersion(" 3.9.0 "))
        assertNull(displayedServerVersion(null)); assertNull(displayedServerVersion("")); assertNull(displayedServerVersion("3.9\u0007"))
        assertNull(displayedServerVersion("x".repeat(41)))
    }

    @Test fun theOperatorNoteHoldsTheHostTheMinimumAndTheFoundVersionOnly() {
        assertEquals("Hi! The ChitChat Android app needs our server (chat.example.org) updated to ChitChat 3.10.0 or newer. It currently runs 3.9.0.",
            operatorNote("chat.example.org", "3.9.0"))
        assertTrue(operatorNote("chat.example.org", null).endsWith("It currently runs unknown."))
    }
}
