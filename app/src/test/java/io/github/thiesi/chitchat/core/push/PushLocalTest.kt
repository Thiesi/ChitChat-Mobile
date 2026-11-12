package io.github.thiesi.chitchat.core.push

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PushLocalTest {
    private val origin = "https://chat.example"
    private val vault = MemoryPushVault()
    private val connector = RecordingConnector()
    private var cancels = 0
    private val local = PushLocal(vault, connector) { cancels++ }
    private val keys = VaultKeyManager(vault)

    @Test fun createdRecordIsValidAndNeverPrintsItsKeys() {
        val record = PushRecord.create(origin, 7)
        assertTrue(record.valid())
        assertEquals("PushRecord", record.toString())
        val point = PushRecord.decode(record.publicKey)!!
        assertEquals(65, point.size); assertEquals(4.toByte(), point[0])
        assertNotEquals(PushRecord.create(origin, 7).instance, record.instance)
    }

    @Test fun keyManagerOpensAWebPushMessageOnlyForItsOwnInstance() {
        val record = local.prepare(origin, 7)
        val sealed = WebPushHybridEncrypt.Builder()
            .withAuthSecret(PushRecord.decode(record.auth))
            .withRecipientPublicKey(PushRecord.decode(record.publicKey))
            .build().encrypt("fixture payload".encodeToByteArray(), null)
        assertEquals("fixture payload", keys.decrypt(record.instance, sealed)!!.decodeToString())
        assertNull(keys.decrypt("another-instance", sealed))
        assertEquals(record.publicKey, keys.getPublicKeySet(record.instance)!!.pubKey)
        assertEquals(record.auth, keys.getPublicKeySet(record.instance)!!.auth)
        assertTrue(keys.exists(record.instance))
        keys.generate("another-instance")
        assertFalse("Keys are never minted for an instance we didn't create", keys.exists("another-instance"))
        assertNull(keys.getPublicKeySet("another-instance"))
    }

    @Test fun retireWipesKeysUnregistersAndCancelsNotifications() {
        val record = local.prepare(origin, 7)
        assertTrue(local.adopt(record.instance, PushRegistration(9, "bind")))
        assertEquals(PushPhoneStatus.On, local.status.value)
        assertEquals(9L, local.retire(origin, 7))
        assertNull(vault.record)
        assertFalse(keys.exists(record.instance))
        assertEquals(listOf(record.instance), connector.unregistered)
        assertEquals(1, cancels)
        assertEquals(PushPhoneStatus.Off, local.status.value)
    }

    @Test fun retireForAnotherAccountStillWipesButRevokesNothing() {
        val record = local.prepare(origin, 7)
        local.adopt(record.instance, PushRegistration(9, null))
        assertNull(local.retire(origin, 8))
        assertNull(local.retire("https://other.example", 7))
        assertNull(vault.record)
    }

    @Test fun unreadableRecordDropsEveryConnectorRegistration() {
        local.prepare(origin, 7)
        vault.failReads = true
        val clears = vault.clears
        assertNull(local.retire(origin, 7))
        assertEquals(1, connector.unregisteredAll)
        assertEquals(clears + 1, vault.clears)
    }

    @Test fun clearFailureNeverStopsTheRestOfTheCleanup() {
        val record = local.prepare(origin, 7)
        vault.failClears = true
        local.retire(origin, 7)
        assertEquals(listOf(record.instance), connector.unregistered)
        assertEquals(1, cancels)
    }

    @Test fun lateServerAnswerCannotReviveARetiredRegistration() {
        val first = local.prepare(origin, 7)
        local.retire(origin, 7)
        val second = local.prepare(origin, 7)
        assertNotEquals(first.instance, second.instance)
        assertFalse(local.adopt(first.instance, PushRegistration(9, null)))
        assertNull(vault.record!!.registrationId)
    }

    @Test fun anotherAccountsRecordIsRetiredBeforeANewOne() {
        val first = local.prepare(origin, 7)
        assertEquals(first.instance, local.prepare(origin, 7).instance)
        val second = local.prepare(origin, 8)
        assertNotEquals(first.instance, second.instance)
        assertEquals(listOf(first.instance), connector.unregistered)
        local.retireIfForeign(origin, 8)
        assertEquals(second.instance, vault.record!!.instance)
        local.retireIfForeign(origin, 7)
        assertNull(vault.record)
    }

    @Test fun forgetOnlyTouchesTheMatchingInstance() {
        val record = local.prepare(origin, 7)
        assertNull(local.forget("another-instance", PushPhoneStatus.Off))
        assertNotNull(vault.record)
        assertEquals(record.instance, local.forget(record.instance, PushPhoneStatus.DistributorFailed(DistributorFailure.NETWORK))!!.instance)
        assertNull(vault.record)
        assertEquals(PushPhoneStatus.DistributorFailed(DistributorFailure.NETWORK), local.status.value)
        assertTrue("Forgetting is local: the distributor already dropped it", connector.unregistered.isEmpty())
    }

    @Test fun malformedRecordsAreRejected() {
        val record = PushRecord.create(origin, 7)
        assertFalse(PushRecord("http://chat.example", 7, record.instance, record.privateKey, record.publicKey, record.auth).valid())
        assertFalse(PushRecord(origin, 7, record.instance, record.privateKey, record.publicKey, record.auth, binding = "has space").valid())
        assertFalse(PushRecord(origin, 7, record.instance, record.privateKey, record.auth, record.auth).valid())
        assertFalse(PushRecord(origin, 7, record.instance, record.privateKey, record.publicKey, record.auth, registrationId = 0).valid())
    }

    private class MemoryPushVault : PushVault {
        var record: PushRecord? = null
        var failReads = false
        var failClears = false
        var clears = 0
        override fun read(): PushRecord? { if (failReads) throw IOException("fixture"); return record }
        override fun save(record: PushRecord) { this.record = record }
        override fun clear() { clears++; if (failClears) throw IOException("fixture"); record = null }
    }

    private class RecordingConnector : PushConnector {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        var unregisteredAll = 0
        override fun register(instance: String, vapidKey: String) { registered += instance }
        override fun unregister(instance: String) { unregistered += instance }
        override fun unregisterAll() { unregisteredAll++ }
    }
}
