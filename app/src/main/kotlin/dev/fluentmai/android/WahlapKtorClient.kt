package dev.fluentmai.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parseClientCookiesHeader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object WahlapKtorClient {
    const val WX_ANDROID_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/112.0.5615.136 Mobile Safari/537.36 MicroMessenger/8.0.50 NetType/WIFI Language/zh_CN"
    const val WX_WINDOWS_UA =
        "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/81.0.4044.138 Safari/537.36 NetType/WIFI " +
            "MicroMessenger/7.0.20.1781(0x6700143B) WindowsWechat(0x6307001e)"

    private val cookieStorage = AcceptAllCookiesStorage()
    private val pendingAuthCookies = AtomicReference<String?>(null)
    private val authReplayHeaders = ConcurrentHashMap<String, String>()

    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
        install(HttpCookies) {
            storage = cookieStorage
        }
        expectSuccess = false
    }

    suspend fun getAuthUrl(): String {
        pendingAuthCookies.set(null)
        authReplayHeaders.clear()
        val response = client.get(MAIMAI_DX_AUTHORIZE_URL)
        return response.call.request.url.toString().replace("redirect_uri=https", "redirect_uri=http")
    }

    fun storeAuthReplayHeaders(rawRequestHeaders: String): Int {
        authReplayHeaders.clear()
        pendingAuthCookies.set(null)
        rawRequestHeaders
            .lineSequence()
            .drop(1)
            .forEach { line ->
                if (line.isBlank()) return@forEach
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.isBlank() || value.isBlank()) return@forEach
                val normalized = name.lowercase(Locale.ROOT)
                if (normalized == "cookie") {
                    pendingAuthCookies.set(value)
                    return@forEach
                }
                if (normalized in SKIPPED_REPLAY_HEADERS) return@forEach
                authReplayHeaders[name] = value
            }
        return authReplayHeaders.size
    }

    suspend fun getWahlapPage(rawUrl: String, detailReferer: String? = null): HttpResponse {
        val uri = Url(rawUrl)
        if (uri.host == "tgk-wcaime.wahlap.com" && uri.encodedPath.startsWith("/wc_auth/oauth/callback/maimai-dx")) {
            pendingAuthCookies.getAndSet(null)?.let {
                cookieStorage.seedWahlapCookies(parseClientCookiesHeader(it), uri)
            }
        }
        return client.get(rawUrl) {
            headers {
                val isAuthReplay = rawUrl.contains(
                    "tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx",
                    ignoreCase = true,
                )
                val replayedNames = if (isAuthReplay) {
                    authReplayHeaders.forEach { (name, value) -> append(name, value) }
                    authReplayHeaders.keys.map { it.lowercase(Locale.ROOT) }.toSet()
                } else {
                    emptySet()
                }
                appendDefaultHeader(HttpHeaders.Connection, "keep-alive", replayedNames)
                appendDefaultHeader("Upgrade-Insecure-Requests", "1", replayedNames)
                appendDefaultHeader(HttpHeaders.UserAgent,
                    authReplayHeaders.entries.firstOrNull { it.key.equals(HttpHeaders.UserAgent, true) }?.value ?: WX_ANDROID_UA,
                    replayedNames)
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
                appendDefaultHeader(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7", replayedNames)
                if (dev.fluentmai.android.core.importer.WahlapActivityParser.safeActivityUrl(rawUrl) != null) {
                    set(HttpHeaders.AcceptEncoding, "identity")
                    set(HttpHeaders.Referrer, detailReferer ?: "https://maimai.wahlap.com/maimai-mobile/home/")
                    set("Sec-Fetch-Site", "same-origin")
                    set(HttpHeaders.UserAgent, authReplayHeaders.entries.firstOrNull { it.key.equals(HttpHeaders.UserAgent, true) }?.value ?: WX_ANDROID_UA)
                }
            }
        }
    }

    suspend fun safeCookieSummary(): String {
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

    private fun io.ktor.http.HeadersBuilder.appendDefaultHeader(
        name: String,
        value: String,
        replayedNames: Set<String>,
    ) {
        if (name.lowercase(Locale.ROOT) !in replayedNames) {
            append(name, value)
        }
    }

    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val REQUEST_TIMEOUT_MS = 30_000L
    private const val MAX_SUMMARY_COOKIES = 12
    private const val MAIMAI_DX_AUTHORIZE_URL =
        "https://tgk-wcaime.wahlap.com/wc_auth/oauth/authorize/maimai-dx"
    private val SKIPPED_REPLAY_HEADERS = setOf(
        "host",
        "connection",
        "content-length",
        "proxy-connection",
    )
}
