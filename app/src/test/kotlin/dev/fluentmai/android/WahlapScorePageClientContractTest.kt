package dev.fluentmai.android

import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import io.ktor.http.Url
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal interface ScoreClientFixture : AutoCloseable {
    suspend fun score(difficulty: Difficulty): String
    suspend fun supplemental(): WahlapSupplementalFetchResult
}

/** Both real HTTP clients must obey the same importer boundary, not just throw as a provider. */
abstract class WahlapScorePageClientContractTest {
    internal abstract fun client(
        server: WahlapLoopbackServer,
        sources: List<WahlapSupplementalPages.Page>,
        diagnostic: (String) -> Unit = {},
        mapper: (String) -> String = { server.url(Url(it).encodedPath) },
    ): ScoreClientFixture

    private val sources = (1..3).map { WahlapSupplementalPages.Page("fixture-$it", "https://maimai.wahlap.com/supplemental-$it") }
    private val scoreHtml get() = File("../fixtures/wahlap_valid_fixture.html").readText()
    private val supplementalHtml get() = File("../fixtures/wahlap_rating_target_supplemental_synthetic_fixture.html").readText()
    private val emptyHtml = """<html><body><form action="/maimai-mobile/record/musicSort/search/">
        <input name="diff" value="0"><input name="sort" value="1"></form>
        <div>没有符合条件的乐曲。</div></body></html>"""

    private fun server(failed: Set<Int>, score: String = scoreHtml, scoreStatus: Int = 200) =
        WahlapLoopbackServer { request ->
            val source = request.path.removePrefix("/supplemental-").toIntOrNull()
            if (source == null) WahlapLoopbackServer.Response(scoreStatus, score)
            else WahlapLoopbackServer.Response(if (source in failed) 503 else 200, supplementalHtml)
        }

    private suspend fun import(client: ScoreClientFixture, persistence: BoundaryPersistence,
        provider: WahlapSupplementalPageProvider = WahlapSupplementalPageProvider { client.supplemental() },
    ) = RealWahlapImportAdapter(difficulties = listOf(Difficulty.BASIC), sanitizeFailure = PrivacyRedactor()::redact)
        .importFetchedPages("fixture-loopback", WahlapScorePageProvider(client::score), persistence, provider)

    @Test fun allRequiredSupplementalSourcesSucceedAndProduceComplete() = runBlocking {
        server(emptySet()).use { server ->
            client(server, sources).use { client ->
                val persistence = BoundaryPersistence()
                val result = import(client, persistence)
                assertEquals(WahlapImportOutcome.COMPLETE, result.outcome)
                assertTrue(result.supplementalFailures.isEmpty())
                assertEquals(3, result.fetchedSupplementalPageCount)
                assertEquals(15, result.parsedSupplementalRecordCount)
                assertEquals(8, persistence.scores.size)
                assertEquals(1, persistence.batches.size)
            }
        }
    }

    @Test fun oneFailedPageRemainsVisibleWhileLaterSuccessfulPagesPersistAsPartial() = runBlocking {
        server(setOf(2)).use { server ->
            client(server, sources).use { client ->
                val persistence = BoundaryPersistence()
                val result = import(client, persistence)
                assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
                assertEquals(listOf("fixture-2"), result.supplementalFailures.map { it.label })
                assertEquals(2, result.fetchedSupplementalPageCount)
                assertEquals(10, result.parsedSupplementalRecordCount)
                assertEquals(8, persistence.scores.size)
                assertTrue(persistence.scores.values.any { it.title == "SYNTHETIC SONG ALPHA" })
                assertEquals(2, server.requestsAt("/supplemental-2").size)
                assertEquals(1, server.requestsAt("/supplemental-3").size)
                val state = ImportTaskState(phase = ImportTaskPhase.Finished, result = result)
                assertTrue(state.succeeded)
                assertFalse(state.complete)
                assertEquals("成绩已导入，部分数据未完整同步", state.completionTitle)
            }
        }
    }

    @Test fun multipleFailedPagesAreAllVisibleAndUsefulDataPersists() = runBlocking {
        server(setOf(1, 3)).use { server ->
            client(server, sources).use { client ->
                val persistence = BoundaryPersistence()
                val result = import(client, persistence)
                assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
                assertEquals(listOf("fixture-1", "fixture-3"), result.supplementalFailures.map { it.label })
                assertEquals(1, result.fetchedSupplementalPageCount)
                assertEquals(5, result.parsedSupplementalRecordCount)
                assertEquals(8, persistence.scores.size)
            }
        }
    }

    @Test fun everySupplementalPageCanFailWhileUsefulScoreDataIsPartial() = runBlocking {
        server(setOf(1, 2, 3)).use { server ->
            client(server, sources).use { client ->
                val persistence = BoundaryPersistence()
                val result = import(client, persistence)
                assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
                assertEquals(3, result.supplementalFailures.size)
                assertEquals(3, persistence.scores.size)
                assertEquals(0, result.fetchedSupplementalPageCount)
            }
        }
    }

    @Test fun failedSupplementalSourcesWithoutUsefulScoreDataAreFailedWithNoWrites() = runBlocking {
        server(setOf(1, 2, 3), score = emptyHtml).use { server ->
            client(server, sources).use { client ->
                val persistence = BoundaryPersistence()
                val result = import(client, persistence)
                assertEquals(WahlapImportOutcome.FAILED, result.outcome)
                assertEquals(3, result.supplementalFailures.size)
                assertEquals(0, persistence.writeCalls)
                assertTrue(persistence.batches.isEmpty())
                assertEquals("", result.importResult.batchId)
                assertFalse(ImportTaskState(result = result).succeeded)
            }
        }
    }

    @Test fun usefulSupplementalDataAloneIsPartialAndUiRecognizesSuccess() = runBlocking {
        server(emptySet(), scoreStatus = 503).use { server ->
            client(server, sources).use { client ->
                val persistence = BoundaryPersistence()
                val result = import(client, persistence)
                assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
                assertEquals(0, result.fetchedDifficultyCount)
                assertEquals(5, persistence.scores.size)
                assertTrue(ImportTaskState(result = result).succeeded)
                assertFalse(ImportTaskState(result = result).complete)
                assertEquals(3, server.requestsAt("/maimai-mobile/record/musicSort/search/").size)
            }
        }
    }

    @Test fun wholeProviderFailureIsPartialOrFailedDependingOnUsefulScoreData() = runBlocking {
        for (useful in listOf(true, false)) {
            server(emptySet(), score = if (useful) scoreHtml else emptyHtml).use { server ->
                client(server, sources).use { client ->
                    val persistence = BoundaryPersistence()
                    val result = import(client, persistence, WahlapSupplementalPageProvider { throw IOException("fixture failure") })
                    assertEquals(if (useful) WahlapImportOutcome.PARTIAL else WahlapImportOutcome.FAILED, result.outcome)
                    assertEquals("supplemental", result.supplementalFailures.single().label)
                    if (!useful) assertEquals(0, persistence.writeCalls)
                }
            }
        }
    }

    @Test fun cancellationRaisedInsideRequestEscapesLoopWithoutAnotherSource() = runBlocking {
        WahlapLoopbackServer().use { server ->
            val cancelled = CancellationException("fixture cancellation")
            var requests = 0
            client(server, sources, mapper = { requests++; throw cancelled }).use { client ->
                try {
                    client.supplemental()
                    fail("cancellation must propagate")
                } catch (error: CancellationException) { assertSame(cancelled, error) }
                assertEquals(1, requests)
                assertTrue(server.requests.isEmpty())
            }
        }
    }

    @Test fun cancellingSuspendedSupplementalIoStopsAdapterWithoutWritesOrLaterRequests() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        WahlapLoopbackServer { request ->
            if (request.path == "/supplemental-1") {
                entered.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
                WahlapLoopbackServer.Response(body = supplementalHtml)
            } else WahlapLoopbackServer.Response(body = scoreHtml)
        }.use { server ->
            client(server, sources).use { client ->
                val persistence = BoundaryPersistence()
                val job = async { import(client, persistence) }
                try {
                    withTimeout(5_000) { entered.await() }
                    job.cancel(CancellationException("fixture cancellation"))
                    try { job.await(); fail("cancellation must propagate through adapter") }
                    catch (_: CancellationException) { }
                    assertEquals(1, server.requestsAt("/supplemental-1").size)
                    assertTrue(server.requestsAt("/supplemental-2").isEmpty())
                    assertTrue(server.requestsAt("/supplemental-3").isEmpty())
                    assertEquals(0, persistence.writeCalls)
                } finally { release.countDown(); job.cancelAndJoin() }
            }
        }
    }

    @Test fun supplementalFailuresContainSanitizedInformation() = runBlocking {
        WahlapLoopbackServer().use { server ->
            client(server, sources, mapper = { throw IOException("token=fixture-private https://fixture.invalid/?code=fixture-private") }).use { client ->
                val result = client.supplemental()
                assertEquals(3, result.failures.size)
                assertTrue(result.pages.isEmpty())
                for (failure in result.failures) {
                    assertFalse(failure.message.contains("fixture-private"))
                    assertFalse(failure.message.contains("https://"))
                }
            }
        }
    }

    @Test fun productionRequiredSourceFailureCannotBecomeCompleteAndProgressReflectsIt() = runBlocking {
        val progress = mutableListOf<ImportProgress>()
        val pageReporter = ImportPageReporter(progress::add)
        WahlapLoopbackServer { request ->
            WahlapLoopbackServer.Response(if (request.path.contains("ratingTargetMusic")) 503 else 200, scoreHtml)
        }.use { server ->
            client(server, WahlapSupplementalPages.pages).use { client ->
                val result = import(client, BoundaryPersistence(), WahlapSupplementalPageProvider {
                    pageReporter.page(ImportStage.Supplemental, 0, 1, "fixture",
                        complete = { it.failures.isEmpty() && it.pages.size == 1 }) { client.supplemental() }
                })
                assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
                assertEquals("rating-target-music", result.supplementalFailures.single().label)
                assertEquals(ImportPageState.Failed, progress.last().pageState)
                assertEquals(1, progress.last().failedPages)
            }
        }
    }

    @Test fun responseDiagnosticCountsDecodedCharactersAccurately() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val html = "<html><body>舞萌 fixture</body></html>"
        assertTrue(html.toByteArray(Charsets.UTF_8).size > html.length)
        WahlapLoopbackServer { WahlapLoopbackServer.Response(body = html) }.use { server ->
            client(server, sources.take(1), diagnostics::add).use { client ->
                assertTrue(client.supplemental().failures.isEmpty())
                assertTrue(diagnostics.any { it.contains("chars=${html.length}") })
                assertFalse(diagnostics.any { it.contains("bytes=") })
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WahlapHttpScorePageClientTest : WahlapScorePageClientContractTest() {
    internal override fun client(server: WahlapLoopbackServer, sources: List<WahlapSupplementalPages.Page>,
        diagnostic: (String) -> Unit, mapper: (String) -> String): ScoreClientFixture {
        val client = WahlapHttpScorePageClient(PrivacyRedactor(), onDiagnostic = diagnostic,
            attempt = WahlapOAuthAttempt(), supplementalPages = sources, mapRequestUrl = mapper,
            fetcher = WahlapResilientFetcher(delayFor = {}))
        return object : ScoreClientFixture {
            override suspend fun score(difficulty: Difficulty) = client.fetchScorePage(difficulty)
            override suspend fun supplemental() = client.fetchSupplementalScorePages()
            override fun close() = client.close()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WahlapManualCookieScorePageClientTest : WahlapScorePageClientContractTest() {
    internal override fun client(server: WahlapLoopbackServer, sources: List<WahlapSupplementalPages.Page>,
        diagnostic: (String) -> Unit, mapper: (String) -> String): ScoreClientFixture {
        val client = WahlapManualCookieScorePageClient(
            WahlapCookieImportCredentials.parse("_t=fixture-token; userId=fixture-user"), PrivacyRedactor(),
            onDiagnostic = diagnostic, supplementalPages = sources, mapRequestUrl = mapper,
            fetcher = WahlapResilientFetcher(delayFor = {}))
        return object : ScoreClientFixture {
            override suspend fun score(difficulty: Difficulty) = client.fetchScorePage(difficulty)
            override suspend fun supplemental() = client.fetchSupplementalScorePages()
            override fun close() = client.close()
        }
    }
}

internal class BoundaryPersistence : ImportPersistence {
    val scores = linkedMapOf<String, ScoreRecord>()
    val batches = mutableListOf<ImportBatch>()
    var writeCalls = 0
    override suspend fun findExistingScoreIds(scoreIds: Set<String>) = scoreIds.filter(scores::containsKey).toSet()
    override suspend fun insertScoreRecords(records: List<ScoreRecord>) { writeCalls++; records.forEach { scores[it.id] = it } }
    override suspend fun insertQuarantineRecords(records: List<QuarantineRecord>) { writeCalls++ }
    override suspend fun insertImportBatch(batch: ImportBatch) { writeCalls++; batches += batch }
}
