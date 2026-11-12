package io.github.thiesi.chitchat.core.push

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PushProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var api: SessionHttp
    private val csrf = "a".repeat(64)

    @Before fun start() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()); start()
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        api = SessionHttp((ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address,
            OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build(), "ChitChat-Mobile/test (Android 15)")
    }
    @After fun stop() { api.close(); server.close() }
    private fun reply(body: String) = server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build())
    private fun preferences(direct: String = "", quiet: String = "null") =
        """{"mentioned_push_enabled":true,"pinged_push_enabled":false$direct,"quiet_hours":$quiet}"""

    @Test fun settingsAreOneGetWithPreferencesAndDevicesWithoutCsrf() = runTest {
        reply("""{"preferences":${preferences(""","direct_message_push_enabled":false""", """{"start":22,"end":7,"timezone":"Europe/Berlin"}""")},
            "devices":[{"id":4,"user_agent":"Mozilla/5.0 (X11; Linux x86_64) Firefox/128.0","device_label":null,"current_session":true,
            "created_at":"2026-07-18T09:12:00+00:00","last_used_at":null}]}""")
        val settings = api.pushSettings()
        val request = server.takeRequest()
        assertEquals("GET", request.method); assertEquals("/api/v1/push/preferences.php", request.url.encodedPath)
        assertNull(request.headers["X-CSRF-Token"])
        assertTrue(settings.preferences.mentioned); assertFalse(settings.preferences.pinged)
        assertEquals(false, settings.preferences.direct)
        assertEquals(QuietHours(22, 7, "Europe/Berlin"), settings.preferences.quietHours)
        val device = settings.devices.single()
        assertEquals(4L, device.id); assertTrue(device.currentSession); assertNull(device.lastUsedAt)
    }

    @Test fun missingOrNullDirectChoiceMeansTheServerDoesNotOfferIt() = runTest {
        for (direct in listOf("", ""","direct_message_push_enabled":null""")) {
            reply("""{"preferences":${preferences(direct)},"devices":[]}""")
            assertNull(api.pushSettings().preferences.direct)
        }
    }

    @Test fun malformedFactsAreIncompatibleNotGuessed() = runTest {
        val bad = listOf(
            """{"preferences":${preferences(""","direct_message_push_enabled":"true"""")},"devices":[]}""",
            """{"preferences":${preferences(quiet = """{"start":24,"end":7,"timezone":"UTC"}""")},"devices":[]}""",
            """{"preferences":${preferences()},"devices":[{"id":0,"created_at":"x"}]}""",
            """{"preferences":${preferences()},"devices":[{"id":1,"created_at":"x"},{"id":1,"created_at":"y"}]}""",
            """{"preferences":${preferences()},"devices":[{"id":1,"created_at":"x","current_session":"yes"}]}""")
        for (body in bad) {
            reply(body)
            val failure = runCatching { api.pushSettings() }.exceptionOrNull() as? ApiFailure
            assertEquals(body, FailureKind.INCOMPATIBLE, failure?.kind)
        }
    }

    @Test fun updateSendsTheWholeChoiceIncludingQuietHoursWithCsrf() = runTest {
        reply("""{"preferences":${preferences()}}""")
        api.updatePushPreferences(pushPreferencesBody(PushPreferences(true, false, null, null)), csrf)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/api/v1/push/update-preferences.php", request.url.encodedPath)
        assertEquals(csrf, request.headers["X-CSRF-Token"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        // The server clears quiet hours whenever they are omitted, so all three are always explicit.
        assertEquals(JsonNull, body["quiet_hours_start"]); assertEquals(JsonNull, body["quiet_hours_end"])
        assertEquals(JsonNull, body["quiet_hours_timezone"])
        assertFalse("An unoffered DM choice is never written", "direct_message_push_enabled" in body)
    }

    @Test fun updateKeepsAnExistingWindowAndAnOfferedDmChoice() {
        val body = Json.parseToJsonElement(pushPreferencesBody(PushPreferences(false, true, true, QuietHours(22, 7, "Europe/Berlin")))).jsonObject
        assertEquals("22", body["quiet_hours_start"]!!.jsonPrimitive.content)
        assertEquals("7", body["quiet_hours_end"]!!.jsonPrimitive.content)
        assertEquals("Europe/Berlin", body["quiet_hours_timezone"]!!.jsonPrimitive.content)
        assertEquals("true", body["direct_message_push_enabled"]!!.jsonPrimitive.content)
        assertEquals("false", body["mentioned_push_enabled"]!!.jsonPrimitive.content)
    }

    @Test fun revokeIsByIdAndReturnsTheRemainingDevices() = runTest {
        reply("""{"devices":[]}""")
        assertTrue(api.revokePushDevice(4, csrf).isEmpty())
        val request = server.takeRequest()
        assertEquals("/api/v1/push/revoke-device.php", request.url.encodedPath)
        assertEquals("""{"id":4}""", request.body!!.utf8()); assertEquals(csrf, request.headers["X-CSRF-Token"])
    }

    @Test fun deviceLabelsNeverGuessAModel() {
        fun device(agent: String?, label: String? = null) = PushDevice(1, agent, label, false, "2026-01-01T00:00:00+00:00", null)
        assertEquals("ChitChat for Android", pushDeviceLabel(device("whatever", "ChitChat for Android")))
        assertEquals("Firefox on Linux", pushDeviceLabel(device("Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0")))
        assertEquals("Chrome on Android", pushDeviceLabel(device("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")))
        assertEquals("Edge on Windows", pushDeviceLabel(device("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 Edg/131.0")))
        assertEquals("ChitChat-Mobile/1.0 (Android 15)", pushDeviceLabel(device("ChitChat-Mobile/1.0 (Android 15)")))
        assertTrue(pushDeviceLabel(device("x".repeat(80)))!!.length <= 48)
        assertNull(pushDeviceLabel(device(null)))
    }
}
