package io.github.thiesi.chitchat.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test fun canonicalizesHttpsOrigin() {
        val result = ServerAddress.parse("  CHAT.example.org:443/ ") as AddressResult.Valid
        assertEquals("https://chat.example.org/", result.address.value)
    }
    @Test fun preservesExplicitHttpsPortAndIpv6() {
        assertEquals("https://[::1]:8443/", (ServerAddress.parse("https://[::1]:8443") as AddressResult.Valid).address.value)
    }
    @Test fun rejectsCredentialsPathsQueriesAndFragments() {
        listOf("https://user:secret@example.org", "https://example.org/index.php", "https://example.org/?token=secret", "https://example.org/#fragment").forEach {
            assertEquals(it, AddressResult.Invalid(AddressError.ORIGIN_ONLY), ServerAddress.parse(it))
        }
    }
    @Test fun rejectsCleartextAndOtherSchemes() {
        listOf("http://example.org", "ftp://example.org").forEach {
            assertEquals(AddressResult.Invalid(AddressError.HTTPS), ServerAddress.parse(it))
        }
    }
    @Test fun rejectsMalformedHostsAndPorts() {
        listOf("", "example org", "https://", "https://example.org:0", "https://example.org:65536", "https://example.org:notaport").forEach {
            assertTrue(it, ServerAddress.parse(it) is AddressResult.Invalid)
        }
    }
}
