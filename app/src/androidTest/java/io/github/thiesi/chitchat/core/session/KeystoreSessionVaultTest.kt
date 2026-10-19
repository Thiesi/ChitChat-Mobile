package io.github.thiesi.chitchat.core.session

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KeystoreSessionVaultTest {
    @Test fun encryptedSessionSurvivesStoreRecreationAndClearDestroysIt() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "session-test-$id.enc")
        val vault = KeystoreSessionVault(file, "chitchat.test.$id")
        try {
            vault.save(SavedSession("https://chat.example.org/", 7,
                listOf("CUSTOM=fixture-session-secret; path=/; secure; httponly"), 100, 100))
            assertFalse(file.readBytes().decodeToString().contains("fixture-session-secret"))
            val recreated = KeystoreSessionVault(file, "chitchat.test.$id")
            assertEquals(7L, recreated.read()!!.userId)
            assertTrue(recreated.read()!!.cookies.single().contains("fixture-session-secret"))
            val firstCiphertext = file.readBytes()
            recreated.save(recreated.read()!!)
            assertFalse("Every save must use a fresh GCM nonce", firstCiphertext.contentEquals(file.readBytes()))
            recreated.clear()
            assertNull(vault.read())
            assertFalse(file.exists())
        } finally { vault.clear() }
    }

    @Test fun tamperedCiphertextCannotRestoreCredentials() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "session-test-$id.enc")
        val vault = KeystoreSessionVault(file, "chitchat.test.$id")
        try {
            vault.save(SavedSession("https://chat.example.org/", 7, listOf("CUSTOM=fixture"), 100, 100))
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            try { vault.read(); fail("Tampering must fail authentication") } catch (_: IOException) { /* Expected. */ }
        } finally { vault.clear() }
    }
}
