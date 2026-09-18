package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapPlayCountParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WahlapSessionCookiesTest {
    @Test fun realHttpRequestPreservesOpaqueIdxAndUpdatesCookiesAcrossRedirectsAndPages() = runBlocking {
        // All synthetic, loopback-only; no real credentials or player idx are used.
        val server = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        val base = "http://127.0.0.1:${server.localPort}"
        val observed = Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            repeat(3) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val uri = URI(requireNotNull(input.readLine()).split(' ')[1])
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    val cookie = headers.firstOrNull { it.startsWith("Cookie:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim().orEmpty()
                    observed += Triple(uri.path, cookie, uri.rawQuery.orEmpty())
                    val (status, extraHeaders, body) = when (uri.path) {
                        "/login" -> Triple("302 Found", "Set-Cookie: _t=fresh+/=; Path=/; HttpOnly\r\nLocation: $base/list\r\n", "")
                        "/list" -> Triple("200 OK", "Set-Cookie: _t=list%2Btoken; Path=/; HttpOnly\r\n", "list")
                        else -> Triple("200 OK", "", "detail")
                    }
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\n${extraHeaders}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                            .toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                }
            }
        }
        val client = HttpClient(CIO) {
            install(HttpCookies) { default {
                seedWahlapCookies(mapOf("_t" to "initial%2Btoken", "userId" to "synthetic"), Url(base))
            } }
        }
        try {
            assertEquals("list", client.get("$base/login").bodyAsText())
            val idx = "92e34cce-SYNTHETIC+/x=="
            val query = "idx=92e34cce-SYNTHETIC%2B%2Fx%3D%3D"
            val officialUrl = requireNotNull(WahlapPlayCountParser.safeDetailUrl("${WahlapPlayCountParser.DETAIL_URL}?$query"))
            client.get("$base/detail?${URI(officialUrl).rawQuery}") { header(HttpHeaders.Referrer, "$base/list") }.bodyAsText()
            served.get(10, TimeUnit.SECONDS)
            assertEquals(3, observed.size)
            assertTrue(observed[0].second.contains("_t=initial%2Btoken"))
            assertTrue(observed[1].second.contains("_t=fresh+/="))
            assertTrue(observed[2].second.contains("_t=list%2Btoken"))
            assertFalse(observed[2].second.contains("initial"))
            assertFalse(observed.any { "%252B" in it.second })
            assertEquals(query, observed[2].third)
            assertEquals(idx, URLDecoder.decode(observed[2].third.substringAfter("idx="), "UTF-8"))
        } finally {
            client.close()
            server.close()
            executor.shutdownNow()
        }
    }
}
