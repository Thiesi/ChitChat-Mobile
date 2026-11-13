package io.github.thiesi.chitchat.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

class InstallationNameTest {
    private fun name(json: String?) = installationName(json?.let { Json.parseToJsonElement(it) as JsonElement })

    @Test fun aNameIsTrimmedAndBoundedOrNullSoTheHostIsUsed() {
        assertEquals("The Common Room", name("""{"name":"  The Common Room ","version":"3.9.0"}"""))
        assertNull(name(null))
        assertNull("Not an object", name("\"The Common Room\""))
        assertNull("Not a string", name("""{"name":7}"""))
        assertNull("Blank", name("""{"name":"   "}"""))
        assertNull("Too long", name("""{"name":"${"x".repeat(81)}"}"""))
        assertNull("Control characters", name("""{"name":"Room\u0007"}"""))
        assertEquals("x".repeat(80), name("""{"name":"${"x".repeat(80)}"}"""))
    }

    @Test fun aMalformedApplicationNeverFailsTheBootstrap() {
        val json = Json { ignoreUnknownKeys = true }
        val reply = json.decodeFromString<SessionReply>("""{"csrf_token":"${"a".repeat(64)}","user":null,
            "registration_enabled":false,"sign_in_providers":[],"lockdown":{"enabled":false},"application":["odd"]}""")
        assertNull(installationName(reply.application))
    }
}
