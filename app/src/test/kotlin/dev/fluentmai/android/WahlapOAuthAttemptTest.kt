package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapRequestCatalog
import dev.fluentmai.android.core.importer.WahlapRequestCategory
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.http.Cookie
import io.ktor.http.Url
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WahlapOAuthAttemptTest {
    private fun handoff() = WahlapAuthCaptureHandoff {
        WahlapOAuthAttempt(WahlapImportHttpClient(isAuthCallback = { it.contains("/callback/") }))
    }

    @Test fun authorizeCaptureCallbackHomeAndScoreShareOneJarThenNextImportIsFresh() {
        val scoreHtml = File("../fixtures/wahlap_valid_fixture.html").readText()
        WahlapLoopbackServer { request ->
            when (request.path) {
                "/authorize" -> WahlapLoopbackServer.Response(302, headers = mapOf(
                    "Location" to "/wechat-entry", "Set-Cookie" to "authorizeStage=fixture-authorize; Path=/"))
                "/callback/maimai-dx" -> WahlapLoopbackServer.Response(headers = mapOf(
                    "Set-Cookie" to "authenticated=fixture-home; Path=/"))
                "/maimai-mobile/record/musicSort/search/" -> WahlapLoopbackServer.Response(body = scoreHtml)
                else -> WahlapLoopbackServer.Response()
            }
        }.use { server ->
            val handoff = handoff()
            val auth = WahlapWechatAuthUrlClient(PrivacyRedactor(), handoff, server.url("/authorize"), { true })
            repeat(2) { index ->
                assertEquals(server.url("/wechat-entry"), auth.maimaiDxAuthUrl())
                val callback = server.url("/callback/maimai-dx?code=fixture-$index")
                assertEquals(1, handoff.captureCallback(callback,
                    "GET /callback/maimai-dx HTTP/1.1\r\nCookie: browser=fixture-browser-$index\r\nUser-Agent: Fixture-WeChat\r\n"))
                // Removing the handoff copy must not clear authorize cookies or the attached replay.
                assertFalse(handoff.consumeReplayState().isEmpty)
                val attempt = handoff.consumeAttempt(callback)
                assertTrue(handoff.consumeReplayState().isEmpty)
                handoff.discardPendingAttempt() // Simulates capture shutdown after ownership transfer.
                assertFalse(attempt.isClosed)
                WahlapHttpScorePageClient(
                    redactor = PrivacyRedactor(), attempt = attempt,
                    mapRequestUrl = { url -> server.url(Url(url).encodedPath) },
                ).use { client ->
                    runBlocking {
                        client.login(callback)
                        client.fetchScorePage(Difficulty.BASIC)
                    }
                }
                assertTrue(attempt.isClosed)
                val authorize = server.requestsAt("/authorize")[index]
                assertTrue("A new import must start empty", authorize.headers["cookie"].isNullOrBlank())
                assertTrue(server.requestsAt("/wechat-entry")[index].headers["cookie"].orEmpty().contains("authorizeStage=fixture-authorize"))
                val captured = server.requestsAt("/callback/maimai-dx")[index]
                assertTrue(captured.headers["cookie"].orEmpty().contains("authorizeStage=fixture-authorize"))
                assertTrue(captured.headers["cookie"].orEmpty().contains("browser=fixture-browser-$index"))
                assertEquals("Fixture-WeChat", captured.headers["user-agent"])
                for (path in listOf("/maimai-mobile/home/", "/maimai-mobile/record/musicSort/search/")) {
                    val cookies = server.requestsAt(path)[index].headers["cookie"].orEmpty()
                    assertTrue(cookies.contains("authorizeStage=fixture-authorize"))
                    assertTrue(cookies.contains("browser=fixture-browser-$index"))
                    assertTrue(cookies.contains("authenticated=fixture-home"))
                    assertFalse(cookies.contains("browser=fixture-browser-${1 - index}"))
                }
            }
            assertEquals(2, server.requestsAt("/authorize").size)
            assertEquals(2, server.requestsAt("/callback/maimai-dx").size)
        }
    }

    @Test fun startingAttemptClosesUnfinishedPreviousAttemptAndClearsCapturedState() = runBlocking {
        val handoff = handoff()
        val first = handoff.beginAttempt()
        first.httpClient.cookieStorage.addCookie(Url("https://maimai.wahlap.com/"), Cookie("old", "fixture-old"))
        handoff.captureCallback("http://fixture/callback", "GET / HTTP/1.1\r\nCookie: browser=fixture-old\r\n")
        val second = handoff.beginAttempt()
        try {
            assertTrue(first.isClosed)
            assertFalse(second.isClosed)
            assertEquals("count=0", second.httpClient.cookieSummary())
            assertTrue(handoff.consumeReplayState().isEmpty)
            assertThrows(IllegalStateException::class.java) { handoff.consumeAttempt("http://fixture/callback") }
            // A late failure from A cannot discard the new B.
            handoff.discardPendingAttempt(first)
            assertTrue(handoff.isCurrent(second))
        } finally { handoff.discardPendingAttempt() }
        assertTrue(second.isClosed)
    }

    @Test fun callbackOwnershipTransfersExactlyOnceAndWrongCallbackCannotTakeIt() {
        val handoff = handoff()
        val attempt = handoff.beginAttempt()
        try {
            assertEquals(0, handoff.captureCallback("http://fixture/callback?code=fixture-a", ""))
            assertNull(handoff.captureCallback("http://fixture/callback?code=fixture-b", ""))
            assertThrows(IllegalStateException::class.java) { handoff.consumeAttempt("http://fixture/callback?code=fixture-b") }
            assertSame(attempt, handoff.consumeAttempt("http://fixture/callback?code=fixture-a"))
            assertThrows(IllegalStateException::class.java) { handoff.consumeAttempt("http://fixture/callback?code=fixture-a") }
            assertNull(handoff.captureCallback("http://fixture/callback?code=fixture-a", ""))
        } finally { attempt.close(); handoff.discardPendingAttempt() }
    }

    @Test fun rejectedAuthorizeClosesAttemptAndLeavesNoPendingReplay() {
        val attempts = mutableListOf<WahlapOAuthAttempt>()
        val handoff = WahlapAuthCaptureHandoff { WahlapOAuthAttempt().also(attempts::add) }
        WahlapLoopbackServer().use { server ->
            val auth = WahlapWechatAuthUrlClient(PrivacyRedactor(), handoff, server.url("/authorize"), { false })
            assertThrows(IOException::class.java) { auth.maimaiDxAuthUrl() }
            assertTrue(attempts.single().isClosed)
            assertNull(handoff.captureCallback("http://fixture/callback", ""))
            assertEquals(1, server.requestsAt("/authorize").size)
        }
    }

    @Test fun closeIsIdempotentAndClosedAttemptCannotMakeAnotherRequest() = runBlocking {
        val attempt = WahlapOAuthAttempt()
        attempt.close()
        attempt.close()
        try {
            attempt.httpClient.fetchPage("http://127.0.0.1:1/fixture", WahlapRequestCategory.SCORE_PAGE,
                WahlapRequestCatalog.timeoutProfile(WahlapRequestCategory.SCORE_PAGE))
            fail("closed attempt must reject requests")
        } catch (_: IllegalStateException) { assertTrue(attempt.isClosed) }
    }

    @Test fun browserCookieUpdatesMatchingAuthorizeCookieWithoutDiscardingOtherAuthorizeCookies() = runBlocking {
        WahlapLoopbackServer().use { server ->
            val handoff = handoff()
            val attempt = handoff.beginAttempt()
            attempt.httpClient.cookieStorage.addCookie(Url(server.url("/")), Cookie("shared", "fixture-authorize", path = "/"))
            attempt.httpClient.cookieStorage.addCookie(Url(server.url("/")), Cookie("authorizeOnly", "fixture-retained", path = "/"))
            val callback = server.url("/callback/maimai-dx?code=fixture-only")
            handoff.captureCallback(callback, "GET / HTTP/1.1\r\nCookie: shared=fixture-browser\r\n")
            handoff.consumeAttempt(callback).use {
                it.httpClient.fetchPage(callback, WahlapRequestCategory.AUTH_CALLBACK,
                    WahlapRequestCatalog.timeoutProfile(WahlapRequestCategory.AUTH_CALLBACK))
            }
            val cookies = server.requestsAt("/callback/maimai-dx").single().headers["cookie"].orEmpty()
            assertTrue(cookies.contains("shared=fixture-browser"))
            assertFalse(cookies.contains("shared=fixture-authorize"))
            assertTrue(cookies.contains("authorizeOnly=fixture-retained"))
        }
    }

    @Test fun freshRetryGeneratesDistinctAuthorizeAndDoesNotCarryRejectedBrowserContext() = runBlocking {
        var generation = 0
        val attempts = mutableListOf<WahlapOAuthAttempt>()
        val handoff = WahlapAuthCaptureHandoff {
            WahlapOAuthAttempt(WahlapImportHttpClient(isAuthCallback = { it.contains("/callback/") })).also(attempts::add)
        }
        WahlapLoopbackServer { request ->
            when (request.path) {
                "/authorize" -> {
                    generation++
                    WahlapLoopbackServer.Response(302, headers = mapOf("Location" to "/entry-$generation",
                        "Set-Cookie" to "authorizeOnly=fixture-$generation; Path=/"))
                }
                "/callback/maimai-dx" -> WahlapLoopbackServer.Response(404)
                "/maimai-mobile/home/" -> WahlapLoopbackServer.Response(body = "<html>登录失败</html>")
                else -> WahlapLoopbackServer.Response()
            }
        }.use { server ->
            val auth = WahlapWechatAuthUrlClient(PrivacyRedactor(), handoff, server.url("/authorize"), { true })
            for (number in 1..2) {
                assertEquals(server.url("/entry-$number"), auth.maimaiDxAuthUrl())
                assertEquals(server.url("/entry-$number"), auth.maimaiDxAuthUrl()) // double click uses same authorize entry
                val callback = server.url("/callback/maimai-dx?code=fixture-$number")
                handoff.captureCallback(callback, "GET / HTTP/1.1\r\nCookie: browser=fixture-$number\r\nUser-Agent: Fixture-UA-$number\r\n")
                val attempt = handoff.consumeAttempt(callback)
                WahlapHttpScorePageClient(PrivacyRedactor(), attempt = attempt,
                    mapRequestUrl = { server.url(Url(it).encodedPath) }).use { client ->
                    try { client.login(callback); fail("expected fresh-auth rejection") }
                    catch (error: IOException) {
                        assertEquals(ImportFailureCategory.AUTHORIZATION_RETRY_REQUIRED, importFailureCategory(error, false))
                    }
                    // Even an accidental second login cannot replay this code.
                    try { client.login(callback); fail("callback must be single-use") } catch (_: IOException) { }
                }
                assertTrue(attempt.isClosed)
                assertTrue(server.requestsAt("/authorize")[number - 1].headers["cookie"].isNullOrBlank())
                val sent = server.requestsAt("/callback/maimai-dx")[number - 1]
                assertEquals("Fixture-UA-$number", sent.headers["user-agent"])
                assertTrue(sent.headers["cookie"].orEmpty().contains("browser=fixture-$number"))
                assertFalse(sent.headers["cookie"].orEmpty().contains("browser=fixture-${3 - number}"))
            }
            assertNotSame(attempts[0].httpClient, attempts[1].httpClient)
            assertEquals(2, generation); assertEquals(2, server.requestsAt("/callback/maimai-dx").size)
        }
    }

    @Test fun cancellingAuthorizeWhileIoRunsDoesNotBlockCleanupOrResurrectAttempt() = runBlocking {
        val handoff = handoff()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val work = async(Dispatchers.IO) {
            runCatching { handoff.authorize { started.countDown(); release.await(); "http://fixture/entry" } }
        }
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertNull("No callback may be captured before authorize identity is registered",
                handoff.captureCallback("http://fixture/stale", ""))
            handoff.discardPendingAttempt()
            assertNull(handoff.captureCallback("http://fixture/stale", ""))
        } finally { release.countDown() }
        assertTrue(work.await().isFailure)
        assertNull(handoff.captureCallback("http://fixture/stale", ""))
    }

    @Test fun simultaneousAuthorizeRequestsCreateOnlyOneAttemptAndOneEntry() = runBlocking {
        val attempts = java.util.Collections.synchronizedList(mutableListOf<WahlapOAuthAttempt>())
        val handoff = WahlapAuthCaptureHandoff { WahlapOAuthAttempt().also(attempts::add) }
        val generated = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val generate: (WahlapOAuthAttempt) -> String = {
            generated.incrementAndGet(); entered.countDown(); release.await(); "http://fixture/entry"
        }
        val first = async(Dispatchers.IO) { handoff.authorize(generate = generate) }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { handoff.authorize(generate = generate) }
        try {
            assertNull(handoff.captureCallback("http://fixture/stale", ""))
        } finally { release.countDown() }
        assertEquals("http://fixture/entry", first.await())
        assertEquals("http://fixture/entry", second.await())
        assertEquals(1, generated.get()); assertEquals(1, attempts.size)
        handoff.discardPendingAttempt(); assertTrue(attempts.single().isClosed)
    }
}
