package io.github.thiesi.chitchat.core.settings

import io.github.thiesi.chitchat.core.server.AddressResult
import io.github.thiesi.chitchat.core.server.ServerAddress

/** Preserve a typed configuration error, never the potentially sensitive invalid URL. */
internal fun decodeSettings(rawServer: String?, rawTheme: String?): AppSettings {
    val parsed = rawServer?.let(ServerAddress::parse)
    return AppSettings(
        server = (parsed as? AddressResult.Valid)?.address,
        theme = ThemePreference.entries.firstOrNull { it.name == rawTheme } ?: ThemePreference.SYSTEM,
        serverError = (parsed as? AddressResult.Invalid)?.reason,
    )
}
