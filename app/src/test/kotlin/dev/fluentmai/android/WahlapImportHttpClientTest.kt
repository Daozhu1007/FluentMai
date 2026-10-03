package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapRequestCatalog
import dev.fluentmai.android.core.importer.WahlapRequestCategory
import io.ktor.http.Cookie
import io.ktor.http.Url
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for AUTH-NET-1: each import run must get its own cookie session. The old
 * process-global client accumulated cookies across imports, creating cross-import contamination.
 * These tests do not establish a cause for observed callback 404/error 100001.
 */
class WahlapImportHttpClientTest {
    @Test
    fun repeatedImportSessionsDoNotReuseStaleCookieState() {
        val firstImport = WahlapImportHttpClient()
        val secondImport = WahlapImportHttpClient()
        try {
            runBlocking {
                firstImport.cookieStorage.addCookie(
                    Url("https://maimai.wahlap.com/"),
                    Cookie(name = "wgSession", value = "stale-session-from-previous-import"),
                )
                firstImport.cookieStorage.addCookie(
                    Url("https://tgk-wcaime.wahlap.com/"),
                    Cookie(name = "userId", value = "stale-user"),
                )

                val firstSummary = firstImport.cookieSummary()
                assertTrue(firstSummary.contains("wgSession"))
                assertTrue(firstSummary.contains("userId"))

                val secondSummary = secondImport.cookieSummary()
                assertEquals("count=0", secondSummary)
            }
        } finally {
            runCatching { firstImport.close() }
            runCatching { secondImport.close() }
        }
    }

    @Test
    fun freshImportSessionStartsWithAnEmptyCookieJar() {
        val client = WahlapImportHttpClient()
        try {
            runBlocking {
                assertEquals("count=0", client.cookieSummary())
            }
        } finally {
            runCatching { client.close() }
        }
    }

    @Test
    fun cookieSummaryExposesNamesOnlyAndNeverValues() {
        val client = WahlapImportHttpClient()
        try {
            runBlocking {
                client.cookieStorage.addCookie(
                    Url("https://maimai.wahlap.com/"),
                    Cookie(name = "_t", value = "super-secret-token-value"),
                )
                val summary = client.cookieSummary()
                assertTrue(summary.contains("_t"))
                assertFalse(summary.contains("super-secret-token-value"))
            }
        } finally {
            runCatching { client.close() }
        }
    }

    @Test
    fun authCallbackSeedsCapturedCookiesAndReplaysCapturedHeaders() {
        LoopbackServer().use { server ->
            val client = WahlapImportHttpClient(
                authReplay = WahlapAuthReplayState(
                    headers = mapOf("User-Agent" to "WeChat-Browser-UA"),
                    pendingAuthCookies = "wgSession=captured-session; userId=captured-user",
                ),
                // Loopback stand-in for the real tgk-wcaime.wahlap.com callback path.
                isAuthCallback = { it.contains("/wc_auth/oauth/callback/maimai-dx") },
            )
            try {
                val response = runBlocking {
                    client.fetchPage(
                        rawUrl = server.url("/wc_auth/oauth/callback/maimai-dx?code=synthetic"),
                        category = WahlapRequestCategory.AUTH_CALLBACK,
                        timeoutProfile = WahlapRequestCatalog
                            .timeoutProfile(WahlapRequestCategory.AUTH_CALLBACK),
                    )
                }

                assertEquals(200, response.statusCode)
                val authRequest = server.requestFor(path = "/wc_auth/oauth/callback/maimai-dx")
                // The captured cookies were seeded into this import's cookie jar, not sent as a raw header.
                assertTrue(authRequest.headers["cookie"].orEmpty().contains("wgSession=captured-session"))
                assertTrue(authRequest.headers["cookie"].orEmpty().contains("userId=captured-user"))
                // Captured headers are replayed verbatim…
                assertEquals("WeChat-Browser-UA", authRequest.headers["user-agent"])
            } finally {
                runCatching { client.close() }
            }
        }
    }

    @Test
    fun ordinaryPageRequestsNeverReplayCapturedAuthState() {
        LoopbackServer().use { server ->
            val client = WahlapImportHttpClient(
                authReplay = WahlapAuthReplayState(
                    headers = mapOf("User-Agent" to "WeChat-Browser-UA"),
                    pendingAuthCookies = "wgSession=captured-session",
                ),
                isAuthCallback = { it.contains("/wc_auth/oauth/callback/maimai-dx") },
            )
            try {
                val response = runBlocking {
                    client.fetchPage(
                        rawUrl = server.url("/maimai-mobile/home/"),
                        category = WahlapRequestCategory.LOGIN_HOME,
                        timeoutProfile = WahlapRequestCatalog
                            .timeoutProfile(WahlapRequestCategory.LOGIN_HOME),
                    )
                }

                assertEquals(200, response.statusCode)
                val homeRequest = server.requestFor(path = "/maimai-mobile/home/")
                assertFalse(
                    "Captured cookies must not leak into ordinary page requests",
                    homeRequest.headers.keys.any { it == "cookie" },
                )
                // v0.3.0 behavior preserved: the whole import run presents the captured browser UA.
                assertEquals("WeChat-Browser-UA", homeRequest.headers["user-agent"])
            } finally {
                runCatching { client.close() }
            }
        }
    }
}

/**
 * Minimal loopback HTTP server recording request headers for assertions. Serves one response
 * per accepted connection and closes it; loopback-only, no real credentials.
 */
private class LoopbackServer : AutoCloseable {
    data class RecordedRequest(val path: String, val headers: Map<String, String>)

    private val requests = Collections.synchronizedMap(mutableMapOf<String, RecordedRequest>())
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
    private val executor = Executors.newSingleThreadExecutor()

    init {
        @Suppress("unused")
        executor.submit {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    return@submit
                }
                runCatching {
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = reader.readLine().orEmpty()
                    val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?').orEmpty()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).trim().lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }
                    synchronized(requests) { requests[path] = RecordedRequest(path, headers) }
                    val body = "<html><body>ok</body></html>".toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.ISO_8859_1) + body,
                    )
                    socket.getOutputStream().flush()
                }
                runCatching { socket.close() }
            }
        }
    }

    fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

    fun requestFor(path: String): RecordedRequest {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            synchronized(requests) { requests[path]?.let { return it } }
            Thread.sleep(10)
        }
        error("No request observed for $path; observed=${requests.keys}")
    }

    override fun close() {
        runCatching { server.close() }
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }
}
