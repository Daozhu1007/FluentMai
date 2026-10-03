package dev.fluentmai.android

import android.util.Log
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Builds the WeChat OAuth authorize entry URL by following the Wahlap authorize redirect chain.
 *
 * Each call begins a fresh complete OAuth attempt, replacing any unfinished attempt. Its client
 * remains owned by the handoff after authorize succeeds, so callback/home/import share the same
 * jar. Authorize is attempted once; failure closes only this attempt.
 */
class WahlapWechatAuthUrlClient(
    private val redactor: PrivacyRedactor,
    private val handoff: WahlapAuthCaptureHandoff = WahlapAuthCaptureStore,
    private val authorizeUrl: String = MAIMAI_DX_AUTHORIZE_URL,
    private val acceptRedirect: (String) -> Boolean = {
        it.contains("tgk-wcaime.wahlap.com", ignoreCase = true) || it.contains("maimai-dx", ignoreCase = true)
    },
) {
    fun maimaiDxAuthUrl(): String {
        val attempt = handoff.beginAttempt()
        try {
            return kotlinx.coroutines.runBlocking {
                val finalUrl = attempt.httpClient.fetchAuthorizeRedirectFinalUrl(authorizeUrl)
                Log.i(
                    TAG,
                    "Generated Wahlap auth URL final=${safeUrlSummary(finalUrl)} " +
                        "cookies=${attempt.httpClient.cookieSummary()}",
                )

                if (!acceptRedirect(finalUrl) || !handoff.isCurrent(attempt)) {
                    throw IOException("Unexpected Wahlap auth redirect")
                }

                finalUrl.replace("redirect_uri=https", "redirect_uri=http")
            }
        } catch (error: CancellationException) {
            handoff.discardPendingAttempt(attempt)
            throw error
        } catch (error: Exception) {
            handoff.discardPendingAttempt(attempt)
            throw IOException(
                "生成舞萌微信授权地址失败：${redactor.redact(error.message ?: error::class.java.simpleName)}",
                error,
            )
        }
    }

    private companion object {
        private const val TAG = "WahlapAuthUrl"
        private const val MAIMAI_DX_AUTHORIZE_URL =
            "https://tgk-wcaime.wahlap.com/wc_auth/oauth/authorize/maimai-dx"

        private fun safeUrlSummary(url: String): String =
            runCatching {
                val uri = java.net.URI(url)
                val query = uri.rawQuery.orEmpty().lowercase()
                "scheme=${uri.scheme} host=${uri.host} path=${uri.rawPath.orEmpty()} " +
                    "hasRedirectUri=${query.contains("redirect_uri=")} " +
                    "hasCode=${query.contains("code=")} hasState=${query.contains("state=")}"
            }.getOrElse { "unparseable" }
    }
}
