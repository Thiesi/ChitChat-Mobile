package io.github.thiesi.chitchat.core.session

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** One origin and one local generation, even though HTTP cookies themselves ignore ports. */
internal class OriginCookieJar(private val origin: HttpUrl) : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    private var retired = false
    var invalid: Boolean = false
        private set

    private fun allowed(url: HttpUrl) = url.scheme == "https" && url.host == origin.host &&
        url.port == origin.port && url.encodedPath.startsWith("/api/v1/")

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (retired || !allowed(url)) return
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        if (this.cookies.size > 32 || this.cookies.any { it.name.length > 256 || it.value.length > 4096 }) {
            invalid = true
            this.cookies.clear()
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (retired || invalid || !allowed(url)) return emptyList()
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    @Synchronized
    fun export(): List<String> = if (retired || invalid) emptyList() else cookies.map(Cookie::toString)

    @Synchronized
    fun restore(encoded: List<String>): Boolean {
        if (retired || encoded.size > 32) return false
        val parsed = encoded.map { Cookie.parse(origin, it) ?: return false }
        if (parsed.any { it.name.length > 256 || it.value.length > 4096 ||
                !it.matches(origin.newBuilder().encodedPath(it.path).build()) }) return false
        cookies.clear()
        cookies.addAll(parsed)
        return true
    }

    @Synchronized
    fun retire() { retired = true; cookies.clear() }
}
