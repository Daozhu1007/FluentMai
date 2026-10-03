package dev.fluentmai.android

import android.util.Log
import dev.fluentmai.android.core.importer.WahlapAuthFailurePageException
import dev.fluentmai.android.core.importer.WahlapHttpStatusException
import dev.fluentmai.android.core.importer.WahlapNonRetryableException
import dev.fluentmai.android.core.importer.WahlapRequestCategory
import dev.fluentmai.android.core.importer.WahlapResilientFetcher
import dev.fluentmai.android.core.importer.WahlapScorePageUrls
import dev.fluentmai.android.core.importer.WahlapSupplementalPage
import dev.fluentmai.android.core.importer.WahlapSupplementalFetchResult
import dev.fluentmai.android.core.importer.WahlapSupplementalFailure
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.timeout
import io.ktor.http.Url
import dev.fluentmai.android.core.importer.WahlapMusicDetailTarget
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

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
    private val onPlayerHome: (String) -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val fetcher: WahlapResilientFetcher = WahlapResilientFetcher(),
    private val supplementalPages: List<WahlapSupplementalPages.Page> = WahlapSupplementalPages.pages,
    private val mapRequestUrl: (String) -> String = { it },
) : Closeable {
    private val client = HttpClient(CIO) {
        install(HttpTimeout)
        install(HttpCookies) {
            default { seedWahlapCookies(credentials.cookies, Url(HOME_URL)) }
        }
        expectSuccess = false
    }

    suspend fun validateLogin() {
        Log.i(TAG, "Manual Wahlap cookie import: ${credentials.safeSummary()}")
        val home = request(
            label = "manual-home",
            category = WahlapRequestCategory.LOGIN_HOME,
            rawUrl = HOME_URL,
        ) { page ->
            if (page.statusCode !in 200..299) {
                throw WahlapHttpStatusException(page.statusCode)
            }
            if (looksLikeAuthFailure(page.body)) {
                throw IOException(
                    "Wahlap Cookie login failed: home page is not authenticated",
                    WahlapAuthFailurePageException("auth failure page"),
                )
            }
            page
        }
        onPlayerHome(home.body)
        onPlayerHome(enrichWahlapPlayerHome(home.body) { url ->
            fetchOptionalPage(url)
        })
    }

    suspend fun fetchScorePage(difficulty: Difficulty): String {
        val response = request(
            label = "manual-score ${difficulty.name}",
            category = WahlapRequestCategory.SCORE_PAGE,
            rawUrl = WahlapScorePageUrls.scorePageUrl(difficulty, incremental = true),
        ) { page ->
            if (page.contentType != null && !page.contentType.contains("html", ignoreCase = true)) {
                throw IOException(
                    "Wahlap score fetch failed: difficulty=${difficulty.name} unexpected content type",
                    WahlapNonRetryableException("unexpected content type"),
                )
            }
            if (looksLikeAuthFailure(page.body)) {
                throw IOException(
                    "Wahlap score fetch failed: difficulty=${difficulty.name} session is not authenticated",
                    WahlapAuthFailurePageException("auth failure page"),
                )
            }
            if (!looksLikeScorePage(page.body)) {
                // Retryable on purpose: a transiently truncated or garbled response can fail this
                // shape check; the bounded retry re-downloads and re-validates.
                throw IOException("Wahlap score fetch failed: difficulty=${difficulty.name} unexpected page")
            }
            page
        }
        return response.body
    }

    suspend fun fetchActivityPage(url: String): String {
        val safeUrl = requireNotNull(dev.fluentmai.android.core.importer.WahlapActivityParser.safeActivityUrl(url))
        val response = request(
            label = "play-records",
            category = WahlapRequestCategory.SUPPLEMENTAL_PAGE,
            rawUrl = safeUrl,
        )
        onDiagnostic("最近记录：${describeWahlapResponse(response.statusCode, response.finalUrl, response.body)}")
        validateActivityResponse(response.statusCode, response.finalUrl, response.body)
        return response.body
    }

    suspend fun fetchMusicDetail(target: WahlapMusicDetailTarget): String {
        val url = requireNotNull(dev.fluentmai.android.core.importer.WahlapPlayCountParser.safeDetailUrl(target.url))
        val response = request(
            label = "music-detail",
            category = WahlapRequestCategory.SUPPLEMENTAL_PAGE,
            rawUrl = url,
            detailReferer = WahlapScorePageUrls.scorePageUrl(target.sourceDifficulty),
        )
        onDiagnostic("单曲详情：${describeWahlapResponse(response.statusCode, response.finalUrl, response.body)}")
        validateActivityResponse(response.statusCode, response.finalUrl, response.body)
        return response.body
    }

    suspend fun fetchSupplementalScorePages(): WahlapSupplementalFetchResult {
        val pages = mutableListOf<WahlapSupplementalPage>()
        val failures = mutableListOf<WahlapSupplementalFailure>()
        supplementalPages.forEach { candidate ->
            coroutineContext.ensureActive()
            val response = runCatching {
                request(
                    label = "manual-${candidate.label}",
                    category = WahlapRequestCategory.SUPPLEMENTAL_PAGE,
                    rawUrl = candidate.url,
                ) { page ->
                    if (page.statusCode !in 200..299) {
                        throw WahlapHttpStatusException(page.statusCode)
                    }
                    if (page.contentType != null && !page.contentType.contains("html", ignoreCase = true)) {
                        throw IOException(
                            "Manual supplemental ${candidate.label} unexpected content type",
                            WahlapNonRetryableException("unexpected content type"),
                        )
                    }
                    if (looksLikeAuthFailure(page.body)) {
                        throw IOException(
                            "Manual supplemental ${candidate.label} session is not authenticated",
                            WahlapAuthFailurePageException("auth failure page"),
                        )
                    }
                    page
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                val message = sanitizeImportDiagnostic(redactor.redact(error.message ?: error::class.java.simpleName))
                failures += WahlapSupplementalFailure(candidate.label, message)
                Log.w(
                    TAG,
                    "Manual supplemental ${candidate.label} request failed: $message",
                )
                return@forEach
            }
            Log.i(
                TAG,
                "Manual supplemental ${candidate.label}: status=${response.statusCode} " +
                    "type=${response.contentType.orEmpty()} chars=${response.body.length} " +
                    "scoreLike=${looksLikeScorePage(response.body)}",
            )
            pages += WahlapSupplementalPage(label = candidate.label, html = response.body)
        }
        return WahlapSupplementalFetchResult(pages, failures)
    }

    /** Best-effort fetch for optional player-profile enrichment: null on any failure. */
    private suspend fun fetchOptionalPage(url: String): String? =
        runCatching {
            request(
                label = "player-collection",
                category = WahlapRequestCategory.SUPPLEMENTAL_PAGE,
                rawUrl = url,
            ) { response ->
                if (response.statusCode !in 200..299) {
                    throw WahlapHttpStatusException(response.statusCode)
                }
                response
            }.body.takeIf { !looksLikeAuthFailure(it) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.i(TAG, "Player collection page skipped: ${redactor.redact(error.message ?: error::class.java.simpleName)}")
            null
        }

    private suspend fun request(
        label: String,
        category: WahlapRequestCategory,
        rawUrl: String,
        detailReferer: String? = null,
        validate: suspend (HttpResponse) -> HttpResponse = { it },
    ): HttpResponse =
        try {
            fetcher.fetch(
                category = category,
                onAttempt = { attemptLog ->
                    Log.i(TAG, "Wahlap manual request $label ${attemptLog.toSafeLogLine()}")
                    onDiagnostic("请求尝试 $label ${attemptLog.toSafeLogLine()}")
                },
            ) { profile, meta ->
                val response = client.get(mapRequestUrl(rawUrl)) {
                    timeout {
                        requestTimeoutMillis = profile.requestTimeoutMs
                        connectTimeoutMillis = profile.connectTimeoutMs
                    }
                    headers {
                        val defaults = defaultNavigationHeaders()
                        defaults.forEach { (name, value) ->
                            append(name, credentials.headers[name] ?: value)
                        }
                        credentials.headers
                            .filterKeys { it !in defaults.keys }
                            .forEach { (name, value) -> append(name, value) }
                        if (dev.fluentmai.android.core.importer.WahlapActivityParser.safeActivityUrl(rawUrl) != null) {
                            set(HttpHeaders.AcceptEncoding, "identity")
                            set(HttpHeaders.Referrer, detailReferer ?: HOME_URL)
                            set("Sec-Fetch-Site", "same-origin")
                            set(HttpHeaders.UserAgent, credentials.headers[HttpHeaders.UserAgent] ?: WahlapImportHttpClient.WX_ANDROID_UA)
                        }
                    }
                }
                val page = HttpResponse(
                    statusCode = response.status.value,
                    contentType = response.headers[HttpHeaders.ContentType],
                    body = response.bodyAsText(),
                    finalUrl = response.call.request.url.toString(),
                )
                meta.httpStatus = page.statusCode
                meta.responseChars = page.body.length.toLong()
                if (category != WahlapRequestCategory.AUTH_CALLBACK &&
                    category != WahlapRequestCategory.AUTH_AUTHORIZE &&
                    page.statusCode !in 200..299
                ) {
                    throw WahlapHttpStatusException(page.statusCode)
                }
                onDiagnostic("$label：${describeWahlapResponse(page.statusCode, page.finalUrl, page.body)}；类型=${page.contentType}")
                validate(page)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onDiagnostic("$label 请求异常：${diagnosticException(error)}")
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

    private companion object {
        private const val TAG = "WahlapManualCookie"
        private const val HOME_URL = "https://maimai.wahlap.com/maimai-mobile/home/"

        private fun defaultNavigationHeaders(): Map<String, String> =
            linkedMapOf(
                HttpHeaders.Connection to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                HttpHeaders.UserAgent to WahlapImportHttpClient.WX_ANDROID_UA,
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
                html.contains("登录失败") ||
                dev.fluentmai.android.core.importer.WahlapActivityParser.hasErrorPage(html) ||
                dev.fluentmai.android.core.importer.WahlapActivityParser.errorCode(html) != null
        }

        private fun looksLikeScorePage(html: String): Boolean =
            dev.fluentmai.android.core.importer.WahlapScorePageValidation.isScorePage(html)
    }
}
