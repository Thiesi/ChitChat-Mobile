package io.github.thiesi.chitchat.core.settings

import io.github.thiesi.chitchat.core.server.AddressError
import org.junit.Assert.*
import org.junit.Test

class SettingsDecoderTest {
    @Test fun distinguishesInvalidStoredAddressWithoutRetainingItsSecrets() {
        val settings = decodeSettings("https://user:fixture-secret@chat.example.org/", "DARK")
        assertNull(settings.server)
        assertEquals(AddressError.ORIGIN_ONLY, settings.serverError)
        assertEquals(ThemePreference.DARK, settings.theme)
        assertFalse(settings.toString().contains("fixture-secret"))
    }
    @Test fun missingServerIsNotAnErrorAndValidStoredIdnsAreCanonicalized() {
        assertNull(decodeSettings(null, null).serverError)
        assertEquals("https://xn--bcher-kva.example/", decodeSettings("https://bücher.example", "SYSTEM").server!!.value)
    }
}
