package dev.fluentmai.android

import android.util.Log
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Builds the WeChat OAuth authorize entry URL by following the Wahlap authorize redirect chain.
 *
 * Each call uses its own throwaway [WahlapImportHttpClient], so the authorize request starts a
 * fresh session and can never inherit cookies from a previous import or from an import that runs
 * later. Any stale captured callback state is discarded first: a new authorize entry must never
 * replay an older authorization attempt. The request is deliberately attempted once: it starts
 * (not completes) an authorization, and if it fails the user can simply tap the hook link again.
 */
class WahlapWechatAuthUrlClient(
    private val redactor: PrivacyRedactor,
) {
    fun maimaiDxAuthUrl(): String {
        val httpClient = WahlapImportHttpClient()
        try {
            WahlapAuthCaptureStore.clear()
            return kotlinx.coroutines.runBlocking {
                val finalUrl = httpClient.fetchAuthorizeRedirectFinalUrl(MAIMAI_DX_AUTHORIZE_URL)
                Log.i(
                    TAG,
                    "Generated Wahlap auth URL final=${safeUrlSummary(finalUrl)} " +
                        "cookies=${httpClient.cookieSummary()}",
                )

                if (!finalUrl.contains("tgk-wcaime.wahlap.com", ignoreCase = true) &&
                    !finalUrl.contains("maimai-dx", ignoreCase = true)
                ) {
                    throw IOException("Unexpected Wahlap auth redirect")
                }

                finalUrl.replace("redirect_uri=https", "redirect_uri=http")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException(
                "生成舞萌微信授权地址失败：${redactor.redact(error.message ?: error::class.java.simpleName)}",
                error,
            )
        } finally {
            runCatching { httpClient.close() }
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
