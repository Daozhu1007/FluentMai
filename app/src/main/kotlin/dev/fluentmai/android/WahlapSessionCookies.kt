package dev.fluentmai.android

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url

/** Bootstrap once, then let HttpCookies consume every Set-Cookie (including redirects).
 * Cookie header values are already wire-encoded and must not be URL-encoded again. */
internal suspend fun CookiesStorage.seedWahlapCookies(cookies: Map<String, String>, origin: Url) {
    cookies.forEach { (name, value) ->
        addCookie(origin, Cookie(name, value, encoding = CookieEncoding.RAW, path = "/"))
    }
}
