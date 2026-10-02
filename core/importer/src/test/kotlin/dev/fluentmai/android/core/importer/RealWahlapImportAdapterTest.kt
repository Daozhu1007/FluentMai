package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.ImportBatch
import dev.fluentmai.android.core.model.QuarantineRecord
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongType
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealWahlapImportAdapterTest {
    @Test
    fun importsAllFiveDifficultyPagesThroughExistingPipeline() = runTest {
        val calls = mutableListOf<Difficulty>()
        val persistence = RealImportMemoryPersistence()
        val adapter = adapter()

        val result = adapter.importFetchedPages(
            source = "wahlap-real-test",
            pageProvider = WahlapScorePageProvider { difficulty ->
                calls += difficulty
                resourceText("wahlap_valid_fixture.html")
            },
            persistence = persistence,
        )

        assertEquals(Difficulty.entries, calls)
        assertEquals(5, result.fetchedDifficultyCount)
        assertEquals(0, result.failedDifficultyCount)
        assertEquals(15, result.parsedRecordCount)
        assertEquals(15, result.importResult.inserted)
        assertEquals(15, persistence.scores.size)
        assertEquals((0..4).toSet(), persistence.scores.values.map { it.levelIndex }.toSet())
    }

    @Test
    fun unplayedBasicAndAdvancedDoNotBlockOtherDifficulties() = runTest {
        val persistence = RealImportMemoryPersistence()
        val emptyPage = """
            <html><body><form action="/maimai-mobile/record/musicSort/search/">
            <input name="diff" value="0"><input name="sort" value="1">
            </form><div>没有符合条件的乐曲。</div></body></html>
        """.trimIndent()
        val result = adapter().importFetchedPages(
            source = "unplayed-difficulties-test",
            pageProvider = WahlapScorePageProvider { difficulty ->
                val html = if (difficulty == Difficulty.BASIC || difficulty == Difficulty.ADVANCED) {
                    emptyPage
                } else resourceText("wahlap_valid_fixture.html")
                check(WahlapScorePageValidation.isScorePage(html))
                html
            },
            persistence = persistence,
        )
        assertEquals(0, result.failedDifficultyCount)
        assertEquals(9, result.importResult.inserted)
        assertEquals(setOf(2, 3, 4), persistence.scores.values.map { it.levelIndex }.toSet())
    }

    @Test
    fun oneDifficultyFailurePersistsTheSuccessfulDifficulties() = runTest {
        val persistence = RealImportMemoryPersistence()
        val adapter = adapter()

        val result = adapter.importFetchedPages(
            source = "wahlap-partial-test",
            pageProvider = WahlapScorePageProvider { difficulty ->
                if (difficulty == Difficulty.ADVANCED) {
                    throw IllegalStateException("HTTP 503")
                }
                resourceText("wahlap_valid_fixture.html")
            },
            persistence = persistence,
        )

        assertEquals(4, result.fetchedDifficultyCount)
        assertEquals(1, result.failedDifficultyCount)
        assertEquals(Difficulty.ADVANCED, result.failures.single().difficulty)
        assertEquals(12, result.importResult.inserted)
        assertEquals(12, result.importResult.updated + result.importResult.inserted + result.importResult.skippedDuplicate + result.importResult.quarantined)
        assertEquals(12, persistence.scores.size)
        assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
        assertFalse(result.isCompleteSuccess)
    }

    @Test
    fun failedDifficultyKeepsItsPreviousLocalRecords() = runTest {
        val persistence = RealImportMemoryPersistence()
        persistence.scores["stale-master"] = ScoreRecord(
            id = "stale-master",
            title = "Stale Master Record",
            difficulty = Difficulty.MASTER,
            level = "13",
            levelIndex = 3,
            achievement = 97.5,
            dxScore = 2200,
            fc = null,
            fs = null,
            sourceBatchId = "old-batch",
            importedAt = 1L,
        )
        val adapter = adapter()

        val result = adapter.importFetchedPages(
            source = "wahlap-partial-keeps-old-test",
            pageProvider = WahlapScorePageProvider { difficulty ->
                if (difficulty == Difficulty.MASTER) {
                    throw IllegalStateException("HTTP 504")
                }
                resourceText("wahlap_valid_fixture.html")
            },
            persistence = persistence,
        )

        assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
        assertEquals(Difficulty.MASTER, result.failures.single().difficulty)
        // The four fetched difficulties were persisted on top of the previous data…
        assertEquals(13, persistence.scores.size)
        // …and the failed difficulty still serves its previous local records.
        assertTrue(persistence.scores.containsKey("stale-master"))
        assertEquals("old-batch", persistence.scores.getValue("stale-master").sourceBatchId)
    }

    @Test
    fun allDifficultiesFailedProducesFailedOutcomeWithoutDestructiveWrites() = runTest {
        val persistence = RealImportMemoryPersistence()
        persistence.scores["precious"] = ScoreRecord(
            id = "precious",
            title = "Pre-existing Record",
            difficulty = Difficulty.BASIC,
            level = "4",
            levelIndex = 0,
            achievement = 90.0,
            dxScore = 1000,
            fc = null,
            fs = null,
            sourceBatchId = "old-batch",
            importedAt = 1L,
        )
        val adapter = adapter()

        val result = adapter.importFetchedPages(
            source = "wahlap-all-failed-test",
            pageProvider = WahlapScorePageProvider { difficulty ->
                throw IllegalStateException("HTTP 503 on ${difficulty.name}")
            },
            persistence = persistence,
        )

        assertEquals(WahlapImportOutcome.FAILED, result.outcome)
        assertEquals(0, result.importResult.inserted)
        assertEquals(5, result.importResult.rejected)
        assertEquals("", result.importResult.batchId)
        // Nothing was persisted for the failed run, and nothing pre-existing was destroyed.
        assertTrue(persistence.batches.isEmpty())
        assertEquals(1, persistence.scores.size)
        assertTrue(persistence.scores.containsKey("precious"))
    }

    @Test
    fun legitimateEmptyAccountImportsAsComplete() = runTest {
        val persistence = RealImportMemoryPersistence()
        val emptyPage = """
            <html><body><form action="/maimai-mobile/record/musicSort/search/">
            <input name="diff" value="0"><input name="sort" value="1">
            </form><div>没有符合条件的乐曲。</div></body></html>
        """.trimIndent()

        val result = adapter().importFetchedPages(
            source = "wahlap-empty-account-test",
            pageProvider = WahlapScorePageProvider { emptyPage },
            persistence = persistence,
        )

        assertEquals(5, result.fetchedDifficultyCount)
        assertEquals(0, result.failedDifficultyCount)
        assertEquals(0, result.parsedRecordCount)
        assertEquals(WahlapImportOutcome.COMPLETE, result.outcome)
        assertTrue(result.isCompleteSuccess)
        assertEquals(1, persistence.batches.size)
        assertEquals(0, persistence.scores.size)
    }

    @Test
    fun supplementalFailureAloneYieldsPartialWithPersistedDifficulties() = runTest {
        val persistence = RealImportMemoryPersistence()
        val adapter = adapter(difficulties = listOf(Difficulty.BASIC))

        val result = adapter.importFetchedPages(
            source = "wahlap-supplemental-failed-test",
            pageProvider = WahlapScorePageProvider { resourceText("wahlap_valid_fixture.html") },
            supplementalPageProvider = WahlapSupplementalPageProvider {
                throw IllegalStateException("HTTP 502")
            },
            persistence = persistence,
        )

        assertEquals(3, result.importResult.inserted)
        assertEquals(1, result.supplementalFailures.size)
        assertEquals(WahlapImportOutcome.PARTIAL, result.outcome)
        assertEquals(3, persistence.scores.size)
    }

    @Test
    fun duplicateRealImportSimulationUpdatesExistingScores() = runTest {
        val persistence = RealImportMemoryPersistence()
        val adapter = adapter(difficulties = listOf(Difficulty.BASIC))
        val provider = WahlapScorePageProvider { resourceText("wahlap_valid_fixture.html") }

        val first = adapter.importFetchedPages("wahlap-first", provider, persistence)
        val second = adapter.importFetchedPages("wahlap-second", provider, persistence)

        assertEquals(3, first.importResult.inserted)
        assertEquals(0, first.importResult.skippedDuplicate)
        assertEquals(0, second.importResult.inserted)
        assertEquals(3, second.importResult.updated)
        assertEquals(0, second.importResult.skippedDuplicate)
        assertEquals(3, persistence.scores.size)
    }

    @Test
    fun completeRealImportPreservesStaleLocalScores() = runTest {
        val persistence = RealImportMemoryPersistence()
        val adapter = adapter(difficulties = listOf(Difficulty.BASIC))
        persistence.scores["stale"] = ScoreRecord(
            id = "stale",
            title = "Stale Local Score",
            difficulty = Difficulty.MASTER,
            level = "14",
            levelIndex = 3,
            achievement = 99.0,
            dxScore = 3000,
            fc = null,
            fs = null,
            sourceBatchId = "old-batch",
            importedAt = 1L,
        )

        adapter.importFetchedPages(
            source = "wahlap-replace-test",
            pageProvider = WahlapScorePageProvider { resourceText("wahlap_valid_fixture.html") },
            persistence = persistence,
        )

        assertTrue(persistence.scores.containsKey("stale"))
        assertEquals(4, persistence.scores.size)
        assertEquals("old-batch", persistence.scores.getValue("stale").sourceBatchId)
    }

    @Test
    fun supplementalRatingTargetPagesAreImportedWithDifficultyPages() = runTest {
        val persistence = RealImportMemoryPersistence()
        val adapter = adapter(difficulties = listOf(Difficulty.BASIC))

        val result = adapter.importFetchedPages(
            source = "wahlap-supplemental-test",
            pageProvider = WahlapScorePageProvider { resourceText("wahlap_valid_fixture.html") },
            supplementalPageProvider = WahlapSupplementalPageProvider {
                listOf(
                    WahlapSupplementalPage(
                        label = "rating-target-music",
                        html = resourceText("wahlap_rating_target_supplemental_synthetic_fixture.html"),
                    ),
                )
            },
            persistence = persistence,
        )

        assertEquals(1, result.fetchedSupplementalPageCount)
        assertEquals(5, result.parsedSupplementalRecordCount)
        assertEquals(8, result.parsedRecordCount)
        assertEquals(8, result.importResult.inserted)
        assertEquals(0, result.importResult.quarantined)
        assertImportedScore("SYNTHETIC SONG ALPHA", Difficulty.EXPERT, SongType.DX, "12+", 100.6000, persistence)
        assertImportedScore("SYNTHETIC SONG BETA", Difficulty.MASTER, SongType.STANDARD, "13", 100.7500, persistence)
        assertImportedScore("SYNTHETIC SONG GAMMA", Difficulty.BASIC, SongType.DX, "4", 100.5043, persistence)
        assertImportedScore("SYNTHETIC SONG EPSILON", Difficulty.MASTER, SongType.DX, "13+", 100.9000, persistence)
    }

    @Test
    fun blankTitleExpertAndMasterCardsEnterQuarantine() = runTest {
        val persistence = RealImportMemoryPersistence()
        val adapter = adapter(difficulties = listOf(Difficulty.EXPERT, Difficulty.MASTER))

        val result = adapter.importFetchedPages(
            source = "wahlap-blank-title-test",
            pageProvider = WahlapScorePageProvider { resourceText("wahlap_blank_title_with_signals.html") },
            persistence = persistence,
        )

        assertEquals(0, result.importResult.inserted)
        assertEquals(2, result.importResult.quarantined)
        assertEquals(0, persistence.scores.size)
        assertEquals(2, persistence.quarantineRecords.size)
        assertEquals(
            setOf(Difficulty.EXPERT, Difficulty.MASTER),
            persistence.quarantineRecords.map { it.difficulty }.toSet(),
        )
        assertTrue(persistence.quarantineRecords.all { it.reason.contains("blank_title") })
    }

    @Test
    fun failureMessagesAreSanitizedBeforeTheyReachResultState() = runTest {
        val persistence = RealImportMemoryPersistence()
        val redactor = PrivacyRedactor()
        val adapter = adapter(
            difficulties = listOf(Difficulty.BASIC),
            sanitizeFailure = redactor::redact,
        )

        val result = adapter.importFetchedPages(
            source = "wahlap-redaction-test",
            pageProvider = WahlapScorePageProvider {
                throw IllegalStateException(
                    """
                    Cookie: secret-cookie
                    https://maimai.wahlap.com/maimai-mobile/home/?token=secret-token
                    <html><body>raw private score page</body></html>
                    """.trimIndent(),
                )
            },
            persistence = persistence,
        )

        val message = result.failures.single().message
        assertFalse(message.contains("secret-cookie"))
        assertFalse(message.contains("secret-token"))
        assertFalse(message.contains("raw private score page"))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("<html", ignoreCase = true))
        assertTrue(message.contains("[REDACTED_SECRET]"))
    }

    private fun adapter(
        difficulties: List<Difficulty> = Difficulty.entries,
        sanitizeFailure: (String) -> String = { it },
    ): RealWahlapImportAdapter {
        var nextBatch = 0
        return RealWahlapImportAdapter(
            pipeline = FakeImportPipeline(
                clock = { 1234L },
                batchIdFactory = {
                    nextBatch += 1
                    "real-batch-$nextBatch"
                },
            ),
            difficulties = difficulties,
            sanitizeFailure = sanitizeFailure,
        )
    }

    private fun resourceText(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)).readText()

    private fun assertImportedScore(
        title: String,
        difficulty: Difficulty,
        songType: SongType,
        level: String,
        achievement: Double,
        persistence: RealImportMemoryPersistence,
    ) {
        val score = persistence.scores.values.single { it.title == title }
        assertEquals(difficulty, score.difficulty)
        assertEquals(difficulty.levelIndex, score.levelIndex)
        assertEquals(songType, score.songType)
        assertEquals(level, score.level)
        assertEquals(achievement, score.achievement, 0.0001)
    }
}

private class RealImportMemoryPersistence : ImportPersistence {
    val scores = linkedMapOf<String, ScoreRecord>()
    val quarantineRecords = mutableListOf<QuarantineRecord>()
    val batches = mutableListOf<ImportBatch>()

    override suspend fun findExistingScoreIds(scoreIds: Set<String>): Set<String> =
        scoreIds.filter(scores::containsKey).toSet()

    override suspend fun insertScoreRecords(records: List<ScoreRecord>) {
        records.forEach { scores[it.id] = it }
    }

    override suspend fun insertQuarantineRecords(records: List<QuarantineRecord>) {
        quarantineRecords += records
    }

    override suspend fun insertImportBatch(batch: ImportBatch) {
        batches += batch
    }
}
