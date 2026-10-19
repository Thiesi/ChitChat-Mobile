package io.github.thiesi.chitchat.core.server

import java.net.URI
import java.util.Locale

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
            if (uri.host.isNullOrBlank() || uri.port !in -1..65535 || uri.port == 0) {
                return AddressResult.Invalid(AddressError.INVALID)
            }
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/")) {
                return AddressResult.Invalid(AddressError.ORIGIN_ONLY)
            }
            val host = uri.host.lowercase(Locale.ROOT)
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return AddressResult.Valid(ServerAddress("https://$host$port/"))
        }
    }
}

enum class AddressError { INVALID, HTTPS, ORIGIN_ONLY }

sealed interface AddressResult {
    data class Valid(val address: ServerAddress) : AddressResult
    data class Invalid(val reason: AddressError) : AddressResult
}
