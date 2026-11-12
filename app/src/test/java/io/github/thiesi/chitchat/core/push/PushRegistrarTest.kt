package io.github.thiesi.chitchat.core.push

import io.github.thiesi.chitchat.core.network.SessionHttp
import io.github.thiesi.chitchat.core.network.apiJson
import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress
import io.github.thiesi.chitchat.core.session.SavedSession
import io.github.thiesi.chitchat.core.session.SessionPhase
import io.github.thiesi.chitchat.core.session.SessionRepository
import io.github.thiesi.chitchat.core.session.SessionVault
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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

class PushRegistrarTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var sessions: SessionRepository
    private val apis = mutableListOf<SessionHttp>()
    private val vault = MemoryPushVault()
    private val connector = RecordingConnector()
    private val local = PushLocal(vault, connector) {}
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val user = """{"id":7,"username":"Alice","roles":["member"],"session_version":2,"guest":false}"""
    private val vapid = PushRecord.create("https://vapid.example", 1, 0).publicKey

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        sessions = repository()
    }
    private val savedSessions = MemorySessionVault()
    private fun repository() = SessionRepository(savedSessions, push = local) { SessionHttp(it, client).also(apis::add) }
    @After fun tearDown() { apis.forEach(SessionHttp::close); server.close() }

    private fun origin() = address().value
    private fun address() = (ServerAddress.parse(server.url("/").toString()) as AddressResult.Valid).address
    private fun json(body: String, status: Int = 200) = MockResponse.Builder().code(status).body(body)
        .addHeader("Content-Type", "application/json").addHeader("Set-Cookie", "SITE=x; Secure; HttpOnly; Path=/").build()
    private fun bootstrap(person: String, extra: String = "") =
        """{"csrf_token":"$b","user":$person,"registration_enabled":true,"sign_in_providers":[],"lockdown":{"enabled":false}$extra}"""

    private suspend fun login() {
        server.enqueue(json(bootstrap("null").replace(b, a)))
        sessions.connect(address())
        server.enqueue(json("""{"csrf_token":"$b","user":$user,"mfa_required":false}"""))
        sessions.signIn("Alice", "fixture-password")
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        repeat(2) { server.takeRequest(5, TimeUnit.SECONDS) }
    }

    private fun TestScope.registrar(): PushRegistrar = PushRegistrar(local, connector, sessions, backgroundScope, Dispatchers.IO)

    /** Waits in real time off the test thread, so the registrar's continuations can still run. */
    private suspend fun awaitReal(condition: () -> Boolean) = kotlinx.coroutines.withContext(Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(5_000) { while (!condition()) kotlinx.coroutines.delay(10) }
    }

    private fun subscribed(id: Long) = json("""{"subscribed":true,"id":$id,"binding":"bind-$id"}""")

    @Test fun newEndpointSubscribesAndAdoptsTheServersRegistration() = runTest {
        login()
        val registrar = registrar()
        val record = local.prepare(origin(), 7)!!
        server.enqueue(subscribed(12))
        registrar.onNewEndpoint(record.instance, "https://push.example/endpoint", record.publicKey, record.auth)
        registrar.status.first { it == PushPhoneStatus.On }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/push/subscribe.php", request.url.encodedPath)
        assertEquals(b, request.headers["X-CSRF-Token"])
        val body = apiJson.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("https://push.example/endpoint", body.getValue("endpoint").jsonPrimitive.content)
        assertEquals(record.publicKey, body.getValue("p256dh").jsonPrimitive.content)
        assertEquals(record.auth, body.getValue("auth").jsonPrimitive.content)
        assertEquals(PUSH_DEVICE_LABEL, body.getValue("device_label").jsonPrimitive.content)
        assertFalse("replaces_id" in body)
        assertEquals(12L, vault.record!!.registrationId)
        assertEquals("bind-12", vault.record!!.binding)
    }

    @Test fun aChangedEndpointReplacesThePreviousRegistration() = runTest {
        login()
        val registrar = registrar()
        val record = local.prepare(origin(), 7)!!
        local.adopt(record.instance, PushRegistration(12, null))
        server.enqueue(subscribed(13))
        registrar.onNewEndpoint(record.instance, "https://push.example/moved", record.publicKey, record.auth)
        awaitReal { vault.record?.registrationId == 13L }
        val body = apiJson.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
        assertEquals("12", body.getValue("replaces_id").jsonPrimitive.content)
    }

    @Test fun aNamedRefusalIsShownAndNothingIsAdopted() = runTest {
        login()
        val registrar = registrar()
        val record = local.prepare(origin(), 7)!!
        server.enqueue(json("""{"error":{"code":"push_subscription_limit","message":"fixture"}}""", 409))
        registrar.onNewEndpoint(record.instance, "https://push.example/endpoint", record.publicKey, record.auth)
        assertEquals(PushPhoneStatus.Refused(PushSubscribeRefusal.LIMIT),
            registrar.status.first { it is PushPhoneStatus.Refused })
        assertNull(vault.record!!.registrationId)
    }

    @Test fun staleInstancesAndForeignKeysNeverReachTheServer() = runTest {
        login()
        val registrar = registrar()
        val record = local.prepare(origin(), 7)!!
        val other = PushRecord.create(origin(), 7, 0)
        // Callbacks are serialized, so the first request the server sees belongs to the last call.
        registrar.onNewEndpoint("stale-instance", "https://push.example/stale", record.publicKey, record.auth)
        registrar.onNewEndpoint(record.instance, "https://push.example/endpoint", other.publicKey, record.auth)
        registrar.onNewEndpoint(record.instance, "http://push.example/plain", record.publicKey, record.auth)
        assertEquals(PushPhoneStatus.Refused(PushSubscribeRefusal.DESTINATION),
            registrar.status.first { it is PushPhoneStatus.Refused })
        server.enqueue(subscribed(14))
        registrar.onNewEndpoint(record.instance, "https://push.example/endpoint", record.publicKey, record.auth)
        registrar.status.first { it == PushPhoneStatus.On }
        val body = apiJson.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
        assertEquals("https://push.example/endpoint", body.getValue("endpoint").jsonPrimitive.content)
        assertEquals(1, server.requestCount - 2)
    }

    @Test fun anotherAccountsRecordIsWipedWhenThisAccountSignsIn() = runTest {
        val foreign = PushRecord.create("https://elsewhere.example", 8, 0)
        vault.record = foreign
        registrar()
        login()
        awaitReal { vault.record == null }
        assertEquals(listOf(foreign.instance), connector.unregistered)
    }

    @Test fun startNeedsTheServersPushKeyAndRegistersWithIt() = runTest {
        login()
        val registrar = registrar()
        assertFalse("Discovery is unknown until bootstrap reports it", registrar.start(sessions.state.value))
        server.enqueue(json(bootstrap(user, ""","web_push":{"enabled":true,"vapid_public_key":"$vapid"}""")))
        sessions.refresh()
        assertTrue(sessions.state.value.webPush is WebPushDiscovery.Available)
        assertTrue(registrar.start(sessions.state.value))
        val record = vault.record!!
        assertTrue(record.belongsTo(origin(), 7))
        assertEquals(listOf(record.instance to vapid), connector.registered)
        assertEquals(PushPhoneStatus.Waiting, registrar.status.value)
        assertTrue("A second start keeps the same keys", registrar.start(sessions.state.value))
        assertEquals(record.instance, vault.record!!.instance)
    }

    @Test fun aRestoredSessionKeepsTheRecordAndAReplacedOneDropsIt() = runTest {
        login()
        val record = local.prepare(origin(), 7)!!
        // Process restart: the same saved login session is restored and revalidated.
        sessions = repository()
        server.enqueue(json(bootstrap(user)))
        sessions.connect(address())
        assertEquals(SessionPhase.AUTHENTICATED, sessions.state.value.phase)
        assertEquals(record.instance, vault.record!!.instance)
        assertTrue(connector.unregistered.isEmpty())
        // The saved session is gone (expired or unusable) and the server answers anonymously.
        savedSessions.saved = null
        sessions = repository()
        server.enqueue(json(bootstrap("null")))
        sessions.connect(address())
        assertNull(vault.record)
        assertEquals(listOf(record.instance), connector.unregistered)
    }

    @Test fun aSignInWithANewLoginSessionDropsTheOldRecord() = runTest {
        login()
        val record = local.prepare(origin(), 7)!!
        // A fresh sign-in on top of an existing runtime (no sign-out in between) is a new login session.
        sessions = repository()
        savedSessions.saved = null
        login()
        assertNull(vault.record)
        assertEquals(listOf(record.instance), connector.unregistered)
    }

    @Test fun anEndpointArrivingInAColdProcessIsHeldUntilItsSessionIsBack() = runTest {
        login()
        val record = local.prepare(origin(), 7)!!
        sessions = repository() // Cold process: nothing has connected yet.
        val registrar = registrar()
        registrar.onNewEndpoint(record.instance, "https://push.example/rotated", record.publicKey, record.auth)
        awaitReal { vault.record?.pendingEndpoint == "https://push.example/rotated" }
        assertEquals(2, server.requestCount)
        server.enqueue(json(bootstrap(user)))
        server.enqueue(subscribed(21))
        sessions.connect(address())
        awaitReal { vault.record?.registrationId == 21L }
        server.takeRequest(5, TimeUnit.SECONDS) // bootstrap
        val body = apiJson.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
        assertEquals("https://push.example/rotated", body.getValue("endpoint").jsonPrimitive.content)
        assertNull(vault.record!!.pendingEndpoint)
    }

    @Test fun distributorUnregisterCleansUpLocallyAndRevokesOnce() = runTest {
        login()
        val registrar = registrar()
        val record = local.prepare(origin(), 7)!!
        local.adopt(record.instance, PushRegistration(12, null))
        server.enqueue(json("""{"devices":[]}"""))
        registrar.onUnregistered(record.instance)
        awaitReal { server.requestCount == 3 }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/push/revoke-device.php", request.url.encodedPath)
        assertEquals("""{"id":12}""", request.body!!.utf8())
        assertNull(vault.record)
        assertEquals(PushPhoneStatus.Off, registrar.status.value)
        assertTrue("No unregister echo back to the distributor", connector.unregistered.isEmpty())
        registrar.onUnregistered(record.instance) // A repeated callback finds nothing and sends nothing.
        kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(300) }
        assertEquals(3, server.requestCount)
    }

    @Test fun distributorRegistrationFailureIsShownHonestlyAndDropsTheRecord() = runTest {
        login()
        val registrar = registrar()
        val record = local.prepare(origin(), 7)!!
        registrar.onRegistrationFailed("another-instance", org.unifiedpush.android.connector.FailedReason.NETWORK)
        registrar.onRegistrationFailed(record.instance, org.unifiedpush.android.connector.FailedReason.ACTION_REQUIRED)
        assertEquals(PushPhoneStatus.DistributorFailed(DistributorFailure.ACTION_REQUIRED),
            registrar.status.first { it is PushPhoneStatus.DistributorFailed })
        assertNull(vault.record)
        assertEquals(2, server.requestCount)
    }

    @Test fun noKeyMaterialEverReachesTheConnectorStore() = runTest {
        login()
        server.enqueue(json(bootstrap(user, ""","web_push":{"enabled":true,"vapid_public_key":"$vapid"}""")))
        sessions.refresh()
        val registrar = registrar()
        assertTrue(registrar.start(sessions.state.value))
        val record = vault.record!!
        server.enqueue(subscribed(30))
        registrar.onNewEndpoint(record.instance, "https://push.example/endpoint", record.publicKey, record.auth)
        registrar.status.first { it == PushPhoneStatus.On }
        local.retire(origin(), 7)
        // Everything the app hands the connector (whose store is its SQLite database) is an instance name or the
        // server's public VAPID key; the decryption keys stay in the vault, reached only via VaultKeyManager.
        val handed = connector.registered.flatMap { listOf(it.first, it.second) } + connector.unregistered
        for (secret in listOf(record.privateKey, record.auth, record.publicKey)) assertTrue(handed.none { secret in it })
    }

    private class MemorySessionVault : SessionVault {
        @Volatile var saved: SavedSession? = null
        override suspend fun read(): SavedSession? = saved
        override suspend fun save(session: SavedSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    private class MemoryPushVault : PushVault {
        @Volatile var record: PushRecord? = null
        override fun read(): PushRecord? = record
        override fun save(record: PushRecord) { this.record = record }
        override fun clear() { record = null }
    }

    private class RecordingConnector : PushConnector {
        val registered = mutableListOf<Pair<String, String>>()
        val unregistered = mutableListOf<String>()
        override fun register(instance: String, vapidKey: String) { registered += instance to vapidKey }
        override fun unregister(instance: String) { unregistered += instance }
        override fun unregisterAll() {}
    }
}
