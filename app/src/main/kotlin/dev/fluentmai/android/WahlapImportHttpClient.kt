package dev.fluentmai.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parseClientCookiesHeader
import dev.fluentmai.android.core.importer.WahlapRequestCategory
import dev.fluentmai.android.core.importer.WahlapTimeoutProfile
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One HTTP client and cookie jar per complete OAuth attempt, from authorize through import.
 * This eliminates cross-import state contamination while preserving authorize-stage state.
 * Cookie contamination has not been established as the cause of callback 404/error 100001.
 * The owning attempt MUST close this client when finished, failed or replaced.
 */
class WahlapImportHttpClient(
    authReplay: WahlapAuthReplayState = WahlapAuthReplayState(),
    val cookieStorage: AcceptAllCookiesStorage = AcceptAllCookiesStorage(),
    private val isAuthCallback: (String) -> Boolean = Companion::isAuthCallbackUrl,
) : Closeable {
    @Volatile
    private var authReplay = authReplay
    private val closed = AtomicBoolean(false)

    internal fun attachAuthReplay(state: WahlapAuthReplayState) {
        check(!closed.get()) { "OAuth HTTP client is closed" }
        authReplay = state
    }
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpCookies) {
            storage = cookieStorage
        }
        install(HttpTimeout)
    }

    /**
     * GETs a Wahlap page with the timeout profile for [category] and the standard navigation
     * headers. For [WahlapRequestCategory.AUTH_CALLBACK] the captured replay headers are attached
     * and the captured cookies are seeded into the jar, so the single-use OAuth code is presented
     * with its original browser context. [detailReferer] reproduces the music-detail Referer
     * rule for play-count requests.
     */
    suspend fun fetchPage(
        rawUrl: String,
        category: WahlapRequestCategory,
        timeoutProfile: WahlapTimeoutProfile,
        detailReferer: String? = null,
    ): WahlapPageResponse {
        check(!closed.get()) { "OAuth HTTP client is closed" }
        val uri = Url(rawUrl)
        val isAuthCallback = isAuthCallback(rawUrl)
        if (isAuthCallback) {
            authReplay.pendingAuthCookies?.let {
                cookieStorage.seedWahlapCookies(parseClientCookiesHeader(it), uri)
            }
        }
        val response = client.get(rawUrl) {
            timeout {
                requestTimeoutMillis = timeoutProfile.requestTimeoutMs
                connectTimeoutMillis = timeoutProfile.connectTimeoutMs
            }
            headers {
                val replayedNames = if (isAuthCallback) {
                    authReplay.headers.forEach { (name, value) -> append(name, value) }
                    authReplay.headers.keys.map { it.lowercase(Locale.ROOT) }.toSet()
                } else {
                    emptySet()
                }
                appendDefaultHeader(HttpHeaders.Connection, "keep-alive", replayedNames)
                appendDefaultHeader("Upgrade-Insecure-Requests", "1", replayedNames)
                appendDefaultHeader(HttpHeaders.UserAgent, defaultUserAgent(), replayedNames)
                appendDefaultHeader(
                    HttpHeaders.Accept,
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
                    replayedNames,
                )
                appendDefaultHeader("Sec-Fetch-Site", "none", replayedNames)
                appendDefaultHeader("Sec-Fetch-Mode", "navigate", replayedNames)
                appendDefaultHeader("Sec-Fetch-User", "?1", replayedNames)
                appendDefaultHeader("Sec-Fetch-Dest", "document", replayedNames)
                appendDefaultHeader(HttpHeaders.AcceptEncoding, "gzip, deflate, br", replayedNames)
                appendDefaultHeader(
                    HttpHeaders.AcceptLanguage,
                    "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
                    replayedNames,
                )
                if (isActivityUrl(rawUrl)) {
                    set(HttpHeaders.AcceptEncoding, "identity")
                    set(HttpHeaders.Referrer, detailReferer ?: HOME_URL)
                    set("Sec-Fetch-Site", "same-origin")
                    set(HttpHeaders.UserAgent, defaultUserAgent())
                }
            }
        }
        return WahlapPageResponse(
            statusCode = response.status.value,
            contentType = response.headers[HttpHeaders.ContentType],
            body = response.bodyAsText(),
            finalUrl = response.call.request.url.toString(),
        )
    }

    /**
     * Follows the OAuth authorize redirect chain and returns the final URL (the WeChat
     * authorize entry). Deliberately not retried by callers: the request starts a fresh
     * authorization and a human can simply tap the hook link again.
     */
    suspend fun fetchAuthorizeRedirectFinalUrl(rawUrl: String): String {
        check(!closed.get()) { "OAuth HTTP client is closed" }
        val profile = dev.fluentmai.android.core.importer.WahlapRequestCatalog
            .timeoutProfile(WahlapRequestCategory.AUTH_AUTHORIZE)
        val response = client.get(rawUrl) {
            timeout {
                requestTimeoutMillis = profile.requestTimeoutMs
                connectTimeoutMillis = profile.connectTimeoutMs
            }
        }
        response.bodyAsText()
        return response.call.request.url.toString()
    }

    suspend fun cookieSummary(): String {
        val ktorCookies = listOf(
            Url("https://maimai.wahlap.com/"),
            Url("https://tgk-wcaime.wahlap.com/"),
        ).flatMap { url -> client.cookies(url) }
            .distinctBy { cookie -> "${cookie.domain}:${cookie.name}" }
        val cookies = ktorCookies.map { cookie -> "${cookie.domain}:${cookie.name}" }
        if (cookies.isEmpty()) return "count=0"
        val names = cookies
            .sorted()
            .take(MAX_SUMMARY_COOKIES)
        val suffix = if (cookies.size > names.size) ",..." else ""
        return "count=${cookies.size} names=${names.joinToString("|")}$suffix"
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            authReplay = WahlapAuthReplayState()
            client.close()
        }
    }

    /** Once captured, the browser UA is retained for the attempt; otherwise use the Android UA. */
    private fun defaultUserAgent(): String =
        authReplay.headers.entries
            .firstOrNull { it.key.equals(HttpHeaders.UserAgent, ignoreCase = true) }
            ?.value
            ?: WX_ANDROID_UA

    private fun io.ktor.http.HeadersBuilder.appendDefaultHeader(
        name: String,
        value: String,
        replayedNames: Set<String>,
    ) {
        if (name.lowercase(Locale.ROOT) !in replayedNames) {
            append(name, value)
        }
    }

    companion object {
        const val WX_ANDROID_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/112.0.5615.136 Mobile Safari/537.36 MicroMessenger/8.0.50 NetType/WIFI Language/zh_CN"

        const val HOME_URL = "https://maimai.wahlap.com/maimai-mobile/home/"

        private const val MAX_SUMMARY_COOKIES = 12

        fun isAuthCallbackUrl(rawUrl: String): Boolean = rawUrl.contains(
            "tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx",
            ignoreCase = true,
        )

        fun isActivityUrl(rawUrl: String): Boolean =
            dev.fluentmai.android.core.importer.WahlapActivityParser.safeActivityUrl(rawUrl) != null
    }
}

data class WahlapPageResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: String,
    val finalUrl: String,
)
