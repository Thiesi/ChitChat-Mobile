package io.github.thiesi.chitchat.core.server

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Server prefill P2 (CC #417's fixed contract, TL and owner, 2026-11-24): a fresh install from the web's Play badge
 * carries `chitchat_server=<host>` in its Play install referrer. Read once, on first launch, it prefills "Where do you
 * chat?" through P1's [PendingServerLink] and router, so it follows the same PL rulings (A on a fresh install; the
 * person still taps Continue). An invalid or missing value is always silent: never offered, shown, logged or kept.
 */
sealed interface ReferrerAnswer {
    /** Play answered OK; [referrer] is its raw referrer string (possibly empty). Definitive. */
    data class Referrer(val referrer: String?) : ReferrerAnswer {
        override fun toString() = "Referrer(redacted)"
    }

    /** No Play, or a client error a retry won't change (FEATURE_NOT_SUPPORTED, DEVELOPER_ERROR, PERMISSION_ERROR). */
    data object Definitive : ReferrerAnswer

    /** Unavailable for now (service unavailable or disconnected, the bound elapsed, an exception): try on a later launch. */
    data object Retry : ReferrerAnswer
}

/** Reads the Play install referrer once. Faked in tests. */
fun interface InstallReferrerReads {
    suspend fun read(): ReferrerAnswer
}

/**
 * The real reader: Google's Install Referrer client (owner decision 2026-11-24, Android SDK License). Only
 * [com.android.installreferrer.api.ReferrerDetails.getInstallReferrer] is read; its timestamps, install version and
 * instant flag are never touched (TL). Bounded at 10 s, the connection always ended, the blocking IPC off the main thread.
 */
class PlayInstallReferrer(private val context: Context) : InstallReferrerReads {
    override suspend fun read(): ReferrerAnswer {
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        return try {
            val setup = withTimeoutOrNull(BOUND_MS) {
                suspendCancellableCoroutine<Int> { continuation ->
                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(code: Int) {
                            if (continuation.isActive) continuation.resume(code)
                        }
                        override fun onInstallReferrerServiceDisconnected() {
                            if (continuation.isActive) continuation.resume(InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED)
                        }
                    })
                }
            } ?: return ReferrerAnswer.Retry
            when (setup) {
                InstallReferrerClient.InstallReferrerResponse.OK ->
                    withContext(Dispatchers.IO) { ReferrerAnswer.Referrer(client.installReferrer.installReferrer) }
                InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR,
                InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR -> ReferrerAnswer.Definitive
                else -> ReferrerAnswer.Retry
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ReferrerAnswer.Retry
        } finally {
            runCatching { client.endConnection() }
        }
    }

    private companion object {
        const val BOUND_MS = 10_000L
    }
}

/**
 * The `chitchat_server` value in a referrer (URL query form, `a=b&chitchat_server=host`), or null: exactly one
 * non-blank occurrence, URL-decoded, from a referrer of at most 2,048 characters. Only extraction: acceptance is
 * [ServerAddress.parse]'s, exactly as for typed input.
 */
fun referrerServer(referrer: String?): String? {
    if (referrer.isNullOrEmpty() || referrer.length > MAX_REFERRER) return null
    val values = referrer.split('&').mapNotNull { pair ->
        if (pair.substringBefore('=') != REFERRER_PARAMETER || '=' !in pair) null
        else runCatching { URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name()) }.getOrNull()
    }
    return values.singleOrNull()?.takeIf { it.isNotBlank() }
}

private const val REFERRER_PARAMETER = "chitchat_server"
private const val MAX_REFERRER = 2_048

/** The one never-cleared "the referrer was settled" flag (TL): a boolean in the backup-excluded settings store. */
interface ReferrerFlag {
    val done: Flow<Boolean>
    suspend fun markDone()
}

/**
 * Server prefill P2's bounded read (TL conditions): at most one read per process, only while no server is saved and
 * nobody is signed in, and only until the flag is set. The flag is set on any definitive answer, or as soon as a server
 * is saved or someone signs in, and is never cleared ("forget server" doesn't reset it), so a referrer can't re-apply
 * later. A non-definitive answer leaves it for a later launch. The read runs in the app's [scope], off the UI: Welcome
 * renders as usual and the answer, if usable, arrives through [links] like a link would.
 */
class ReferrerPrefill(private val reads: InstallReferrerReads, private val flag: ReferrerFlag,
    private val links: PendingServerLink, private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)

    /**
     * Called by the root once settings have loaded. [eligible] reads the current state: no saved server and nobody
     * signed in. Not eligible settles the flag; eligible starts the one read of this process.
     */
    fun consider(eligible: () -> Boolean) {
        scope.launch {
            if (flag.done.first()) return@launch
            if (!eligible()) { flag.markDone(); return@launch }
            if (!started.compareAndSet(false, true)) return@launch
            when (val answer = reads.read()) {
                is ReferrerAnswer.Referrer -> {
                    flag.markDone()
                    // Re-checked on arrival: a server saved or a sign-in meanwhile drops it. Invalid is silent.
                    val address = referrerServer(answer.referrer)
                        ?.let { (ServerAddress.parse(it) as? AddressResult.Valid)?.address }
                    if (address != null && eligible()) links.offer(ServerLink.of(address))
                }
                ReferrerAnswer.Definitive -> flag.markDone()
                ReferrerAnswer.Retry -> Unit
            }
        }
    }
}
