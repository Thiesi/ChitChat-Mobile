package io.github.thiesi.chitchat.core.media

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The private copies behind Share and Open (received files F5; files-media.md "Share out", TL decisions of 2026-11-08 and
 * 2026-11-20; privacy-policy.md "What the app stores on your phone").
 *
 * Layout: `[root]/<accountKey>/<random>/<sanitized name>`, where [root] is the cache subfolder the FileProvider exposes
 * and nothing else. The account key is a hash of origin and account ID, so no identifier appears in a path. Each copy
 * has its own random directory, so server file names never collide and are never a path.
 *
 * Lifetime: a copy expires after [TTL_MILLIS]; expired copies go at the next app start or the next share or open
 * ([sweep]). Cancel before handoff and integrity failures delete at once ([discard]). The end of an account's session
 * deletes its whole folder ([deleteAccount]), and a session change keeps only the current account's ([keepOnly]). A
 * sweep never deletes an in-flight copy (one still being written or verified). [revoke] runs for every file before it's
 * deleted, so a receiver holding a grant loses it with the file.
 */
internal class ShareCopies(private val root: File, private val clock: () -> Long = System::currentTimeMillis,
    private val revoke: (File) -> Unit = {}) {

    /** One copy on disk. [file] is where the download writes; only [ShareCopies] deletes it. */
    class Copy internal constructor(val accountKey: String, val directory: File, val file: File) {
        override fun toString() = "ShareCopy"
    }

    private val random = SecureRandom()
    private val inFlight = mutableSetOf<File>()
    /** A session event has already said which account is current; the start-up guess from the vault then stands aside. */
    private var decided = false

    /** A fresh, empty copy for [accountKey] under [name] (sanitized here), marked in flight until [handedOff] or [discard]. */
    @Synchronized fun create(accountKey: String, name: String?): Copy {
        val account = File(root, accountKey)
        var directory: File
        do {
            val bytes = ByteArray(16).also(random::nextBytes)
            directory = File(account, bytes.joinToString("") { "%02x".format(it) })
        } while (directory.exists())
        check(directory.mkdirs()) { "Couldn't create a share directory" }
        inFlight += directory
        return Copy(accountKey, directory, File(directory, FileNames.sanitizedFileName(name)))
    }

    /** Verified and handed to another app: it now lives under the ordinary lifetime rules. */
    @Synchronized fun handedOff(copy: Copy) {
        inFlight -= copy.directory
        copy.directory.setLastModified(clock())
    }

    /** Cancel before handoff, an integrity failure, no app for it, or any failure: deleted now. */
    @Synchronized fun discard(copy: Copy) {
        inFlight -= copy.directory
        delete(copy.directory)
    }

    /** At app start and at each new share or open: every expired copy that isn't in flight. */
    @Synchronized fun sweep() {
        val now = clock()
        accounts().forEach { account ->
            account.listFiles().orEmpty().forEach { directory ->
                if (directory !in inFlight && now - directory.lastModified() >= TTL_MILLIS) delete(directory)
            }
            if (account.listFiles().isNullOrEmpty()) account.delete()
        }
    }

    /** The end of an account's session (sign-out, an ended session, the deletion teardown): its whole folder. */
    @Synchronized fun deleteAccount(accountKey: String) {
        val account = File(root, accountKey)
        inFlight.removeAll { it.parentFile == account }
        delete(account)
    }

    /**
     * A session change: every other account's folder goes, or all of them when [accountKey] is null (definitely no
     * person signed in). In-flight copies stay: their owner's own session fence cancels and discards them.
     */
    @Synchronized fun keepOnly(accountKey: String?) {
        decided = true
        keepOnlyLocked(accountKey)
    }

    /**
     * At app start, off Main: the expired copies, and every account folder but the one the vault stores ([storedKey],
     * read with no network; null when none is stored). If a session event has already decided which account is current
     * (a fast restore or sign-in), only the expiry part runs, so a slow start never deletes a newer account's copies.
     */
    @Synchronized fun startupSweep(storedKey: String?) {
        sweep()
        if (!decided) keepOnlyLocked(storedKey)
    }

    private fun keepOnlyLocked(accountKey: String?) {
        accounts().filter { it.name != accountKey }.forEach { account ->
            account.listFiles().orEmpty().filter { it !in inFlight }.forEach(::delete)
            if (account.listFiles().isNullOrEmpty()) account.delete()
        }
    }

    private fun accounts(): List<File> = root.listFiles().orEmpty().filter { it.isDirectory }

    private fun delete(target: File) {
        if (!target.exists()) return
        target.walkBottomUp().forEach { if (it.isFile) revoke(it) }
        target.deleteRecursively()
    }

    companion object {
        /** TL, 2026-11-08: long enough for a receiver to finish reading; never promised to the person. */
        const val TTL_MILLIS = 24L * 60 * 60 * 1000

        /** A path-safe key for an origin and account: a hash, so no server or account identifier is written to disk. */
        fun accountKey(origin: String, userId: Long): String =
            MessageDigest.getInstance("SHA-256").digest("$origin\n$userId".toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) }
    }
}
