package dev.fluentmai.android

import android.util.Log
import dev.fluentmai.android.core.importer.measure
import dev.fluentmai.android.core.model.DiagnosticStage
import dev.fluentmai.android.core.importer.WahlapAttemptLog
import dev.fluentmai.android.core.importer.WahlapAuthFailurePageException
import dev.fluentmai.android.core.importer.WahlapHttpStatusException
import dev.fluentmai.android.core.importer.WahlapNonRetryableException
import dev.fluentmai.android.core.importer.WahlapRequestCategory
import dev.fluentmai.android.core.importer.WahlapResilientFetcher
import dev.fluentmai.android.core.importer.WahlapScorePageUrls
import dev.fluentmai.android.core.importer.WahlapSupplementalPage
import dev.fluentmai.android.core.importer.WahlapSupplementalFetchResult
import dev.fluentmai.android.core.importer.WahlapSupplementalFailure
import dev.fluentmai.android.core.importer.WahlapMusicDetailTarget
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Login + page fetch orchestration using the same [WahlapOAuthAttempt] that generated authorize.
 * Callback, home and all import pages retain its jar; close the attempt when done.
 *
 * Retry policy comes from [WahlapRequestCategory]: score, home, and supplemental GETs are
 * idempotent and retried with backoff (including HTTP-level transient statuses), while the OAuth
 * callback ([WahlapRequestCategory.AUTH_CALLBACK]) is attempted exactly once because its
 * authorization code is single-use — its non-2xx status is returned, not thrown, so the home
 * probe below still runs and decides the login outcome.
 */
class WahlapHttpScorePageClient(
    private val redactor: PrivacyRedactor,
    private val onPlayerHome: (String) -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val attempt: WahlapOAuthAttempt,
    private val supplementalPages: List<WahlapSupplementalPages.Page> = WahlapSupplementalPages.pages,
    private val mapRequestUrl: (String) -> String = { it },
    private val fetcher: WahlapResilientFetcher = WahlapResilientFetcher(),
    private val onRequestAttempt: (WahlapRequestCategory, dev.fluentmai.android.core.model.DiagnosticRequestLabel, WahlapAttemptLog) -> Unit = { _, _, _ -> },
    private val observer: dev.fluentmai.android.core.importer.ImportTimingObserver = dev.fluentmai.android.core.importer.ImportTimingObserver.None,
) : AutoCloseable {
    private fun httpClient(): WahlapImportHttpClient = attempt.httpClient

    suspend fun login(authUrl: String) {
        val normalizedAuthUrl = normalizeWahlapAuthUrl(authUrl)
        Log.i(
            TAG,
            "Wahlap auth request: ${safeUrlSummary(normalizedAuthUrl)} " +
                "cookiesBefore=${cookieSummary()}",
        )

        val auth = observer.measure(DiagnosticStage.CALLBACK_PROCESSING) { request(
            label = "auth",
            category = WahlapRequestCategory.AUTH_CALLBACK,
            rawUrl = normalizedAuthUrl,
        ) }
        Log.i(
            TAG,
            "Wahlap auth response status=${auth.statusCode} " +
                "final=${safeUrlSummary(auth.finalUrl)} cookies=${cookieSummary()}",
        )
        if (auth.statusCode !in 200..299) {
            Log.w(
                TAG,
                "Wahlap auth returned status=${auth.statusCode} " +
                    "final=${safeUrlSummary(auth.finalUrl)}; checking home page before failing",
            )
        }

        val home = request(
            label = "home",
            category = WahlapRequestCategory.LOGIN_HOME,
            rawUrl = WahlapImportHttpClient.HOME_URL,
        ) { page ->
            if (page.statusCode !in 200..299) {
                throw WahlapHttpStatusException(page.statusCode)
            }
            if (looksLikeAuthFailure(page.body)) {
                throw WahlapAuthorizationRetryRequiredException()
            }
            page
        }
        Log.i(
            TAG,
            "Wahlap home response status=${home.statusCode} " +
                "final=${safeUrlSummary(home.finalUrl)} cookies=${cookieSummary()}",
        )
        onPlayerHome(home.body)
        onPlayerHome(
            enrichWahlapPlayerHome(home.body) { url ->
                fetchOptionalPage(url)
            },
        )
    }

    suspend fun fetchScorePage(difficulty: Difficulty): String {
        val response = request(
            label = "score ${difficulty.name}",
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
                    label = candidate.label,
                    category = WahlapRequestCategory.SUPPLEMENTAL_PAGE,
                    rawUrl = candidate.url,
                ) { page ->
                    if (page.statusCode !in 200..299) {
                        throw WahlapHttpStatusException(page.statusCode)
                    }
                    if (page.contentType != null && !page.contentType.contains("html", ignoreCase = true)) {
                        throw IOException(
                            "Supplemental ${candidate.label} unexpected content type",
                            WahlapNonRetryableException("unexpected content type"),
                        )
                    }
                    if (looksLikeAuthFailure(page.body)) {
                        throw IOException(
                            "Supplemental ${candidate.label} session is not authenticated",
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
                    "Supplemental ${candidate.label} request failed: $message",
                )
                return@forEach
            }
            Log.i(
                TAG,
                "Supplemental ${candidate.label}: status=${response.statusCode} " +
                    "type=${response.contentType.orEmpty()} chars=${response.body.length} " +
                    "scoreLike=${looksLikeScorePage(response.body)} " +
                    "ratingLike=${looksLikeRatingTargetPage(response.body)}",
            )
            pages += WahlapSupplementalPage(label = candidate.label, html = response.body)
        }
        return WahlapSupplementalFetchResult(pages, failures)
    }

    /** Best-effort fetch for optional player-profile enrichment: null on any failure. */
    private suspend fun fetchOptionalPage(url: String): String? =
        runCatching {
            val page = request(
                label = "player-collection",
                category = WahlapRequestCategory.SUPPLEMENTAL_PAGE,
                rawUrl = url,
            ) { response ->
                if (response.statusCode !in 200..299) {
                    throw WahlapHttpStatusException(response.statusCode)
                }
                response
            }
            page.body.takeIf { !looksLikeAuthFailure(it) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.i(TAG, "Player collection page skipped: ${redactor.redact(error.message ?: error::class.java.simpleName)}")
            null
        }

    override fun close() = attempt.close()

    /**
     * Runs one categorized Wahlap GET with the category's retry policy. HTTP-level status
     * validation happens inside the fetch so transient server statuses take part in the retry
     * loop — except for the auth callback, whose status is inspected by the caller.
     */
    private suspend fun request(
        label: String,
        category: WahlapRequestCategory,
        rawUrl: String,
        detailReferer: String? = null,
        validate: suspend (WahlapPageResponse) -> WahlapPageResponse = { it },
    ): WahlapPageResponse =
        try {
            fetcher.fetch(
                category = category,
                onAttempt = { attemptLog ->
                    onRequestAttempt(category, diagnosticRequestLabel(label), attemptLog)
                    logAttempt(label, attemptLog)
                },
            ) { profile, meta ->
                val response = httpClient().fetchPage(mapRequestUrl(rawUrl), category, profile, detailReferer)
                meta.httpStatus = response.statusCode
                meta.responseChars = response.body.length.toLong()
                if (category != WahlapRequestCategory.AUTH_CALLBACK &&
                    category != WahlapRequestCategory.AUTH_AUTHORIZE &&
                    response.statusCode !in 200..299
                ) {
                    throw WahlapHttpStatusException(response.statusCode)
                }
                onDiagnostic("$label：${describeWahlapResponse(response.statusCode, response.finalUrl, response.body)}；类型=${response.contentType}")
                validate(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onDiagnostic("$label 请求异常：${diagnosticException(error)}")
            throw IOException("$label request failed: ${redactor.redact(error.message ?: error::class.java.simpleName)}", error)
        }

    private fun logAttempt(label: String, attemptLog: WahlapAttemptLog) {
        Log.i(TAG, "Wahlap request $label ${attemptLog.toSafeLogLine()}")
        onDiagnostic("请求尝试 $label ${attemptLog.toSafeLogLine()}")
    }

    private suspend fun cookieSummary(): String = httpClient().cookieSummary()

    private fun looksLikeAuthFailure(html: String): Boolean {
        val normalized = html.lowercase()
        return normalized.contains("请在微信客户端打开链接") ||
            normalized.contains("please open in wechat") ||
            normalized.contains("/wc_auth/oauth/authorize/") ||
            normalized.contains("open.weixin.qq.com/connect/oauth2/authorize") ||
            html.contains("登录失败") ||
            dev.fluentmai.android.core.importer.WahlapActivityParser.hasErrorPage(html) ||
            dev.fluentmai.android.core.importer.WahlapActivityParser.errorCode(html) != null
    }

    private fun looksLikeScorePage(html: String): Boolean =
        dev.fluentmai.android.core.importer.WahlapScorePageValidation.isScorePage(html)

    private fun looksLikeRatingTargetPage(html: String): Boolean =
        html.contains("DX评分对象曲目") ||
            html.contains("DXè©åå¯¹è±¡æ²ç®") ||
            html.contains("rating", ignoreCase = true) &&
            html.contains("music", ignoreCase = true)

    private fun safeUrlSummary(url: String): String =
        runCatching {
            val uri = java.net.URI(url)
            val query = uri.rawQuery.orEmpty().lowercase()
            val path = uri.rawPath.orEmpty()
            "scheme=${uri.scheme} host=${uri.host} path=$path " +
                "hasCode=${query.contains("code=")} hasState=${query.contains("state=")} " +
                "duplicatedHttpInPath=${path.lowercase().contains("http://")}"
        }.getOrElse { "unparseable" }

    private companion object {
        private const val TAG = "WahlapHttpScore"
    }
}

internal fun normalizeWahlapAuthUrl(authUrl: String): String {
    val trimmed = authUrl.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        throw IllegalArgumentException("Captured auth URL must start with http:// or https://")
    }
    if (trimmed.startsWith("http://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx", ignoreCase = true)) {
        return "https://" + trimmed.removePrefix("http://")
    }
    return trimmed
}
