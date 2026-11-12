package io.github.thiesi.chitchat.core.push

import io.github.thiesi.chitchat.core.network.ApiFailure
import io.github.thiesi.chitchat.core.network.FailureKind
import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.network.apiJson
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

    private val point = PushRecord.create("https://chat.example", 1, 0).publicKey
    private val secret = "AAECAwQFBgcICQoLDA0ODw"

    @Test fun vapidKeyMustBeTheConnectorsForm() {
        assertEquals(point, validVapidKey(point))
        assertEquals(point, validVapidKey("$point="))
        assertNull(validVapidKey(point.dropLast(1)))
        assertNull(validVapidKey("C" + point.drop(1))) // First byte 0x08, not a 0x04 uncompressed point.
        assertNull(validVapidKey(point.replace(point[5], '+')))
        assertNull(validVapidKey(null))
    }

    @Test fun discoveryIsAvailableOnlyForAnExplicitOnWithAValidKey() {
        fun parse(json: String) = apiJson.decodeFromString(WebPushDiscoverySerializer, json)
        assertEquals(point, (parse("""{"enabled":true,"vapid_public_key":"$point"}""") as WebPushDiscovery.Available).vapidKey)
        for (json in listOf("""{"enabled":false,"vapid_public_key":"$point"}""", """{"enabled":"true","vapid_public_key":"$point"}""",
            """{"enabled":true}""", """{"enabled":true,"vapid_public_key":null}""", """{"enabled":true,"vapid_public_key":"short"}""",
            "null", "[]")) assertEquals(json, WebPushDiscovery.Unavailable, parse(json))
    }

    @Test fun endpointAndKeysAreCheckedBeforeLeavingThePhone() {
        val keys = validEndpointKeys("https://push.example/up/abc?x=1", "$point=", secret)!!
        assertEquals(point, keys.p256dh); assertEquals("PushEndpointKeys", keys.toString())
        for (endpoint in listOf("http://push.example/a", "https://user@push.example/a", "https://push.example/a#f",
            "https:///a", "https://push.example/" + "a".repeat(2048), "https://push.example/ä", "not a uri"))
            assertNull(endpoint, validEndpointKeys(endpoint, point, secret))
        assertNull(validEndpointKeys("https://push.example/a", secret, secret))
        assertNull(validEndpointKeys("https://push.example/a", point, point))
        assertNull(validEndpointKeys("https://push.example/a", point, "%%%"))
    }

    @Test fun subscribeBodyCarriesTheFixedLabelAndOptionalReplacement() {
        val keys = validEndpointKeys("https://push.example/a", point, secret)!!
        val first = apiJson.parseToJsonElement(pushSubscribeBody(keys, null)).jsonObject
        assertEquals(setOf("endpoint", "p256dh", "auth", "device_label"), first.keys)
        assertEquals("ChitChat for Android", first.getValue("device_label").jsonPrimitive.content)
        assertEquals("4", apiJson.parseToJsonElement(pushSubscribeBody(keys, 4)).jsonObject.getValue("replaces_id").jsonPrimitive.content)
    }

    @Test fun subscribeReplyIsStrict() = runTest {
        reply("""{"subscribed":true,"id":12,"binding":"b-1"}""")
        val registration = api.subscribePush("{}", csrf)
        assertEquals(12L, registration.id); assertEquals("b-1", registration.binding)
        val request = server.takeRequest()
        assertEquals("/api/v1/push/subscribe.php", request.url.encodedPath); assertEquals(csrf, request.headers["X-CSRF-Token"])
        reply("""{"subscribed":true,"id":13}""")
        assertNull(api.subscribePush("{}", csrf).binding)
        for (bad in listOf("""{"subscribed":"true","id":1}""", """{"subscribed":false,"id":1}""", """{"subscribed":true,"id":0}""",
            """{"subscribed":true}""", """{"subscribed":true,"id":1,"binding":""}""", """{"subscribed":true,"id":1,"binding":"${"x".repeat(65)}"}""",
            """{"subscribed":true,"id":1,"binding":"has space"}""")) {
            reply(bad)
            val failure = try { api.subscribePush("{}", csrf); null } catch (f: ApiFailure) { f }
            assertNotNull(bad, failure)
        }
    }

    @Test fun onlyNamedRefusalsAreDefinite() {
        fun refusal(status: Int?, code: String?, kind: FailureKind = FailureKind.HTTP) = ApiFailure(kind, status, code).subscribeRefusal()
        assertEquals(PushSubscribeRefusal.CONFLICT, refusal(409, "push_subscription_conflict"))
        assertEquals(PushSubscribeRefusal.LIMIT, refusal(409, "push_subscription_limit"))
        assertEquals(PushSubscribeRefusal.PROVIDER_UNAVAILABLE, refusal(503, "push_provider_unavailable"))
        assertEquals(PushSubscribeRefusal.DESTINATION, refusal(400, "validation_error"))
        assertEquals(PushSubscribeRefusal.RATE_LIMITED, refusal(429, null))
        assertEquals(PushSubscribeRefusal.OTHER, refusal(403, "guest_not_allowed"))
        assertNull(refusal(503, null)); assertNull(refusal(500, "x"))
        assertNull(refusal(null, null, FailureKind.NETWORK)); assertNull(refusal(null, null, FailureKind.TIMEOUT))
        assertNull(refusal(200, null, FailureKind.INCOMPATIBLE))
    }
}
