package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.ImportResult
import dev.fluentmai.android.core.model.DiagnosticStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

fun interface WahlapScorePageProvider {
    suspend fun fetchScorePage(difficulty: Difficulty): String
}

fun interface WahlapSupplementalPageProvider {
    suspend fun fetchSupplementalPages(): WahlapSupplementalFetchResult
}

/** Required sources remain observable even when only some pages could be fetched. */
data class WahlapSupplementalFetchResult(
    val pages: List<WahlapSupplementalPage> = emptyList(),
    val failures: List<WahlapSupplementalFailure> = emptyList(),
)

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

/**
 * Overall outcome of one import run.
 *
 * COMPLETE: every difficulty (and supplemental page) was fetched and persisted.
 * PARTIAL: at least one difficulty (or supplemental page) fetch failed, but other sources
 *          produced records that were safely persisted; failed difficulties keep their
 *          pre-existing local records.
 * FAILED:  nothing was fetched and parsed, so nothing was persisted.
 */
enum class WahlapImportOutcome {
    COMPLETE,
    PARTIAL,
    FAILED,
}

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
    val outcome: WahlapImportOutcome = WahlapImportOutcome.COMPLETE,
) {
    val isCompleteSuccess: Boolean = outcome == WahlapImportOutcome.COMPLETE
}

class RealWahlapImportAdapter(
    private val parser: WahlapFixtureParser = WahlapFixtureParser(),
    private val pipeline: FakeImportPipeline = FakeImportPipeline(),
    private val difficulties: List<Difficulty> = Difficulty.entries,
    private val sanitizeFailure: (String) -> String = { it },
    private val observer: ImportTimingObserver = ImportTimingObserver.None,
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
            coroutineContext.ensureActive()
            val html = runCatching {
                pageProvider.fetchScorePage(difficulty)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                failures += difficultyFailure(difficulty, error)
                return@forEach
            }

            fetchedDifficultyCount += 1
            val parsed = runCatching {
                observer.measure(DiagnosticStage.PARSING) { parser.parse(html, difficulty) }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                failures += difficultyFailure(difficulty, error)
                return@forEach
            }
            parsedRecords += parsed
        }

        supplementalPageProvider?.let { provider ->
            val supplemental = runCatching { provider.fetchSupplementalPages() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    WahlapSupplementalFetchResult(
                        failures = listOf(WahlapSupplementalFailure(
                            label = "supplemental",
                            message = sanitizeFailure(error.message ?: error::class.java.simpleName),
                        )),
                    )
                }
            supplementalFailures += supplemental.failures.map { it.copy(message = sanitizeFailure(it.message)) }
            fetchedSupplementalPageCount = supplemental.pages.size
            supplemental.pages.forEach { page ->
                val parsed = runCatching {
                    observer.measure(DiagnosticStage.PARSING) { parser.parseMixedDifficultyPage(page.html) }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
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

        // An import aborts only when failures left nothing to persist. A failure-free run with
        // zero parsed records is a legitimate empty-account import and still writes its batch.
        coroutineContext.ensureActive()
        if ((failures.isNotEmpty() || supplementalFailures.isNotEmpty()) && parsedRecords.isEmpty()) {
            return RealWahlapImportResult(
                importResult = ImportResult(
                    batchId = "",
                    inserted = 0,
                    updated = 0,
                    skippedDuplicate = 0,
                    quarantined = 0,
                    rejected = failures.size + supplementalFailures.size,
                ),
                parsedRecordCount = 0,
                fetchedDifficultyCount = fetchedDifficultyCount,
                failedDifficultyCount = failures.size,
                failures = failures,
                fetchedSupplementalPageCount = fetchedSupplementalPageCount,
                parsedSupplementalRecordCount = parsedSupplementalRecordCount,
                supplementalFailures = supplementalFailures,
                outcome = WahlapImportOutcome.FAILED,
            )
        }

        // Partial-safe persistence: records parsed from successful difficulties (and supplemental
        // pages) are persisted even when other difficulties failed. The pipeline only upserts
        // fetched records and never deletes, so failed difficulties keep their pre-existing local
        // records instead of being invalidated by the whole run.
        val importResult = pipeline.importParsedRecords(
            source = source,
            parsed = parsedRecords,
            persistence = persistence,
        )

        val outcome = if (failures.isEmpty() && supplementalFailures.isEmpty()) {
            WahlapImportOutcome.COMPLETE
        } else {
            WahlapImportOutcome.PARTIAL
        }

        return RealWahlapImportResult(
            importResult = importResult,
            parsedRecordCount = parsedRecords.size,
            fetchedDifficultyCount = fetchedDifficultyCount,
            failedDifficultyCount = failures.size,
            failures = failures,
            fetchedSupplementalPageCount = fetchedSupplementalPageCount,
            parsedSupplementalRecordCount = parsedSupplementalRecordCount,
            supplementalFailures = supplementalFailures,
            outcome = outcome,
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
