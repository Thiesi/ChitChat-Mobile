package io.github.thiesi.chitchat.core.push

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import io.github.thiesi.chitchat.core.network.apiJson
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.keys.KeyManager

/**
 * This phone's one push registration, scoped to the server origin and account that turned it on.
 * Holds the Web Push decryption keys, so it lives only in [PushVault]; never logged, never in bundles.
 */
@Serializable
class PushRecord(
    val origin: String,
    val userId: Long,
    /** Random per turn-on; the connector's instance name. Unrelated to any account identifier. */
    val instance: String,
    /** Raw 32-byte P-256 private scalar, base64url. */
    val privateKey: String,
    /** 65-byte uncompressed P-256 point, base64url: the subscription's p256dh. */
    val publicKey: String,
    /** 16-byte auth secret, base64url. */
    val auth: String,
    val registrationId: Long? = null,
    val binding: String? = null,
    val schema: Int = 1,
) {
    fun belongsTo(origin: String, userId: Long) = this.origin == origin && this.userId == userId

    fun registered(registration: PushRegistration) = PushRecord(origin, userId, instance, privateKey, publicKey, auth,
        registration.id, registration.binding, schema)

    fun valid(): Boolean = schema == 1 && userId > 0 && origin.startsWith("https://") &&
        instance.length in 1..64 && decode(privateKey)?.size == 32 &&
        decode(publicKey)?.let { it.size == 65 && it[0] == 4.toByte() } == true && decode(auth)?.size == 16 &&
        (registrationId == null || registrationId > 0) &&
        binding?.let { b -> b.length in 1..64 && b.all { it.code in 0x21..0x7e } } != false

    override fun toString() = "PushRecord"

    companion object {
        /** Fresh keys for a new turn-on. Software keys, sealed at rest by [KeystorePushVault]. */
        fun create(origin: String, userId: Long, random: SecureRandom = SecureRandom()): PushRecord {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
                .generateKeyPair()
            val public = pair.public as ECPublicKey
            val point = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
            val secret = ByteArray(16).also(random::nextBytes)
            return PushRecord(origin, userId, UUID.randomUUID().toString(),
                encode(fixed((pair.private as ECPrivateKey).s)), encode(point), encode(secret))
        }

        private fun fixed(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> throw IllegalStateException("Unexpected key size")
            }
        }

        internal fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        internal fun decode(value: String): ByteArray? =
            try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) { null }
    }
}

/** Synchronous by design: the connector calls [KeyManager] on its receiver thread. */
interface PushVault {
    fun read(): PushRecord?
    fun save(record: PushRecord)
    fun clear()
}

/** The session vault's AES-GCM pattern with its own non-exportable Keystore key and file. */
class KeystorePushVault internal constructor(private val file: File, private val alias: String) : PushVault {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "push.v1.enc"), "chitchat.push.v1")

    @Synchronized override fun read(): PushRecord? = storage {
        if (!file.exists() && !File(file.path + ".bak").exists()) return@storage null
        val bytes = AtomicFile(file).openRead().use { input ->
            val buffer = ByteArray(16_385)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }
        if (bytes.size !in 30..16_384 || bytes[0] != 1.toByte()) throw IOException("Invalid push record")
        val key = keys().getKey(alias, null) as? SecretKey ?: throw IOException("Push key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(bytes, 13, bytes.size - 13)
        try { apiJson.decodeFromString<PushRecord>(plaintext.decodeToString()).takeIf { it.valid() } }
        finally { plaintext.fill(0) }
    }

    @Synchronized override fun save(record: PushRecord) = storage {
        require(record.valid())
        val plaintext = apiJson.encodeToString(record).encodeToByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(plaintext)
            check(cipher.iv.size == 12)
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(byteArrayOf(1)); output.write(cipher.iv); output.write(encrypted)
                atomic.finishWrite(output)
            } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        } finally { plaintext.fill(0) }
    }

    /** Destroying the key alone already makes the record unreadable; the file goes too. */
    @Synchronized override fun clear() {
        var keyRemoved = false
        try { keys().deleteEntry(alias); keyRemoved = true } catch (_: Exception) { /* Try file removal too. */ }
        AtomicFile(file).delete()
        if (!keyRemoved && (file.exists() || File(file.path + ".bak").exists())) throw IOException("Could not clear push record")
    }

    private fun keys(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey = (keys().getKey(alias, null) as? SecretKey) ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()

    private fun <T> storage(action: () -> T): T =
        try { action() } catch (_: Exception) { throw IOException("Secure push storage unavailable") }

    private companion object { val AAD = "ChitChat/push-record/v1".encodeToByteArray() }
}

/**
 * The connector's key store, backed by [PushVault] instead of its own SQLite tables, so that
 * wiping the record wipes the keys. Only the record's own instance is known; keys are created
 * with the record before registering, so [generate] never mints keys for an unknown instance.
 */
class VaultKeyManager(private val vault: PushVault) : KeyManager {
    private fun record(instance: String): PushRecord? =
        try { vault.read()?.takeIf { it.instance == instance } } catch (_: IOException) { null }

    override fun exists(instance: String): Boolean = record(instance) != null

    override fun generate(instance: String) {
        // Keys exist from turn-on. An unknown instance is not ours; leaving it keyless makes the connector fail it.
    }

    override fun getPublicKeySet(instance: String): PublicKeySet? = record(instance)?.let { PublicKeySet(it.publicKey, it.auth) }

    override fun decrypt(instance: String, sealed: ByteArray): ByteArray? {
        val record = record(instance) ?: return null
        val privateKey = PushRecord.decode(record.privateKey) ?: return null
        return try {
            WebPushHybridDecrypt.Builder()
                .withAuthSecret(PushRecord.decode(record.auth))
                .withRecipientPublicKey(PushRecord.decode(record.publicKey))
                .withRecipientPrivateKey(privateKey)
                .build().decrypt(sealed, null)
        } finally { privateKey.fill(0) }
    }

    override fun delete(instance: String) {
        if (record(instance) != null) try { vault.clear() } catch (_: IOException) { /* Key removal is best effort here. */ }
    }
}
