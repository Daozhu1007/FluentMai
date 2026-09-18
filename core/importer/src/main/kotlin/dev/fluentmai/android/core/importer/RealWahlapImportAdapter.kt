package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.ImportResult

fun interface WahlapScorePageProvider {
    suspend fun fetchScorePage(difficulty: Difficulty): String
}

fun interface WahlapSupplementalPageProvider {
    suspend fun fetchSupplementalPages(): List<WahlapSupplementalPage>
}

data class WahlapSupplementalPage(
    val label: String,
    val html: String,
)

data class WahlapDifficultyFailure(
    val difficulty: Difficulty,
    val message: String,
)

data class WahlapSupplementalFailure(
    val label: String,
    val message: String,
)

data class RealWahlapImportResult(
    val importResult: ImportResult,
    val parsedRecordCount: Int,
    val fetchedDifficultyCount: Int,
    val failedDifficultyCount: Int,
    val failures: List<WahlapDifficultyFailure>,
    val fetchedSupplementalPageCount: Int = 0,
    val parsedSupplementalRecordCount: Int = 0,
    val supplementalFailures: List<WahlapSupplementalFailure> = emptyList(),
    val fetchedPlayRecordCount: Int = 0,
    val failedPlayPageCount: Int = 0,
    val activityCaptureAttempted: Boolean = false,
    val fetchedPlayCountCharts: Int = 0,
    val activityWarnings: List<String> = emptyList(),
    val diagnosticDetails: String = "",
) {
    val isCompleteSuccess: Boolean = failedDifficultyCount == 0 && supplementalFailures.isEmpty()
}

class RealWahlapImportAdapter(
    private val parser: WahlapFixtureParser = WahlapFixtureParser(),
    private val pipeline: FakeImportPipeline = FakeImportPipeline(),
    private val difficulties: List<Difficulty> = Difficulty.entries,
    private val sanitizeFailure: (String) -> String = { it },
) {
    suspend fun importFetchedPages(
        source: String,
        pageProvider: WahlapScorePageProvider,
        persistence: ImportPersistence,
        supplementalPageProvider: WahlapSupplementalPageProvider? = null,
    ): RealWahlapImportResult {
        val parsedRecords = mutableListOf<ParsedScoreRecord>()
        val failures = mutableListOf<WahlapDifficultyFailure>()
        val supplementalFailures = mutableListOf<WahlapSupplementalFailure>()
        var fetchedDifficultyCount = 0
        var fetchedSupplementalPageCount = 0
        var parsedSupplementalRecordCount = 0

        difficulties.forEach { difficulty ->
            val html = runCatching {
                pageProvider.fetchScorePage(difficulty)
            }.getOrElse { error ->
                failures += difficultyFailure(difficulty, error)
                return@forEach
            }

            fetchedDifficultyCount += 1
            val parsed = runCatching {
                parser.parse(html, difficulty)
            }.getOrElse { error ->
                failures += difficultyFailure(difficulty, error)
                return@forEach
            }
            parsedRecords += parsed
        }

        supplementalPageProvider?.let { provider ->
            val supplementalPages = runCatching { provider.fetchSupplementalPages() }
                .getOrElse { error ->
                    supplementalFailures += WahlapSupplementalFailure(
                        label = "supplemental",
                        message = sanitizeFailure(error.message ?: error::class.java.simpleName),
                    )
                    emptyList()
                }
            fetchedSupplementalPageCount = supplementalPages.size
            supplementalPages.forEach { page ->
                val parsed = runCatching {
                    parser.parseMixedDifficultyPage(page.html)
                }.getOrElse { error ->
                    supplementalFailures += WahlapSupplementalFailure(
                        label = page.label,
                        message = sanitizeFailure(error.message ?: error::class.java.simpleName),
                    )
                    return@forEach
                }
                parsedSupplementalRecordCount += parsed.size
                parsedRecords += parsed
            }
        }

        if (failures.isNotEmpty()) {
            return RealWahlapImportResult(
                importResult = ImportResult(
                    batchId = "",
                    inserted = 0,
                    updated = 0,
                    skippedDuplicate = 0,
                    quarantined = 0,
                    rejected = failures.size,
                ),
                parsedRecordCount = parsedRecords.size,
                fetchedDifficultyCount = fetchedDifficultyCount,
                failedDifficultyCount = failures.size,
                failures = failures,
                fetchedSupplementalPageCount = fetchedSupplementalPageCount,
                parsedSupplementalRecordCount = parsedSupplementalRecordCount,
                supplementalFailures = supplementalFailures,
            )
        }

        val importResult = pipeline.importParsedRecords(
            source = source,
            parsed = parsedRecords,
            persistence = persistence,
        )

        return RealWahlapImportResult(
            importResult = importResult,
            parsedRecordCount = parsedRecords.size,
            fetchedDifficultyCount = fetchedDifficultyCount,
            failedDifficultyCount = failures.size,
            failures = failures,
            fetchedSupplementalPageCount = fetchedSupplementalPageCount,
            parsedSupplementalRecordCount = parsedSupplementalRecordCount,
            supplementalFailures = supplementalFailures,
        )
    }

    private fun difficultyFailure(difficulty: Difficulty, error: Throwable): WahlapDifficultyFailure =
        WahlapDifficultyFailure(
            difficulty = difficulty,
            message = sanitizeFailure(error.message ?: error::class.java.simpleName),
        )
}

object WahlapScorePageUrls {
    fun scorePageUrl(
        difficulty: Difficulty,
        incremental: Boolean = true,
    ): String {
        val baseUrl = if (incremental) {
            "https://maimai.wahlap.com/maimai-mobile/record/musicSort/search/" +
                "?search=A&sort=1&playCheck=on&diff="
        } else {
            "https://maimai.wahlap.com/maimai-mobile/record/musicGenre/search/?genre=99&diff="
        }
        return baseUrl + difficulty.levelIndex
    }
}
