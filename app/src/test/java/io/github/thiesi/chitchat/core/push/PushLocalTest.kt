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
        val record = PushRecord.create(origin, 7, 100)
        assertTrue(record.valid())
        assertEquals("PushRecord", record.toString())
        val point = PushRecord.decode(record.publicKey)!!
        assertEquals(65, point.size); assertEquals(4.toByte(), point[0])
        assertNotEquals(PushRecord.create(origin, 7, 100).instance, record.instance)
    }

    @Test fun keyManagerOpensAWebPushMessageOnlyForItsOwnInstance() {
        val record = prep(7)
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
        val record = prep(7)
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
        val record = prep(7)
        local.adopt(record.instance, PushRegistration(9, null))
        assertNull(local.retire(origin, 8))
        assertNull(local.retire("https://other.example", 7))
        assertNull(vault.record)
    }

    @Test fun unreadableRecordDropsEveryConnectorRegistration() {
        prep(7)
        vault.failReads = true
        val clears = vault.clears
        assertNull(local.retire(origin, 7))
        assertEquals(1, connector.unregisteredAll)
        assertEquals(clears + 1, vault.clears)
    }

    @Test fun clearFailureNeverStopsTheRestOfTheCleanup() {
        val record = prep(7)
        vault.failClears = true
        local.retire(origin, 7)
        assertEquals(listOf(record.instance), connector.unregistered)
        assertEquals(1, cancels)
    }

    @Test fun lateServerAnswerCannotReviveARetiredRegistration() {
        val first = prep(7)
        local.retire(origin, 7)
        val second = prep(7)
        assertNotEquals(first.instance, second.instance)
        assertFalse(local.adopt(first.instance, PushRegistration(9, null)))
        assertNull(vault.record!!.registrationId)
    }

    @Test fun aRecordLivesExactlyAsLongAsItsLoginSession() {
        val first = prep(7)
        local.sessionChanged(origin, 7, 100) // The same saved session restored or revalidated.
        assertEquals(first.instance, vault.record!!.instance)
        assertEquals(first.instance, prep(7).instance)
        local.sessionChanged(origin, 7, 200) // Same account, new login session.
        assertNull(vault.record)
        assertEquals(listOf(first.instance), connector.unregistered)
        val second = prep(7, 200)
        local.sessionChanged(origin, 8, 300) // Another account.
        assertNull(vault.record)
        assertEquals(listOf(first.instance, second.instance), connector.unregistered)
        prep(8, 300)
        local.sessionChanged(origin, null, null) // Anonymous, guest or pending MFA: no account session.
        assertNull(vault.record)
    }

    @Test fun prepareNeedsTheMatchingPublishedSession() {
        assertNull(local.prepare(origin, 7))
        local.sessionChanged(origin, 7, 100)
        assertNull(local.prepare(origin, 8))
        assertNull(local.prepare("https://other.example", 7))
        assertEquals(100L, local.prepare(origin, 7)!!.signedInAt)
    }

    @Test fun forgetOnlyTouchesTheMatchingInstance() {
        val record = prep(7)
        assertNull(local.forget("another-instance", PushPhoneStatus.Off))
        assertNotNull(vault.record)
        assertEquals(record.instance, local.forget(record.instance, PushPhoneStatus.DistributorFailed(DistributorFailure.NETWORK))!!.instance)
        assertNull(vault.record)
        assertEquals(PushPhoneStatus.DistributorFailed(DistributorFailure.NETWORK), local.status.value)
        assertTrue("Forgetting is local: the distributor already dropped it", connector.unregistered.isEmpty())
    }

    @Test fun malformedRecordsAreRejected() {
        val record = PushRecord.create(origin, 7, 100)
        assertFalse(PushRecord("http://chat.example", 7, 100, record.instance, record.privateKey, record.publicKey, record.auth).valid())
        assertFalse(PushRecord(origin, 7, 100, record.instance, record.privateKey, record.publicKey, record.auth, binding = "has space").valid())
        assertFalse(PushRecord(origin, 7, 100, record.instance, record.privateKey, record.auth, record.auth).valid())
        assertFalse(PushRecord(origin, 7, 100, record.instance, record.privateKey, record.publicKey, record.auth, registrationId = 0).valid())
    }

    private fun prep(userId: Long, signedInAt: Long = 100): PushRecord {
        local.sessionChanged(origin, userId, signedInAt)
        return local.prepare(origin, userId)!!
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
