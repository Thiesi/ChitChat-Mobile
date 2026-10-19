package io.github.thiesi.chitchat.core.server

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The root-host deployment used by the current ChitChat API. No credentials belong in it. */
@JvmInline
value class ServerAddress private constructor(val value: String) {
    companion object {
        fun parse(input: String): AddressResult {
            val text = input.trim()
            if (text.isBlank() || text.any(Char::isWhitespace)) return AddressResult.Invalid(AddressError.INVALID)
            val candidate = if ("://" in text) text else "https://$text"
            val uri = try { URI(candidate) } catch (_: Exception) {
                return AddressResult.Invalid(AddressError.INVALID)
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return AddressResult.Invalid(AddressError.HTTPS)
            if (uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true || uri.rawQuery != null || uri.rawFragment != null ||
                (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/")) {
                return AddressResult.Invalid(AddressError.ORIGIN_ONLY)
            }
            // HttpUrl provides maintained IDN/punycode, IPv6 and port handling.
            // Inspect the raw URI first: URL normalization must not hide a page path.
            val url = candidate.toHttpUrlOrNull() ?: return AddressResult.Invalid(AddressError.INVALID)
            if (url.port == 0 || url.host.isBlank()) return AddressResult.Invalid(AddressError.INVALID)
            return AddressResult.Valid(ServerAddress(url.toString()))
        }
    }
}

enum class AddressError { INVALID, HTTPS, ORIGIN_ONLY }

sealed interface AddressResult {
    data class Valid(val address: ServerAddress) : AddressResult
    data class Invalid(val reason: AddressError) : AddressResult
}
