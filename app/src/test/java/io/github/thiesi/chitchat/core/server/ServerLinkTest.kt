package io.github.thiesi.chitchat.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server prefill P1: only extraction from the CC #417 link; acceptance is exactly ServerAddress.parse's. */
class ServerLinkTest {
    private fun link(query: String) = "chitchat://connect?$query"

    @Test fun theContractsLinkGivesItsHostAndPort() {
        assertEquals("chat.example.org", ServerLink.value(link("server=chat.example.org")))
        assertEquals("chat.example.org:8443", ServerLink.value(link("server=chat.example.org%3A8443")))
        assertEquals("[2001:db8::1]", ServerLink.value(link("server=%5B2001%3Adb8%3A%3A1%5D")))
        assertEquals(ServerAddress.parse("chat.example.org"), ServerLink.from(link("server=chat.example.org"))?.address
            ?.let { AddressResult.Valid(it) })
    }

    @Test fun schemeAndHostAreCaseInsensitiveAndAnEmptyPathIsAllowed() {
        assertEquals("chat.example.org", ServerLink.value("CHITCHAT://Connect/?server=chat.example.org"))
    }

    @Test fun anythingButExactlyChitchatConnectIsNotAServerLink() {
        for (uri in listOf(null, "", "https://connect?server=a.org", "chitchat://other?server=a.org", "chitchat://connect/path?server=a.org",
            "chitchat://connect:1?server=a.org", "chitchat://user@connect?server=a.org", "chitchat://connect?server=a.org#x",
            "chitchat://connect", "chitchat://connect?other=a.org", "chitchat://connect?server=", "chitchat://connect?server",
            "chitchat://connect?server=a.org&server=b.org", "chitchat://connect?server=" + "a".repeat(2_100))) {
            assertNull(uri, ServerLink.value(uri))
        }
    }

    @Test fun acceptanceIsServerAddressParseExactlyNeverWider() {
        // A value parse rejects keeps the raw text but has no address: the link widens nothing.
        for (value in listOf("http%3A%2F%2Fa.org", "a.org%2Fpath", "user%40a.org", "a%20b.org", "a.org%3Fq%3D1")) {
            val link = ServerLink.from(link("server=$value"))
            assertTrue(value, link != null)
            assertNull(value, link?.address)
            assertTrue(value, ServerAddress.parse(link!!.value) is AddressResult.Invalid)
        }
        // https:// typed by hand is accepted by parse, so the link value is too, with the same verdict.
        assertEquals(ServerAddress.parse("https://a.org"), ServerLink.from(link("server=https%3A%2F%2Fa.org"))?.address?.let { AddressResult.Valid(it) })
    }

    /** TL, PL: every shown or filled host is the canonical ASCII one, so a link-borne lookalike reads as xn--. */
    @Test fun theDisplayHostIsCanonicalAscii() {
        assertEquals("xn--bcher-kva.example", ServerLink.from(link("server=b%C3%BCcher.example"))?.address?.displayHost())
        assertEquals("chat.example.org", ServerLink.from(link("server=CHAT.Example.org"))?.address?.displayHost())
        assertEquals("chat.example.org:8443", ServerLink.from(link("server=chat.example.org%3A8443"))?.address?.displayHost())
        assertEquals("Port 443 is omitted", "chat.example.org", ServerLink.from(link("server=chat.example.org%3A443"))?.address?.displayHost())
        assertEquals("[2001:db8::1]", ServerLink.from(link("server=%5B2001%3Adb8%3A%3A1%5D"))?.address?.displayHost())
    }

    @Test fun aNewerLinkReplacesAnOlderOneAndIsTakenOnce() {
        val pending = PendingServerLink()
        val first = ServerLink.from(link("server=a.org"))!!
        val second = ServerLink.from(link("server=b.org"))!!
        pending.offer(first); pending.offer(null); pending.offer(second)
        assertEquals(second, pending.state.value)
        assertFalse("A replaced link can't be taken", pending.take(first))
        assertTrue(pending.take(second))
        assertFalse("Taken once", pending.take(second))
        assertNull(pending.state.value)
    }
}
