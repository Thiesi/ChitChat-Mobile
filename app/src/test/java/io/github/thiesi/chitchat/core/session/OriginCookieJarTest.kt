package io.github.thiesi.chitchat.core.session

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class OriginCookieJarTest {
    private val origin = "https://chat.example.org/".toHttpUrl()
    private val api = origin.resolve("api/v1/session.php")!!

    @Test fun cookiesNeverLeaveTheSelectedOriginPortOrApiPaths() {
        val jar = OriginCookieJar(origin)
        jar.saveFromResponse(api, listOf(Cookie.parse(api, "CUSTOM=fixture; Secure; HttpOnly; Path=/")!!))
        assertEquals(1, jar.loadForRequest(api).size)
        listOf("https://chat.example.org:8443/api/v1/session.php", "https://other.example.org/api/v1/session.php",
            "http://chat.example.org/api/v1/session.php", "https://chat.example.org/health.php").forEach {
            assertTrue(it, jar.loadForRequest(it.toHttpUrl()).isEmpty())
        }
    }
    @Test fun retiredGenerationRejectsLateCookieCallbacks() {
        val jar = OriginCookieJar(origin)
        jar.retire()
        jar.saveFromResponse(api, listOf(Cookie.parse(api, "CUSTOM=late; Secure; HttpOnly; Path=/")!!))
        assertTrue(jar.loadForRequest(api).isEmpty())
        assertTrue(jar.export().isEmpty())
    }
    @Test fun persistedCookieRetainsPathSecureHttpOnlyAndHostOnly() {
        val jar = OriginCookieJar(origin)
        jar.saveFromResponse(api, listOf(Cookie.parse(api, "CUSTOM=fixture; Secure; HttpOnly; Path=/api/v1/")!!))
        val restored = OriginCookieJar(origin)
        assertTrue(restored.restore(jar.export()))
        val cookie = restored.loadForRequest(api).single()
        assertEquals("/api/v1/", cookie.path)
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.hostOnly)
    }
    @Test fun serverDeletionAndForeignPersistedCookiesCannotRestoreAuthentication() {
        val jar = OriginCookieJar(origin)
        assertFalse(jar.restore(listOf("CUSTOM=wrong; Domain=other.example.org; Path=/")))
        jar.saveFromResponse(api, listOf(Cookie.parse(api, "CUSTOM=fixture; Path=/")!!))
        jar.saveFromResponse(api, listOf(Cookie.parse(api, "CUSTOM=; Max-Age=0; Path=/")!!))
        assertTrue(jar.loadForRequest(api).isEmpty())
    }
    @Test fun oversizedCookieSetsFailClosed() {
        val jar = OriginCookieJar(origin)
        jar.saveFromResponse(api, (1..33).map { Cookie.parse(api, "COOKIE$it=fixture; Path=/")!! })
        assertTrue(jar.invalid)
        assertTrue(jar.loadForRequest(api).isEmpty())
        assertTrue(jar.export().isEmpty())
    }
}
