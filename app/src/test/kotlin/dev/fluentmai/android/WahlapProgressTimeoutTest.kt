package dev.fluentmai.android

import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WahlapProgressTimeoutTest {
    private val productionProfile = WahlapRequestCatalog.timeoutProfile(WahlapRequestCategory.SCORE_PAGE)
    private val shortProfile = productionProfile.copy(responseStartTimeoutMs = 750, readIdleTimeoutMs = 750)
    private val html get() = File("../fixtures/wahlap_valid_fixture.html").readText()

    private suspend fun fetch(server: WahlapStreamingServer, meta: WahlapAttemptMeta = WahlapAttemptMeta(),
        profile: WahlapTimeoutProfile = shortProfile): String = WahlapImportHttpClient().use { client ->
        client.fetchPage(server.url(), WahlapRequestCategory.SCORE_PAGE, profile, meta = meta).body
    }

    private suspend fun noConnections(server: WahlapStreamingServer) {
        withTimeout(3_000) { while (server.activeConnections.get() != 0) delay(10) }
    }

    private suspend fun importOnce(server: WahlapStreamingServer, profile: WahlapTimeoutProfile,
        persistence: BoundaryPersistence, logs: MutableList<WahlapAttemptLog>,
        onProgress: (ImportProgress) -> Unit = {},
        onMeta: (WahlapAttemptMeta) -> Unit = {}): RealWahlapImportResult =
        WahlapImportHttpClient().use { client ->
            val reporter = ImportPageReporter(onProgress)
            RealWahlapImportAdapter(difficulties = listOf(Difficulty.EXPERT)).importFetchedPages(
                "synthetic-stream", WahlapScorePageProvider {
                    reporter.page(ImportStage.Scores, 0, 1, "EXPERT 成绩页") {
                        WahlapResilientFetcher(delayFor = {}).fetch(WahlapRequestCategory.SCORE_PAGE, onAttempt = { log ->
                            logs += log; reporter.requestAttempt(WahlapRequestCategory.SCORE_PAGE, log)
                        }) { _, meta ->
                            onMeta(meta)
                            val body = client.fetchPage(server.url(), WahlapRequestCategory.SCORE_PAGE, profile, meta = meta).body
                            check(WahlapScorePageValidation.isScorePage(body))
                            body
                        }
                    }
                }, persistence)
        }

    @Test(timeout = 10_000) fun fastCompletePageParsesAndPersistsOnce() = runBlocking {
        val bytes = html.toByteArray()
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders(bytes.size.toLong())
            socket.getOutputStream().write(bytes)
        }.use { server ->
            val persistence = BoundaryPersistence()
            val logs = mutableListOf<WahlapAttemptLog>()
            val result = importOnce(server, shortProfile, persistence, logs)
            assertEquals(WahlapImportOutcome.COMPLETE, result.outcome)
            assertEquals(3, result.parsedRecordCount)
            assertEquals(3, persistence.scores.size)
            assertEquals(1, persistence.batches.size)
            assertEquals(1, server.requests.size)
            assertEquals(bytes.size.toLong(), logs.single().responseBytes)
            noConnections(server)
        }
    }

    @Test(timeout = 180_000) fun continuousLargeScoreSurvivesMoreThan120SecondsWithOneRequest() = runBlocking {
        val large = html.replace("</body>", "<!--${"x".repeat(850_000)}--></body>")
        val bytes = large.toByteArray()
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders()
            val chunkSize = (bytes.size + 21) / 22
            bytes.asList().chunked(chunkSize).forEachIndexed { index, chunk ->
                if (index > 0) Thread.sleep(6_000)
                socket.scoreChunk(chunk.toByteArray())
            }
            socket.finishChunks()
        }.use { server ->
            val logs = mutableListOf<WahlapAttemptLog>()
            val persistence = BoundaryPersistence()
            val snapshots = Collections.synchronizedList(mutableListOf<ImportProgress>())
            val elapsed = measureTimeMillis {
                val importing = async(Dispatchers.IO) { importOnce(server, productionProfile, persistence, logs, snapshots::add) }
                delay(65_000)
                assertFalse(importing.isCompleted)
                assertEquals(ImportPageState.Loading, snapshots.last().pageState)
                assertEquals("EXPERT 成绩页", snapshots.last().pageName)
                assertEquals(0, snapshots.last().processedPages)
                val result = importing.await()
                assertEquals(WahlapImportOutcome.COMPLETE, result.outcome)
                assertEquals(3, result.parsedRecordCount)
            }
            assertTrue("real wall time must exceed 120 seconds", elapsed > 120_000)
            assertEquals(1, server.requests.size)
            assertEquals(WahlapAttemptOutcome.SUCCESS, logs.single().outcome)
            assertEquals(bytes.size.toLong(), logs.single().responseBytes)
            assertTrue(logs.single().progressChunks!! >= 22)
            assertEquals(3, persistence.scores.size)
            assertEquals(1, persistence.batches.size)
            println("AUTH-NET-TIMEOUT-1 slow_stream elapsed_ms=$elapsed bytes=${bytes.size} requests=${server.requests.size}")
            noConnections(server)
        }
    }

    @Test(timeout = 10_000) fun acceptedConnectionWithoutHeadersHitsResponseStartTimeout() = runBlocking {
        WahlapStreamingServer { _, socket -> socket.waitForClientClose() }.use { server ->
            val meta = WahlapAttemptMeta()
            val error = runCatching { fetch(server, meta) }.exceptionOrNull()
            assertTrue(error is WahlapScoreDownloadException)
            assertEquals(DiagnosticDownloadFailure.RESPONSE_START_TIMEOUT, meta.downloadFailure)
            assertNull(meta.httpStatus)
            assertEquals(0L, meta.responseBytes)
            noConnections(server)
        }
    }

    @Test(timeout = 10_000) fun headersWithoutFirstBodyByteStillHitResponseStartTimeout() = runBlocking {
        WahlapStreamingServer { _, socket -> socket.scoreHeaders(); socket.waitForClientClose() }.use { server ->
            val meta = WahlapAttemptMeta()
            assertTrue(runCatching { fetch(server, meta) }.exceptionOrNull() is WahlapScoreDownloadException)
            assertEquals(DiagnosticDownloadFailure.RESPONSE_START_TIMEOUT, meta.downloadFailure)
            assertEquals(200, meta.httpStatus)
            assertEquals(0L, meta.responseBytes)
            noConnections(server)
        }
    }

    @Test(timeout = 15_000) fun stalledBodyStopsAfterThreeAttemptsAndDoesNotPersistPartialHtml() = runBlocking {
        val fragment = html.substringBefore("</html>").toByteArray()
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders(); socket.scoreChunk(fragment); socket.waitForClientClose()
        }.use { server ->
            val persistence = BoundaryPersistence()
            val logs = mutableListOf<WahlapAttemptLog>()
            val result = importOnce(server, shortProfile, persistence, logs)
            assertEquals(WahlapImportOutcome.FAILED, result.outcome)
            assertEquals(3, server.requests.size)
            assertTrue(logs.all { it.downloadFailure == DiagnosticDownloadFailure.READ_IDLE_TIMEOUT })
            assertEquals(listOf(true, true, false), logs.map { it.willRetry })
            assertEquals(0, persistence.writeCalls)
            noConnections(server)
        }
    }

    @Test(timeout = 15_000) fun briefPausesResetIdleWindowOnEachNonemptyRead() = runBlocking {
        val bytes = html.toByteArray()
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders()
            bytes.asList().chunked(500).forEach { part -> socket.scoreChunk(part.toByteArray()); Thread.sleep(120) }
            socket.finishChunks()
        }.use { server ->
            val meta = WahlapAttemptMeta()
            assertEquals(html, fetch(server, meta, shortProfile.copy(readIdleTimeoutMs = 500)))
            assertTrue(meta.progressChunks!! > 1)
            assertNull(meta.downloadFailure)
            assertEquals(1, server.requests.size)
        }
    }

    @Test(timeout = 15_000) fun declaredLengthTruncationCannotSucceedEvenWithClosingHtmlTag() = runBlocking {
        val bytes = html.toByteArray()
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders(bytes.size + 500L); socket.getOutputStream().write(bytes)
        }.use { server ->
            val logs = mutableListOf<WahlapAttemptLog>()
            val persistence = BoundaryPersistence()
            assertEquals(WahlapImportOutcome.FAILED, importOnce(server, shortProfile, persistence, logs).outcome)
            assertEquals(3, server.requests.size)
            assertTrue(logs.all { it.downloadFailure == DiagnosticDownloadFailure.BODY_INCOMPLETE })
            assertEquals(0, persistence.writeCalls)
        }
    }

    @Test(timeout = 10_000) fun missingChunkTerminatorDoesNotReturnPartialBody() = runBlocking {
        WahlapStreamingServer { _, socket -> socket.scoreHeaders(); socket.scoreChunk(html.toByteArray()) }.use { server ->
            assertNotNull(runCatching { fetch(server) }.exceptionOrNull())
        }
    }

    @Test(timeout = 10_000) fun excessiveContentLengthFailsBeforeReadingAndWithoutRetry() = runBlocking {
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders(MAX_SCORE_BODY_BYTES + 1L); socket.waitForClientClose()
        }.use { server ->
            val meta = WahlapAttemptMeta()
            val error = runCatching { fetch(server, meta) }.exceptionOrNull()!!
            assertFalse("${error.javaClass.simpleName}: ${meta.downloadFailure}", WahlapRetryClassifier.isRetryable(error))
            assertEquals(DiagnosticDownloadFailure.BODY_TOO_LARGE, meta.downloadFailure)
            assertEquals(0L, meta.responseBytes)
            noConnections(server)
        }
    }

    @Test(timeout = 10_000) fun unendingChunkedStreamIsCappedAndConnectionClosed() = runBlocking {
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders()
            val bytes = ByteArray(32 * 1024) { 'x'.code.toByte() }
            while (true) socket.scoreChunk(bytes)
        }.use { server ->
            val meta = WahlapAttemptMeta()
            val error = runCatching { fetch(server, meta) }.exceptionOrNull()!!
            assertFalse(WahlapRetryClassifier.isRetryable(error))
            assertEquals(DiagnosticDownloadFailure.BODY_TOO_LARGE, meta.downloadFailure)
            assertTrue(meta.responseBytes!! in (MAX_SCORE_BODY_BYTES + 1L)..(MAX_SCORE_BODY_BYTES + 32 * 1024L))
            noConnections(server)
        }
    }

    @Test(timeout = 10_000) fun cancellationClosesActiveReadAndDoesNotRetryOrPersist() = runBlocking {
        val sent = CountDownLatch(1)
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders(); socket.scoreChunk(html.toByteArray()); sent.countDown(); socket.waitForClientClose()
        }.use { server ->
            val persistence = BoundaryPersistence()
            val logs = Collections.synchronizedList(mutableListOf<WahlapAttemptLog>())
            var activeMeta: WahlapAttemptMeta? = null
            // Share the event-loop thread so the observed progress has no cross-thread data race.
            val job = launch { importOnce(server, productionProfile, persistence, logs, onMeta = { activeMeta = it }) }
            withTimeout(3_000) {
                while (sent.count != 0L || (activeMeta?.responseBytes ?: 0L) == 0L) delay(10)
            }
            assertTrue(activeMeta!!.progressChunks!! > 0)
            val elapsed = measureTimeMillis { job.cancelAndJoin(); noConnections(server) }
            assertTrue(elapsed < 3_000)
            assertTrue(job.isCancelled)
            assertEquals(1, server.requests.size)
            assertEquals(0, persistence.writeCalls)
            assertEquals(DiagnosticDownloadFailure.CANCELLED, logs.single().downloadFailure)
            assertFalse(logs.single().willRetry)
        }
    }

    @Test(timeout = 10_000) fun multibyteCharsetDecodesAfterAllChunksAreReceived() = runBlocking {
        val text = "<html><body>合成成绩页</body></html>"
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        WahlapStreamingServer { _, socket ->
            socket.scoreHeaders(charset = "UTF-16LE")
            bytes.forEach { socket.scoreChunk(byteArrayOf(it)) }
            socket.finishChunks()
        }.use { server -> assertEquals(text, fetch(server)) }
    }

    @Test fun otherCategoriesRetainTheirOriginalBudgetsAndPolicies() {
        assertEquals(30_000L, WahlapRequestCatalog.timeoutProfile(WahlapRequestCategory.LOGIN_HOME).requestTimeoutMs)
        assertEquals(45_000L, WahlapRequestCatalog.timeoutProfile(WahlapRequestCategory.SUPPLEMENTAL_PAGE).requestTimeoutMs)
        assertEquals(1, WahlapRequestCatalog.retryPolicy(WahlapRequestCategory.AUTH_CALLBACK).maxAttempts)
        assertEquals(3, WahlapRequestCatalog.retryPolicy(WahlapRequestCategory.LOGIN_HOME).maxAttempts)
        assertEquals(2, WahlapRequestCatalog.retryPolicy(WahlapRequestCategory.SUPPLEMENTAL_PAGE).maxAttempts)
        for (category in WahlapRequestCategory.entries.filter { it != WahlapRequestCategory.SCORE_PAGE }) {
            assertEquals(15_000L, WahlapRequestCatalog.timeoutProfile(category).connectTimeoutMs)
            assertNull(WahlapRequestCatalog.timeoutProfile(category).readIdleTimeoutMs)
        }
    }

    @Test(timeout = 10_000) fun callbackHomeAndSupplementalStillUseTotalRequestTimeouts() = runBlocking {
        for (category in listOf(WahlapRequestCategory.AUTH_CALLBACK, WahlapRequestCategory.LOGIN_HOME, WahlapRequestCategory.SUPPLEMENTAL_PAGE)) {
            WahlapStreamingServer { _, socket -> socket.waitForClientClose() }.use { server ->
                WahlapImportHttpClient().use { client ->
                    val meta = WahlapAttemptMeta()
                    val profile = WahlapRequestCatalog.timeoutProfile(category).copy(requestTimeoutMs = 500)
                    val error = runCatching { client.fetchPage(server.url(), category, profile, meta = meta) }.exceptionOrNull()
                    assertTrue(error is io.ktor.client.plugins.HttpRequestTimeoutException)
                    assertNull(meta.downloadFailure)
                    assertEquals(1, server.requests.size)
                }
                noConnections(server)
            }
        }
    }
}
