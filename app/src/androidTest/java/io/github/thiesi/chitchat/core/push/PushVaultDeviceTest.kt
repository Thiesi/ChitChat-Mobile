package io.github.thiesi.chitchat.core.push

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real Keystore and the real connector, with no distributor installed: local behaviour only. */
class PushVaultDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sealedRecordOpensPushesAndRetirementLeavesNothingBehind() {
        val id = UUID.randomUUID().toString()
        val file = File(context.noBackupFilesDir, "push-test-$id.enc")
        val alias = "chitchat.test.push.$id"
        val vault = KeystorePushVault(file, alias)
        val keys = VaultKeyManager(vault)
        val local = PushLocal(vault, UnifiedPushConnector(context, keys)) {}
        try {
            val record = local.prepare("https://chat.example.org", 7)
            assertFalse(file.readBytes().decodeToString().contains(record.auth))
            assertEquals(record.instance, KeystorePushVault(file, alias).read()!!.instance)

            val sealed = WebPushHybridEncrypt.Builder()
                .withAuthSecret(PushRecord.decode(record.auth))
                .withRecipientPublicKey(PushRecord.decode(record.publicKey))
                .build().encrypt("fixture payload".encodeToByteArray(), null)
            assertEquals("fixture payload", keys.decrypt(record.instance, sealed)!!.decodeToString())

            // No distributor is installed, so this exercises the connector's local bookkeeping only.
            UnifiedPushConnector(context, keys).register(record.instance, record.publicKey)
            assertNull(local.retire("https://chat.example.org", 7))
            assertFalse(file.exists())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            assertNull(keys.decrypt(record.instance, sealed))
            assertNoConnectorKeys()
        } finally {
            vault.clear()
        }
    }

    /** The connector's own key table must stay empty: our key manager never writes to it. */
    private fun assertNoConnectorKeys() {
        val db = context.getDatabasePath("unifiedpush-connector")
        if (!db.exists()) return
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='keys'", null).use {
                it.moveToFirst(); if (it.getInt(0) == 0) return
            }
            database.rawQuery("SELECT count(*) FROM keys", null).use {
                it.moveToFirst(); assertEquals(0, it.getInt(0))
            }
        }
    }
}
