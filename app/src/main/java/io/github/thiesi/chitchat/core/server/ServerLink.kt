package io.github.thiesi.chitchat.core.server

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Server prefill P1 (CC #417's fixed contract): `chitchat://connect?server=<host[:port]>`, the web's "Open it with this
 * server" link. A link can only **prefill** "Where do you chat?"; the person still taps Continue, nothing connects by
 * itself, and nothing switches while signed in (the phishing guard: any site can craft these links).
 *
 * [address] is [ServerAddress.parse]'s verdict on the raw value, exactly as for typed input: the link adds no acceptance
 * rule of its own; null means the link carried no usable address. The raw value isn't kept (G: an invalid value is never
 * shown, logged or kept), and [toString] names only validity. Not a data class: each arrival is its own instance, so
 * equality is identity and an older arrival can never be taken in place of a newer one.
 */
class ServerLink private constructor(val address: ServerAddress?) {
    override fun toString() = if (address == null) "ServerLink(invalid)" else "ServerLink(valid)"

    companion object {
        const val SCHEME = "chitchat"
        const val HOST = "connect"
        private const val PARAMETER = "server"
        /** Generous bound on the raw link before any decoding: a host[:port] is far shorter. */
        private const val MAX_LINK = 2_048

        /**
         * The link's server value, or null when [uri] isn't exactly `chitchat://connect` (no path, no fragment) with a
         * single non-blank `server` parameter. Only extraction: acceptance is [ServerAddress.parse]'s.
         */
        fun value(uri: String?): String? {
            if (uri == null || uri.length > MAX_LINK) return null
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            if (!parsed.scheme.equals(SCHEME, ignoreCase = true) || !parsed.host.equals(HOST, ignoreCase = true)) return null
            if (parsed.port != -1 || parsed.rawUserInfo != null || parsed.rawFragment != null) return null
            if (!parsed.rawPath.isNullOrEmpty() && parsed.rawPath != "/") return null
            val values = parsed.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=')
                if (key != PARAMETER || '=' !in pair) null
                else runCatching { URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name()) }.getOrNull()
            }
            return values.singleOrNull()?.takeIf { it.isNotBlank() }
        }

        /** The link [uri] carries, with [ServerAddress.parse]'s verdict, or null when it isn't a server link at all. */
        /** Server prefill P2: an address already parsed from the Play referrer (only ever a valid one is offered). */
        fun of(address: ServerAddress): ServerLink = ServerLink(address)

        fun from(uri: String?): ServerLink? = value(uri)?.let { ServerLink((ServerAddress.parse(it) as? AddressResult.Valid)?.address) }
    }
}

/**
 * The canonical host[:port] of a parsed address, as ASCII (IDNs as punycode, IPv6 in brackets, port 443 omitted): what a
 * link's prefill puts in the field and what every link line shows, so a link-borne lookalike reads as xn--... (TL, PL).
 */
fun ServerAddress.displayHost(): String {
    val uri = URI(value)
    return uri.host + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
}

/**
 * At most one link waiting for onboarding, in memory for this process (handed over by LinkEntryActivity): never saved, so
 * it can't come back after process death, and a restored activity never replays it. A newer link replaces an older one; onboarding consumes it once ([take]).
 */
class PendingServerLink {
    private val pending = MutableStateFlow<ServerLink?>(null)
    val state: StateFlow<ServerLink?> = pending.asStateFlow()

    fun offer(link: ServerLink?) { if (link != null) pending.value = link }

    /** Consumes exactly this link; false if it was already consumed or replaced. */
    fun take(link: ServerLink): Boolean = pending.compareAndSet(link, null)
}
