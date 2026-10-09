package dev.fluentmai.android

import android.os.SystemClock
import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*

internal fun interface ImportMonotonicClock { fun nowNanos(): Long }

/** One sequential import execution; nested measurements are accounted for exclusively. */
internal class ImportDiagnosticCollector(
    private val mode: DiagnosticImportMode,
    private val clock: ImportMonotonicClock = ImportMonotonicClock(SystemClock::elapsedRealtimeNanos),
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ImportTimingObserver {
    private data class Scope(val stage: DiagnosticStage, val start: Long, var children: Long = 0)
    private data class Timing(var duration: Long = 0, var exclusive: Long = 0, var count: Long = 0,
        var failed: Boolean = false, var incomplete: Boolean = false)
    private val admitted = clock.nowNanos()
    private var importStarted: Long? = null
    private var waitingEnded: Long? = null
    private val stack = mutableListOf<Scope>()
    private val timings = mutableMapOf<DiagnosticStage, Timing>()
    private val requests = linkedMapOf<Triple<DiagnosticStage, DiagnosticRequestCategory, DiagnosticRequestLabel>, DiagnosticRequestStats>()
    private val failures = mutableSetOf<DiagnosticFailure>()
    private val recoveries = mutableMapOf<DiagnosticStage, Long>()
    private var counters = DiagnosticCounters()
    private var sealed = false
    private var bounded = false
    private var finalReport: ImportDiagnosticReport? = null

    @Synchronized fun beginImport() {
        if (sealed || importStarted != null) return
        importStarted = clock.nowNanos()
        if (mode == DiagnosticImportMode.WECHAT_OAUTH) waitingEnded = importStarted
    }
    @Synchronized override fun started(stage: DiagnosticStage) {
        if (!sealed) stack += Scope(stage, clock.nowNanos())
    }
    @Synchronized override fun finished(stage: DiagnosticStage, failed: Boolean, interrupted: Boolean) {
        closeScope(stage, failed, interrupted, clock.nowNanos())
    }
    private fun closeScope(stage: DiagnosticStage, failed: Boolean, interrupted: Boolean, at: Long) {
        if (sealed || stack.lastOrNull()?.stage != stage) return
        val scope = stack.removeAt(stack.lastIndex)
        val elapsed = (at - scope.start).coerceAtLeast(0)
        stack.lastOrNull()?.let { it.children += elapsed }
        val timing = timings.getOrPut(stage) { Timing() }
        timing.duration += elapsed
        timing.exclusive += (elapsed - scope.children).coerceAtLeast(0)
        timing.count++
        timing.failed = timing.failed || failed
        timing.incomplete = timing.incomplete || interrupted
        if (failed && !interrupted) failures += when (stage) {
            DiagnosticStage.PARSING -> DiagnosticFailure.PARSING
            DiagnosticStage.DATABASE_PERSISTENCE -> DiagnosticFailure.PERSISTENCE
            DiagnosticStage.SONG_CATALOG -> DiagnosticFailure.SONG_CATALOG
            DiagnosticStage.LOGIN_HOME, DiagnosticStage.CALLBACK_PROCESSING -> DiagnosticFailure.REQUEST
            DiagnosticStage.SUPPLEMENTAL -> DiagnosticFailure.SUPPLEMENTAL
            DiagnosticStage.RECENT_RECORDS -> DiagnosticFailure.RECENT_RECORDS
            DiagnosticStage.PC_CAPTURE -> DiagnosticFailure.PC_CAPTURE
            else -> DiagnosticFailure.DIFFICULTY
        }
    }
    @Synchronized fun failure(failure: DiagnosticFailure) { if (!sealed) failures += failure }
    @Synchronized override fun recoveryRetry(stage: DiagnosticStage) {
        if (!sealed) recoveries[stage] = add(recoveries[stage] ?: 0, 1)
    }
    @Synchronized fun stageFailure(stage: DiagnosticStage, failure: DiagnosticFailure) {
        if (!sealed) { failures += failure; timings[stage]?.failed = true }
    }
    @Synchronized fun result(value: RealWahlapImportResult) {
        if (sealed) return
        counters = DiagnosticCounters(value.parsedRecordCount.toLong(), value.importResult.inserted.toLong(),
            value.importResult.updated.toLong(), value.importResult.quarantined.toLong(), value.importResult.rejected.toLong(),
            value.importResult.skippedDuplicate.toLong(), value.fetchedPlayRecordCount.toLong().takeIf { value.activityCaptureAttempted },
            value.fetchedPlayCountCharts.toLong().takeIf { timings[DiagnosticStage.PC_CAPTURE]?.count?.let { it > 0 } == true })
        if (value.failures.isNotEmpty()) failures += DiagnosticFailure.DIFFICULTY
        if (value.supplementalFailures.isNotEmpty()) {
            failures += DiagnosticFailure.SUPPLEMENTAL
            timings[DiagnosticStage.SUPPLEMENTAL]?.failed = true
        }
        if (value.failedPlayPageCount > 0) {
            failures += DiagnosticFailure.RECENT_RECORDS
            timings[DiagnosticStage.RECENT_RECORDS]?.failed = true
        }
    }
    @Synchronized fun attempt(category: WahlapRequestCategory, label: DiagnosticRequestLabel, log: WahlapAttemptLog) {
        if (sealed) return
        // category/errorType strings in legacy attempt logs are deliberately ignored.
        val stage = stack.lastOrNull()?.stage ?: DiagnosticStage.LOGIN_HOME
        val typedCategory = DiagnosticRequestCategory.valueOf(category.name)
        val safeLabel = if (stage == DiagnosticStage.PC_CAPTURE && label == DiagnosticRequestLabel.RECENT_PAGE)
            DiagnosticRequestLabel.PC_RANKING else label
        val key = Triple(stage, typedCategory, safeLabel)
        if (key !in requests && requests.size >= 64) { bounded = true; return }
        val old = requests[key] ?: DiagnosticRequestStats(stage, typedCategory, safeLabel)
        val chars = log.responseChars?.coerceIn(0, MAX_DIAGNOSTIC_NUMBER)
        val statusCounts = old.httpStatusCounts.associate { it.status to it.attempts }.toMutableMap()
        log.httpStatus?.takeIf { it in 100..599 }?.let { status ->
            if (status in statusCounts || statusCounts.size < 8) statusCounts[status] = add(statusCounts[status] ?: 0, 1)
            else bounded = true
        }
        requests[key] = old.copy(attempts = add(old.attempts, 1), retries = add(old.retries, if (log.attempt > 1) 1 else 0),
            successfulRequests = add(old.successfulRequests, if (log.outcome == WahlapAttemptOutcome.SUCCESS) 1 else 0),
            failedRequests = add(old.failedRequests, if (log.outcome != WahlapAttemptOutcome.SUCCESS && !log.willRetry) 1 else 0),
            attemptDurationMs = add(old.attemptDurationMs, log.elapsedMs.coerceAtLeast(0)),
            lastHttpStatus = log.httpStatus?.takeIf { it in 100..599 },
            responseChars = if (chars == null) old.responseChars else add(old.responseChars ?: 0, chars),
            httpStatusCounts = statusCounts.map { DiagnosticHttpStatusCount(it.key, it.value) })
    }
    private fun add(a: Long, b: Long): Long {
        if (a > MAX_DIAGNOSTIC_NUMBER - b.coerceAtMost(MAX_DIAGNOSTIC_NUMBER)) bounded = true
        return (a + b.coerceAtMost(MAX_DIAGNOSTIC_NUMBER)).coerceAtMost(MAX_DIAGNOSTIC_NUMBER)
    }
    @Synchronized fun finish(state: ImportTaskState, auth: List<DiagnosticAuthMilestone> = emptyList()): ImportDiagnosticReport {
        finalReport?.let { return it }
        val ended = clock.nowNanos()
        while (stack.isNotEmpty()) closeScope(stack.last().stage, failed = false, interrupted = true, at = ended)
        sealed = true
        val termination = when {
            state.failureCategory == ImportFailureCategory.CANCELLED -> DiagnosticTermination.CANCELLED
            state.failureCategory == ImportFailureCategory.AUTHORIZATION_RETRY_REQUIRED ||
                (state.result == null && DiagnosticFailure.AUTHORIZATION_REJECTED in failures) -> DiagnosticTermination.AUTH_REJECTED
            else -> DiagnosticTermination.FINISHED
        }
        val outcome = if (termination != DiagnosticTermination.FINISHED) null else when {
            state.complete -> DiagnosticOutcome.COMPLETE
            state.succeeded -> DiagnosticOutcome.PARTIAL
            else -> DiagnosticOutcome.FAILED
        }
        state.failureCategory?.let { failures += when (it) {
            ImportFailureCategory.AUTHORIZATION_RETRY_REQUIRED -> DiagnosticFailure.AUTHORIZATION_REJECTED
            ImportFailureCategory.CAPTURE_VPN -> DiagnosticFailure.CAPTURE
            ImportFailureCategory.AUTHORIZE_GENERATION -> DiagnosticFailure.AUTHORIZE_GENERATION
            ImportFailureCategory.NETWORK_TRANSPORT -> DiagnosticFailure.NETWORK
            ImportFailureCategory.CANCELLED -> DiagnosticFailure.CANCELLED
            else -> DiagnosticFailure.REQUEST
        } }
        val total = importStarted?.let { (ended - it).coerceAtLeast(0) }
        return ImportDiagnosticReport(appVersion = APP_VERSION, createdAtEpochMs = wallClock(), mode = mode,
            outcome = outcome, termination = termination, totalDurationMs = total?.div(1_000_000),
            executionDurationMs = (ended - admitted).coerceAtLeast(0) / 1_000_000,
            authorizationWaitDurationMs = if (mode == DiagnosticImportMode.MANUAL_COOKIE) null else
                ((waitingEnded ?: ended) - admitted).coerceAtLeast(0) / 1_000_000,
            unattributedDurationMs = total?.let { (it / 1_000_000 - timings.values.sumOf { t -> t.exclusive / 1_000_000 }).coerceAtLeast(0) },
            stages = DiagnosticStage.entries.map { stage ->
                timings[stage]?.let { t -> DiagnosticStageTiming(stage,
                    if (t.incomplete) DiagnosticObservation.INCOMPLETE else if (t.failed) DiagnosticObservation.FAILED else DiagnosticObservation.COMPLETE,
                    t.duration / 1_000_000, t.exclusive / 1_000_000, t.count, recoveries[stage] ?: 0) } ?: DiagnosticStageTiming(stage)
            }, requests = requests.values.toList(), counters = counters, failures = failures.toSet(), authMilestones = auth,
            metricsBounded = bounded).also { finalReport = it }
    }
}

internal const val MAX_DIAGNOSTIC_NUMBER = 1_000_000_000_000L

/** Exact database-call scopes; does not change ordering, records, batching, or exception behavior. */
internal class DiagnosticImportPersistence(private val delegate: ImportPersistence, private val observer: ImportTimingObserver) : ImportPersistence {
    override suspend fun findExistingScoreIds(scoreIds: Set<String>) = observer.measure(DiagnosticStage.DATABASE_PERSISTENCE) { delegate.findExistingScoreIds(scoreIds) }
    override suspend fun insertScoreRecords(records: List<ScoreRecord>) = observer.measure(DiagnosticStage.DATABASE_PERSISTENCE) { delegate.insertScoreRecords(records) }
    override suspend fun insertQuarantineRecords(records: List<QuarantineRecord>) = observer.measure(DiagnosticStage.DATABASE_PERSISTENCE) { delegate.insertQuarantineRecords(records) }
    override suspend fun insertImportBatch(batch: ImportBatch) = observer.measure(DiagnosticStage.DATABASE_PERSISTENCE) { delegate.insertImportBatch(batch) }
}

/** Exact-match allowlist. Never derives labels from URLs, titles, HTML, or exception messages. */
internal fun diagnosticRequestLabel(label: String): DiagnosticRequestLabel {
    val fixed = label.removePrefix("manual-")
    return when (fixed) {
        "auth" -> DiagnosticRequestLabel.AUTH_CALLBACK
        "home" -> DiagnosticRequestLabel.HOME
        "rating-target-music" -> DiagnosticRequestLabel.RATING_TARGET
        "play-records" -> DiagnosticRequestLabel.RECENT_PAGE
        "music-detail" -> DiagnosticRequestLabel.MUSIC_DETAIL
        "player-collection" -> DiagnosticRequestLabel.PLAYER_COLLECTION
        else -> Difficulty.entries.firstOrNull { fixed == "score ${it.name}" }?.let { DiagnosticRequestLabel.valueOf(it.name) }
            ?: DiagnosticRequestLabel.OTHER
    }
}
