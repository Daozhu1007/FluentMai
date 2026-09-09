package dev.fluentmai.android

import android.util.Log
import dev.fluentmai.android.core.importer.WahlapScorePageUrls
import dev.fluentmai.android.core.importer.WahlapSupplementalPage
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.Closeable
import java.io.IOException
import java.util.Locale

data class WahlapCookieImportCredentials(
    val cookies: Map<String, String>,
    val headers: Map<String, String>,
) {
    val cookieHeader: String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    fun safeSummary(): String =
        "cookies=${cookies.keys.sorted().joinToString("|")} headers=${headers.keys.sorted().joinToString("|")}"

    companion object {
        private val requiredCookies = setOf("_t", "userId")
        private val pseudoHeaders = setOf(":method", ":authority", ":path", ":scheme")
        private val headerMap = mapOf(
            "user-agent" to HttpHeaders.UserAgent,
            "accept" to HttpHeaders.Accept,
            "accept-language" to HttpHeaders.AcceptLanguage,
            "x-requested-with" to "X-Requested-With",
            "sec-ch-ua" to "Sec-CH-UA",
            "sec-ch-ua-mobile" to "Sec-CH-UA-Mobile",
            "sec-ch-ua-platform" to "Sec-CH-UA-Platform",
            "referer" to HttpHeaders.Referrer,
            "sec-fetch-site" to "Sec-Fetch-Site",
            "sec-fetch-mode" to "Sec-Fetch-Mode",
            "sec-fetch-user" to "Sec-Fetch-User",
            "sec-fetch-dest" to "Sec-Fetch-Dest",
            "upgrade-insecure-requests" to "Upgrade-Insecure-Requests",
        )

        fun parse(rawInput: String): WahlapCookieImportCredentials {
            val raw = rawInput.trim()
            require(raw.isNotBlank()) { "Wahlap Cookie is empty" }

            val cookies = linkedMapOf<String, String>()
            val headers = linkedMapOf<String, String>()
            var sawCookieHeader = false

            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val separator = line.indexOf(": ")
                        .takeIf { it >= 0 }
                        ?: line.indexOf(':').takeIf { it >= 0 }
                        ?: -1
                    if (separator <= 0) return@forEach

                    val key = line.substring(0, separator).trim()
                    val lowerKey = key.lowercase(Locale.ROOT)
                    val value = line.substring(separator + 1).trim()
                    if (lowerKey in pseudoHeaders) return@forEach
                    if (lowerKey == "cookie") {
                        sawCookieHeader = true
                        parseCookiePairs(value, cookies)
                        return@forEach
                    }
                    if (lowerKey == "set-cookie") {
                        sawCookieHeader = true
                        parseSetCookieHeader(value, cookies)
                        return@forEach
                    }
                    headerMap[lowerKey]?.let { canonicalName ->
                        headers[canonicalName] = value
                    }
                }

            if (!sawCookieHeader) {
                parseCookiePairs(raw.removePrefix("Cookie:").removePrefix("cookie:"), cookies)
            }

            val missing = requiredCookies.filterNot(cookies::containsKey)
            require(missing.isEmpty()) { "Wahlap Cookie missing required fields: ${missing.joinToString(", ")}" }

            return WahlapCookieImportCredentials(
                cookies = cookies,
                headers = headers,
            )
        }

        private fun parseCookiePairs(
            text: String,
            output: MutableMap<String, String>,
        ) {
            text.split(';')
                .map { it.trim() }
                .filter { it.contains('=') }
                .forEach { part ->
                    val name = part.substringBefore('=').trim()
                    val value = part.substringAfter('=').trim()
                    if (name.isNotBlank() && value.isNotBlank()) {
                        output[name] = value
                    }
                }
        }

        private fun parseSetCookieHeader(
            text: String,
            output: MutableMap<String, String>,
        ) {
            val firstPair = text.substringBefore(';').trim()
            if (!firstPair.contains('=')) return

            val name = firstPair.substringBefore('=').trim()
            val value = firstPair.substringAfter('=').trim()
            if (name.isNotBlank() && value.isNotBlank()) {
                output[name] = value
            }
        }
    }
}

class WahlapManualCookieScorePageClient(
    private val credentials: WahlapCookieImportCredentials,
    private val redactor: PrivacyRedactor,
) : Closeable {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
        expectSuccess = false
    }

    suspend fun validateLogin() {
        Log.i(TAG, "Manual Wahlap cookie import: ${credentials.safeSummary()}")
        val home = request(label = "manual-home", rawUrl = HOME_URL)
        if (home.statusCode !in 200..299) {
            throw IOException("Wahlap Cookie login failed: status=${home.statusCode}")
        }
        if (looksLikeAuthFailure(home.body)) {
            throw IOException("Wahlap Cookie login failed: home page is not authenticated")
        }
    }

    suspend fun fetchScorePage(difficulty: Difficulty): String {
        val response = request(
            label = "manual-score-${difficulty.name}",
            rawUrl = WahlapScorePageUrls.scorePageUrl(difficulty, incremental = true),
        )
        if (response.statusCode !in 200..299) {
            throw IOException("Wahlap score fetch failed: difficulty=${difficulty.name} status=${response.statusCode}")
        }
        if (response.contentType != null && !response.contentType.contains("html", ignoreCase = true)) {
            throw IOException("Wahlap score fetch failed: difficulty=${difficulty.name} unexpected content type")
        }
        if (looksLikeAuthFailure(response.body) || !looksLikeScorePage(response.body)) {
            throw IOException("Wahlap score fetch failed: difficulty=${difficulty.name} unexpected page")
        }
        return response.body
    }

    suspend fun fetchSupplementalScorePages(): List<WahlapSupplementalPage> =
        SUPPLEMENTAL_SCORE_PAGE_URLS.mapNotNull { candidate ->
            val response = runCatching {
                request(label = "manual-${candidate.label}", rawUrl = candidate.url)
            }.getOrElse { error ->
                Log.w(TAG, "Manual supplemental ${candidate.label} request failed: ${redactor.redact(error.message ?: error::class.java.simpleName)}")
                return@mapNotNull null
            }
            Log.i(
                TAG,
                "Manual supplemental ${candidate.label}: status=${response.statusCode} " +
                    "type=${response.contentType.orEmpty()} bytes=${response.body.length} " +
                    "scoreLike=${looksLikeScorePage(response.body)}",
            )
            if (response.statusCode !in 200..299) return@mapNotNull null
            if (response.contentType != null && !response.contentType.contains("html", ignoreCase = true)) {
                return@mapNotNull null
            }
            if (looksLikeAuthFailure(response.body)) {
                return@mapNotNull null
            }
            WahlapSupplementalPage(label = candidate.label, html = response.body)
        }

    private suspend fun request(label: String, rawUrl: String): HttpResponse =
        try {
            val response = client.get(rawUrl) {
                headers {
                    val defaults = defaultNavigationHeaders()
                    defaults.forEach { (name, value) ->
                        append(name, credentials.headers[name] ?: value)
                    }
                    credentials.headers
                        .filterKeys { it !in defaults.keys }
                        .forEach { (name, value) -> append(name, value) }
                    append(HttpHeaders.Cookie, credentials.cookieHeader)
                }
            }
            HttpResponse(
                statusCode = response.status.value,
                contentType = response.headers[HttpHeaders.ContentType],
                body = response.bodyAsText(),
                finalUrl = response.call.request.url.toString(),
            )
        } catch (error: Exception) {
            throw IOException("$label request failed: ${redactor.redact(error.message ?: error::class.java.simpleName)}", error)
        }

    override fun close() {
        client.close()
    }

    private data class HttpResponse(
        val statusCode: Int,
        val contentType: String?,
        val body: String,
        val finalUrl: String,
    )

    private data class SupplementalScorePageCandidate(
        val label: String,
        val url: String,
    )

    private companion object {
        private const val TAG = "WahlapManualCookie"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val HOME_URL = "https://maimai.wahlap.com/maimai-mobile/home/"
        private val SUPPLEMENTAL_SCORE_PAGE_URLS = listOf(
            SupplementalScorePageCandidate(
                label = "rating-target-music",
                url = "https://maimai.wahlap.com/maimai-mobile/home/ratingTargetMusic/",
            ),
            SupplementalScorePageCandidate(
                label = "rating-recent",
                url = "https://maimai.wahlap.com/maimai-mobile/home/playerData/ratingDetailRecent/",
            ),
            SupplementalScorePageCandidate(
                label = "rating-best",
                url = "https://maimai.wahlap.com/maimai-mobile/home/playerData/ratingDetailBest/",
            ),
            SupplementalScorePageCandidate(
                label = "rating-detail",
                url = "https://maimai.wahlap.com/maimai-mobile/home/playerData/ratingDetail/",
            ),
            SupplementalScorePageCandidate(
                label = "rating-old",
                url = "https://maimai.wahlap.com/maimai-mobile/home/playerData/ratingDetailOld/",
            ),
        )

        private fun defaultNavigationHeaders(): Map<String, String> =
            linkedMapOf(
                HttpHeaders.Connection to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                HttpHeaders.UserAgent to WahlapKtorClient.WX_WINDOWS_UA,
                HttpHeaders.Accept to "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-User" to "?1",
                "Sec-Fetch-Dest" to "document",
                HttpHeaders.AcceptEncoding to "gzip, deflate, br",
                HttpHeaders.AcceptLanguage to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            )

        private fun looksLikeAuthFailure(html: String): Boolean {
            val normalized = html.lowercase()
            return normalized.contains("please open in wechat") ||
                normalized.contains("/wc_auth/oauth/authorize/") ||
                normalized.contains("open.weixin.qq.com/connect/oauth2/authorize") ||
                html.contains("\u767b\u5f55\u5931\u8d25") ||
                html.contains("\u9519\u8bef\u7801") ||
                html.contains("登录失败") ||
                html.contains("错误码") ||
                html.contains("title_error")
        }

        private fun looksLikeScorePage(html: String): Boolean =
            dev.fluentmai.android.core.importer.WahlapScorePageValidation.isScorePage(html)
    }
}
