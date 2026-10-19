package io.github.thiesi.chitchat.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.thiesi.chitchat.core.network.apiJson
import io.github.thiesi.chitchat.core.server.ServerAddress
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
class SavedSession(
    val origin: String,
    val userId: Long,
    val cookies: List<String>,
    val signedInAt: Long,
    val lastValidatedAt: Long,
    val schema: Int = 1,
) {
    fun usableFor(server: ServerAddress, now: Long): Boolean = schema == 1 && origin == server.value &&
        userId > 0 && cookies.isNotEmpty() && cookies.size <= 32 && signedInAt >= 0 &&
        lastValidatedAt >= signedInAt && now >= lastValidatedAt &&
        now - lastValidatedAt < 7L * 24 * 60 * 60 * 1000 && now - signedInAt < 30L * 24 * 60 * 60 * 1000
}

interface SessionVault {
    suspend fun read(): SavedSession?
    suspend fun save(session: SavedSession)
    suspend fun clear()
}

/** Platform AES-GCM with a non-exportable Keystore key. Never writes plaintext to disk. */
class KeystoreSessionVault internal constructor(private val file: File, private val alias: String) : SessionVault {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "session.v1.enc"), "chitchat.session.v1")

    override suspend fun read(): SavedSession? = storage {
        if (!file.exists() && !File(file.path + ".bak").exists()) return@storage null
        if (file.length() > 65_536) throw IOException("Invalid saved session")
        val bytes = AtomicFile(file).openRead().use { input ->
            // Bound the actual stream too, including AtomicFile's recovered backup.
            val buffer = ByteArray(65_537)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > 65_536) throw IOException("Invalid saved session")
            buffer.copyOf(count)
        }
        if (bytes.size !in 30..65_536 || bytes[0] != 1.toByte()) throw IOException("Invalid saved session")
        val key = keys().getKey(alias, null) as? SecretKey ?: throw IOException("Session key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        apiJson.decodeFromString<SavedSession>(cipher.doFinal(bytes, 13, bytes.size - 13).decodeToString())
    }

    override suspend fun save(session: SavedSession) = storage {
        val plaintext = apiJson.encodeToString(session).encodeToByteArray()
        if (plaintext.size > 60_000) throw IOException("Session exceeds storage limit")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext)
        plaintext.fill(0)
        check(cipher.iv.size == 12)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(byteArrayOf(1))
            output.write(cipher.iv)
            output.write(encrypted)
            atomic.finishWrite(output)
        } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        // Either removing the ciphertext or destroying its key prevents restoration.
        var keyRemoved = false
        try { keys().deleteEntry(alias); keyRemoved = true } catch (_: Exception) { /* Try file removal too. */ }
        AtomicFile(file).delete()
        if (!keyRemoved && (file.exists() || File(file.path + ".bak").exists())) {
            throw IOException("Could not clear saved session")
        }
    }

    private fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey = (keys().getKey(alias, null) as? SecretKey) ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()

    private suspend fun <T> storage(action: () -> T): T = withContext(Dispatchers.IO) {
        try { action() } catch (_: Exception) { throw IOException("Secure session storage unavailable") }
    }

    private companion object { val AAD = "ChitChat/native-session/v1".encodeToByteArray() }
}
