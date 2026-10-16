package dev.fluentmai.android

import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.http.Url
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises production score validation, retry wiring and importer boundaries for both clients. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WahlapProgressClientTest {
    private val html get() = File("../fixtures/wahlap_valid_fixture.html").readText()
    private fun complete(socket: Socket, body: String = html) {
        val bytes = body.toByteArray()
        socket.scoreHeaders(bytes.size.toLong())
        socket.getOutputStream().write(bytes)
    }
    private fun client(manual: Boolean, server: WahlapStreamingServer,
        attempt: (WahlapRequestCategory, DiagnosticRequestLabel, WahlapAttemptLog) -> Unit = { _, _, _ -> },
    ): ScoreClientFixture {
        val fetcher = WahlapResilientFetcher(delayFor = {}, timeoutProfileFor = { category ->
            WahlapRequestCatalog.timeoutProfile(category).let {
                if (category == WahlapRequestCategory.SCORE_PAGE) it.copy(responseStartTimeoutMs = 750, readIdleTimeoutMs = 750) else it
            }
        })
        val mapper: (String) -> String = { server.url("/${Url(it).parameters["diff"] ?: "other"}") }
        if (manual) {
            val client = WahlapManualCookieScorePageClient(
                WahlapCookieImportCredentials.parse("_t=synthetic; userId=synthetic"), PrivacyRedactor(),
                fetcher = fetcher, supplementalPages = emptyList(), mapRequestUrl = mapper, onRequestAttempt = attempt)
            return object : ScoreClientFixture {
                override suspend fun score(difficulty: Difficulty) = client.fetchScorePage(difficulty)
                override suspend fun supplemental() = client.fetchSupplementalScorePages()
                override fun close() = client.close()
            }
        }
        val client = WahlapHttpScorePageClient(PrivacyRedactor(), attempt = WahlapOAuthAttempt(),
            fetcher = fetcher, supplementalPages = emptyList(), mapRequestUrl = mapper, onRequestAttempt = attempt)
        return object : ScoreClientFixture {
            override suspend fun score(difficulty: Difficulty) = client.fetchScorePage(difficulty)
            override suspend fun supplemental() = client.fetchSupplementalScorePages()
            override fun close() = client.close()
        }
    }

    @Test fun idleFailureThenRecoveryKeepsLoadingAndPersistsOnceForBothClients() = runBlocking {
        for (manual in listOf(false, true)) {
            val count = AtomicInteger()
            WahlapStreamingServer { _, socket ->
                if (count.incrementAndGet() == 1) {
                    socket.scoreHeaders(); socket.scoreChunk(html.toByteArray()); socket.waitForClientClose()
                } else complete(socket)
            }.use { server ->
                val snapshots = mutableListOf<ImportProgress>()
                val reporter = ImportPageReporter(snapshots::add)
                val logs = mutableListOf<WahlapAttemptLog>()
                client(manual, server) { category, _, log -> logs += log; reporter.requestAttempt(category, log) }.use { client ->
                    val persistence = BoundaryPersistence()
                    val result = RealWahlapImportAdapter(difficulties = listOf(Difficulty.EXPERT)).importFetchedPages(
                        "synthetic-recovery", WahlapScorePageProvider { difficulty ->
                            reporter.page(ImportStage.Scores, 0, 1, "EXPERT 成绩页") { client.score(difficulty) }
                        }, persistence)
                    assertEquals(WahlapImportOutcome.COMPLETE, result.outcome)
                    assertEquals(2, server.requests.size)
                    assertEquals(3, persistence.scores.size)
                    assertEquals(1, persistence.batches.size)
                    assertEquals(DiagnosticDownloadFailure.READ_IDLE_TIMEOUT, logs.first().downloadFailure)
                    val retry = snapshots.single { it.detail.contains("正在重试") }
                    assertEquals(ImportPageState.Loading, retry.pageState)
                    assertEquals(0, retry.processedPages)
                    assertEquals("EXPERT 成绩页", retry.pageName)
                    assertEquals(ImportPageState.Complete, snapshots.last().pageState)
                }
            }
        }
    }

    @Test fun exhaustedExpertKeepsOtherDifficultyAndPreviouslySavedScoresForBothClients() = runBlocking {
        for (manual in listOf(false, true)) {
            WahlapStreamingServer { path, socket ->
                if (path == "/${Difficulty.EXPERT.levelIndex}") {
                    socket.scoreHeaders(); socket.scoreChunk(html.toByteArray()); socket.waitForClientClose()
                } else complete(socket)
            }.use { server ->
                client(manual, server).use { client ->
                    val persistence = BoundaryPersistence()
                    RealWahlapImportAdapter(difficulties = listOf(Difficulty.EXPERT)).importFetchedPages(
                        "synthetic-old", WahlapScorePageProvider { html }, persistence)
                    val oldIds = persistence.scores.keys.toSet()
                    val result = RealWahlapImportAdapter(difficulties = listOf(Difficulty.BASIC, Difficulty.EXPERT))
                        .importFetchedPages("synthetic-partial", WahlapScorePageProvider(client::score), persistence)
                    assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
                    assertEquals(listOf(Difficulty.EXPERT), result.failures.map { it.difficulty })
                    assertEquals(4, server.requests.size)
                    assertTrue(persistence.scores.keys.containsAll(oldIds))
                    assertEquals(6, persistence.scores.size)
                    assertEquals(2, persistence.batches.size)
                }
            }
        }
    }

    @Test fun transportCompleteButTruncatedHtmlRetriesAndNeverPersistsForBothClients() = runBlocking {
        for (manual in listOf(false, true)) {
            WahlapStreamingServer { _, socket -> complete(socket, html.substringBefore("</html>")) }.use { server ->
                client(manual, server).use { client ->
                    val persistence = BoundaryPersistence()
                    val result = RealWahlapImportAdapter(difficulties = listOf(Difficulty.EXPERT)).importFetchedPages(
                        "synthetic-incomplete", WahlapScorePageProvider(client::score), persistence)
                    assertEquals(WahlapImportOutcome.FAILED, result.outcome)
                    assertEquals(3, server.requests.size)
                    assertEquals(0, persistence.writeCalls)
                }
            }
        }
    }
}
