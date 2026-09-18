package dev.fluentmai.android

import org.junit.Assert.*
import org.junit.Test

class WahlapActivityResponseTest {
    private val url = "https://maimai.wahlap.com/maimai-mobile/record/"
    @Test fun errorNamesInScriptsAreNotLoginFailures() {
        validateActivityResponse(200, url, "<html><script>var x='title_error';</script><div class='playlog_top_container'>record</div></html>")
    }
    @Test fun errorsAreSpecificAndNeverIncludeCredentialsOrRawPages() {
        val http = runCatching { validateActivityResponse(503, url + "?token=secret", "private body") }.exceptionOrNull()
        assertEquals("HTTP 503", http?.message)
        val errorPage = runCatching { validateActivityResponse(200, url, "<img src='/img/title_error.png'><div>secret user</div>") }.exceptionOrNull()
        assertEquals("官方返回错误页面（HTTP 200）", errorPage?.message)
        val login = runCatching { validateActivityResponse(200, "https://example.com/oauth?code=secret", "") }.exceptionOrNull()
        assertTrue(login is WahlapActivityFetchException)
        assertFalse(login?.message.orEmpty().contains("secret"))
    }

    @Test fun officialErrorOnlyIncludesNumericCodeAndCanRenewDetailLink() {
        val error = runCatching { validateActivityResponse(200, url,
            "<img src='title_error.png'><div>错误码：12345</div><div>private user and token</div>") }.exceptionOrNull()
        assertEquals("官方返回错误页面（HTTP 200），错误码 12345", error?.message)
        assertTrue((error as WahlapActivityFetchException).canRefreshDetail)
    }
}
